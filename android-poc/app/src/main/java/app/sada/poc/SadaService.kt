package app.sada.poc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.ToneGenerator
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.KeyEvent
import app.sada.poc.PocStateMachine.Phase
import app.sada.poc.PocStateMachine.StepKind
import app.sada.poc.dsp.FrontEnd
import app.sada.poc.dsp.ReferenceBuffer
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Stage 0 foreground service: the whole hands-free cycle on the phone, offline.
 *   podcast (own player) → phone mic → echo cancellation when on the speaker → "Hey Sada" → pause →
 *   best microphone for the question (earbud mic if available) → record until the user stops →
 *   replay what was heard (no AI in Stage 0) → "Continue" → resume at the exact position.
 * Must be started while the app is on screen (Android rule for background microphone use).
 */
class SadaService : Service() {
    companion object {
        const val ACTION_START = "app.sada.poc.START"
        const val ACTION_STOP = "app.sada.poc.STOP"
        const val ACTION_MARK_MISS = "app.sada.poc.MARK_MISS"
        const val ACTION_RECORD_CHECK = "app.sada.poc.RECORD_CHECK"
        const val ACTION_ASK = "app.sada.poc.ASK"
        const val ACTION_TOGGLE = "app.sada.poc.TOGGLE"
        const val ACTION_VOLUME_KEY = "app.sada.poc.VOLUME_KEY"
        const val EXTRA_DIRECTION = "direction"
        const val EXTRA_WAKE_WORD = "wakeWord"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_SENSITIVITY = "sensitivity"
        const val EXTRA_MIC_MODE = "micMode"
        const val EXTRA_PLAYBACK = "playback"
        const val EXTRA_AEC = "aec"
        const val EXTRA_EARBUD_MIC = "earbudMic"
        const val EXTRA_LISTEN_EARBUDS = "listenEarbuds"
        const val EXTRA_SPEAKER_MODE = "speakerMode"
        const val EXTRA_ANDROID_AEC = "androidAec"
        private const val CHANNEL = "sada-stage0"
        private const val NOTIF_ID = 7
        private const val VOL_MAX = 15
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val machine = PocStateMachine()
    private lateinit var audio: AudioManager
    private val reference = ReferenceBuffer(SAMPLE_RATE * 4)
    private val frontEnd = FrontEnd(reference)
    private var player: PodcastPlayer? = null
    private var input: AudioInput? = null
    private var detector: WakeDetector? = null
    @Volatile private var capture: QuestionCapture? = null
    @Volatile private var captureOnPhoneMic = false
    private var captureInput = "-"
    private var captureSetupMs = 0L
    @Volatile private var captureMicLabel = "-"
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private val ttsDone = ConcurrentHashMap<String, CountDownLatch>()
    private var session: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var focus: AudioFocusRequest? = null
    private var recordingCallback: AudioManager.AudioRecordingCallback? = null
    private var wakeAtMs = 0L
    private var continueAtMs = 0L
    private var savedPositionMs = 0L
    private var earbudMic = true
    private var started = false
    private var phaseSince = SystemClock.elapsedRealtime()
    private var lastPhase = ""

    /** Publishes the current phase and remembers when it started (for the stall watchdog). */
    private fun syncPhase() {
        val p = machine.phase.name
        if (p != lastPhase) {
            lastPhase = p; phaseSince = SystemClock.elapsedRealtime()
            Diag.stateText = stateText(machine.phase)
            if (Diag.serviceRunning) try {
                getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(Diag.stateText))
            } catch (_: Exception) {}
        }
        Diag.phase = p
        refreshSessionState()
    }
    @Volatile private var alive = false

    /** Earbud listening experiment: the whole session runs in call mode on the headset (mic and output). */
    private var earbudListening = false
    @Volatile private var reconnecting = false
    private var lastReconnectAt = 0L

    /** Call audio: Android routes it to the communication device (the earbuds' call link). */
    private val voiceAttrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** Where Sada's own sound goes: media (Build #8 behaviour) or call audio (earbud listening). */
    private val outAttrs: AudioAttributes get() = if (earbudListening) voiceAttrs else mediaAttrs

    /** Phone Speaker Mode: podcast on the built-in speaker, phone mic for everything. */
    private var speakerMode = false
    private var androidAec = false

    private fun speakerOutput(): AudioDeviceInfo? =
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

    private fun scoOutput(): AudioDeviceInfo? =
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }

    private val mediaAttrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Diag.init(this)
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopEverything("Stopped from the notification or app"); return START_NOT_STICKY }
            ACTION_MARK_MISS -> { runSteps(machine.onMarkMiss(), "button:screen"); return START_NOT_STICKY }
            ACTION_ASK -> { activate("notification or app button"); return START_NOT_STICKY }
            ACTION_TOGGLE -> { runSteps(machine.onTogglePlayback(), "button:notification"); return START_NOT_STICKY }
            ACTION_VOLUME_KEY -> { onVolumeKey(intent?.getIntExtra(EXTRA_DIRECTION, 0) ?: 0, "app on screen"); return START_NOT_STICKY }
            ACTION_RECORD_CHECK -> { startMicCheck(); return START_NOT_STICKY }
        }
        if (!goForeground()) { stopSelf(); return START_NOT_STICKY }
        if (!started) {
            val source = intent?.getStringExtra(EXTRA_SOURCE)
            val playback = intent?.getBooleanExtra(EXTRA_PLAYBACK, true) ?: true
            wakeWord = intent?.getBooleanExtra(EXTRA_WAKE_WORD, false) ?: false // read before the microphone starts
            Diag.wakeWordOn = wakeWord
            if (playback && source == null) {
                Diag.log("error", "No podcast chosen"); stopEverything("No source")
            } else {
                startEverything(
                    source, playback,
                    intent?.getStringExtra(EXTRA_SENSITIVITY) ?: "default",
                    intent?.getStringExtra(EXTRA_MIC_MODE) ?: "recognition",
                    intent?.getBooleanExtra(EXTRA_AEC, true) ?: true,
                    intent?.getBooleanExtra(EXTRA_EARBUD_MIC, true) ?: true,
                    intent?.getBooleanExtra(EXTRA_LISTEN_EARBUDS, false) ?: false,
                    intent?.getBooleanExtra(EXTRA_SPEAKER_MODE, false) ?: false,
                    intent?.getBooleanExtra(EXTRA_ANDROID_AEC, false) ?: false,
                )
            }
        }
        // Never restarted by Android on its own: a restarted service may not use the microphone from the background.
        return START_NOT_STICKY
    }

    /** What the user sees on the lock screen and in the app for each state. */
    private fun stateText(p: Phase): String = when (p) {
        Phase.LISTENING -> "Listening to the podcast. Double-press Volume Up to ask a question."
        Phase.ARMING -> "Question Mode: getting the microphone ready… wait for “Go ahead”"
        Phase.ASKING -> "Listening to your question ($captureMicLabel)… double-press Volume Up to finish"
        Phase.ANSWERING -> "Thinking… then answering"
        Phase.WAIT_CONTINUE -> "Paused. Double-press Volume Up or say “Continue” to resume."
        Phase.STOPPED -> "Starting…"
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        fun action(code: Int, act: String) = PendingIntent.getService(this, code, Intent(this, SadaService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Sada")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC) // buttons usable from the lock screen
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Ask Sada", action(2, ACTION_ASK)).build())
            .addAction(Notification.Action.Builder(null, "Pause / Continue", action(3, ACTION_TOGGLE)).build())
            .addAction(Notification.Action.Builder(null, "Stop", action(1, ACTION_STOP)).build())
            .build()
    }

    private fun goForeground(): Boolean {
        val notif = buildNotification(stateText(machine.phase))
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else startForeground(NOTIF_ID, notif)
            true
        } catch (e: Exception) {
            Diag.log("error", "Could not start the foreground service: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    // ---------------- start / stop ----------------

    private fun startEverything(source: String?, playback: Boolean, sensitivity: String, micMode: String, aec: Boolean, earbud: Boolean,
                                listenEarbuds: Boolean, speaker: Boolean, platformAec: Boolean) {
        earbudListening = listenEarbuds
        speakerMode = speaker && !listenEarbuds
        androidAec = platformAec && speakerMode
        Diag.usageMode = when {
            listenEarbuds -> "EARBUD CALL MODE (experimental)"
            speakerMode -> "PHONE SPEAKER MODE (podcast on the phone speaker, phone mic)"
            else -> "earbuds + phone mic (Build #8)"
        }
        Diag.listenMic = if (listenEarbuds) "EARBUDS (call mode, experimental)" else "phone"
        Diag.aecKind = when { !speakerMode -> "automatic (only if the podcast plays on the speaker)"; androidAec -> "Android built-in (experimental)"; aec -> "Sada's own (reference signal)"; else -> "OFF" }
        Diag.playbackRequested = when {
            !playback -> "none"
            listenEarbuds -> "earbuds, call audio (Bluetooth SCO)"
            speakerMode -> "phone speaker"
            else -> "media output (earbuds via A2DP, or speaker)"
        }
        started = true
        alive = true
        earbudMic = earbud && !speakerMode // speaker mode: never switch to an earbud mic for questions
        Diag.checkLostSession(this)
        Diag.serviceRunning = true
        Diag.startedAt = System.currentTimeMillis()
        Diag.playback = playback
        Diag.aecWanted = aec
        Diag.earbudMicWanted = earbud
        Diag.source = when {
            !playback -> "none (listen-only test)"
            source!!.startsWith("file:") -> "test episode (phone voice)"
            else -> "your audio file"
        }
        Diag.batteryAtStart = batteryPct()
        Diag.resetInputCounters()
        Diag.log("session-start", "Test started (${Diag.testMode}; playback ${if (playback) "on" else "off"}; echo cancellation ${if (aec) "on" else "off"}; " +
            "earbud mic for questions ${if (earbud) "on" else "off"}; mic $micMode; sensitivity $sensitivity; listening microphone ${Diag.listenMic})")

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sada:stage0").also { it.acquire(6 * 60 * 60 * 1000L) }
        setUpTts()
        setUpMediaSession()
        watchRecording()

        MicCheck.deleteOlderThanHours(this, 24)
        AudioRouteGuard.repairIfNeeded(audio, "At test start")
        Diag.log("route", "At start: ${AudioRouteGuard.describe(audio)}")
        val det = WakeDetector(this)
        if (!det.load(sensitivity)) { stopEverything("Keyword model failed to load"); return }
        detector = det
        det.onCanary = { ok, ms -> main.post { onCanaryResult(ok, ms) } }
        prepareCanary(det)
        registerNoisyReceiver()

        if (!listenEarbuds) { startAudio(source, playback, micMode, aec); return }

        // Earbud listening: establish the call-mode route first, and only continue once Android confirms it.
        val dev = EarbudMic.device(audio)
        if (dev == null) {
            Diag.earbudRoute = "NOT AVAILABLE: Android lists no Bluetooth headset microphone (${earbudMicState()})"
            Diag.log("error", "Earbud listening: ${Diag.earbudRoute}")
            stopEverything("Earbud listening: no headset microphone")
            return
        }
        Diag.earbudRoute = "requesting ${AudioNames.describe(dev)}…"
        Diag.log("earbud-route", "Requesting call mode on ${AudioNames.describe(dev)}")
        worker.execute {
            val ms = EarbudMic.connect(audio, dev, 6000)
            main.post {
                if (!alive) { worker.execute { AudioRouteGuard.release(audio) }; return@post }
                if (ms < 0) {
                    Diag.earbudRoute = "FAILED: Android did not select the headset within 6 s (${AudioRouteGuard.describe(audio)})"
                    Diag.log("error", "Earbud listening: ${Diag.earbudRoute}")
                    stopEverything("Earbud listening: route not established")
                } else {
                    Diag.earbudRoute = "active (ready after $ms ms): ${AudioRouteGuard.describe(audio)}"
                    Diag.log("earbud-route", Diag.earbudRoute)
                    startAudio(source, playback, micMode, aec)
                }
            }
        }
    }

    /** Starts the podcast, the microphone and listening (after the earbud route is up, in earbud mode). */
    private fun startAudio(source: String?, playback: Boolean, micMode: String, aec: Boolean) {
        if (playback) {
            val p = PodcastPlayer(
                this, Uri.parse(source!!), outAttrs, reference,
                onEvent = { kind, text -> Diag.log(kind, text) },
                preferredOutput = { if (earbudListening) scoOutput() else if (speakerMode) speakerOutput() else null },
            )
            player = p
            requestFocus()
            p.start()
            setPlaying(true)
        }
        val inp = AudioInput(this, frontEnd, { player }) { m, a, mName, aName -> onMicBlock(m, a, mName, aName) }
        inp.aecAllowed = aec && !androidAec // Sada's own canceller, unless the Android one is being tested
        inp.onWrongRoute = { actual -> main.post { onEarbudRouteLost("the microphone is on $actual, not the headset") } }
        input = inp
        val started = inp.start(micMode, earbudListening, androidAec)
        if (started && !wakeWord && !earbudListening) {
            inp.pauseCapture() // no always-on listening: the microphone opens only for a question or for "Continue"
            Diag.log("mic", "Voice wake word off: microphone closed until you ask a question")
        }
        if (!started) { stopEverything(if (earbudListening) "Earbud microphone failed to open" else "Phone microphone failed"); return }
        machine.start()
        syncPhase()
        Diag.earbudMicStatus = earbudMicState()
        main.postDelayed({ refreshRoutes(log = true) }, 2000)
        main.post(heartbeat)
    }

    private fun stopEverything(reason: String) {
        alive = false
        main.removeCallbacksAndMessages(null)
        capture?.finishNow()
        input?.stop(); input = null
        detector?.close(); detector = null
        unregisterNoisyReceiver()
        player?.stop(); player = null
        // Always withdraw our call-mode request, whatever state the question flow was in (it only affects this app).
        AudioRouteGuard.release(audio)
        tts?.shutdown(); tts = null
        session?.release(); session = null
        focus?.let { audio.abandonAudioFocusRequest(it) }
        recordingCallback?.let { audio.unregisterAudioRecordingCallback(it) }
        recordingCallback = null
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        machine.stop()
        syncPhase()
        if (Diag.serviceRunning) Diag.log("session-stop", reason)
        Diag.serviceRunning = false
        try { File(filesDir, "heartbeat.txt").writeText("stopped\t${System.currentTimeMillis()}\t0") } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        started = false
    }

    override fun onDestroy() {
        if (Diag.serviceRunning) stopEverything("Service destroyed")
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Diag.log("info", "App swiped away from recent apps; the service keeps running")
        super.onTaskRemoved(rootIntent)
    }

    // ---------------- microphone blocks (mic thread) ----------------

    private fun onMicBlock(m: FloatArray, a: FloatArray, mName: String, aName: String) {
        val q = capture
        if (q != null && captureOnPhoneMic && !q.done) {
            if (q.push(a, m)) main.post { questionDone() }
        }
        val hits = detector?.process(m, a, mName, aName) ?: return
        for (h in hits) main.post { onKeyword(h) }
    }

    // ---------------- flow ----------------

    private fun onKeyword(h: WakeDetector.Hit) {
        val steps = machine.onKeyword(h.label)
        if (steps.isEmpty()) Diag.log("ignored", "Heard ${h.label} before the test was ready (state ${machine.phase})")
        for (s in steps) execute(s, h.atMs, h.label, h.stream)
        syncPhase()
    }

    /** Runs the steps the state machine returned for a button or notification action. */
    private fun runSteps(steps: List<StepKind>, label: String) {
        for (st in steps) execute(st, System.currentTimeMillis(), label, "")
        syncPhase()
    }

    /** Every non-voice activation (earbud double-tap, notification, app button) leads into the same flow as "Hey Sada". */
    private fun activate(source: String) {
        lastActivationAt = SystemClock.elapsedRealtime()
        runSteps(machine.onActivate(), "button:$source")
    }

    private fun execute(step: StepKind, atMs: Long, label: String, stream: String) {
        when (step) {
            StepKind.WAKE_DETECTED -> { wakeAtMs = atMs; pendingActivation = true; lastActivationAt = SystemClock.elapsedRealtime() }
            StepKind.PAUSE_PODCAST -> {
                detector?.muted = true
                val p = player
                if (p != null && p.isPlaying) { p.pause(); savedPositionMs = p.positionMs() }
                setPlaying(false)
                when {
                    pendingActivation && !label.startsWith("button:") -> {
                        val e = Diag.log("wake", "Heard $label on $stream → paused at ${savedPositionMs / 1000.0}s", System.currentTimeMillis() - wakeAtMs)
                        if (Diag.testMode == "silence") Diag.setFalse(e.t, true)
                    }
                    pendingActivation -> Diag.log("activate", "Activated by ${label.removePrefix("button:")} → paused at ${savedPositionMs / 1000.0}s")
                    else -> Diag.log("paused", "Paused by ${label.removePrefix("button:")} at ${savedPositionMs / 1000.0}s")
                }
                pendingActivation = false
            }
            StepKind.PREPARE_QUESTION_INPUT -> prepareQuestionInput()
            StepKind.START_CAPTURE -> Diag.log("asking", "Recording the question ($captureInput)")
            StepKind.STOP_CAPTURE -> capture?.finishNow()
            StepKind.ANSWER -> answer()
            StepKind.LISTEN_FOR_CONTINUE -> {
                input?.resumeCapture() // listen for "Continue"
                detector?.flushNow()
                main.postDelayed({ detector?.muted = false }, 300)
                Diag.log("waiting", "Waiting for “Continue”")
            }
            StepKind.CONTINUE_DETECTED -> continueAtMs = atMs
            StepKind.RESUME -> {
                resume(label)
                if (!wakeWord && !earbudListening) input?.pauseCapture() // back to podcast only: microphone closed
            }
            StepKind.CONTINUE_WHILE_PLAYING -> Diag.log("continue-while-playing", "Heard $label on $stream while the podcast was playing (false trigger)")
            StepKind.IGNORED_BUSY -> Diag.log("ignored", "Heard $label while busy (${machine.phase})")
            StepKind.WAKE_WHILE_PAUSED -> Diag.log("wake-while-paused", "Heard $label while paused; say Continue")
            StepKind.MISSED_WAKE -> Diag.log("miss-wake", "You marked a missed “Hey Sada” ($label)")
            StepKind.UNKNOWN_KEYWORD -> Diag.log("unknown", "Unexpected keyword $label")
            StepKind.RECOVERED -> {
                Diag.log("recovered", "A step stalled in ${lastPhase} for over 30 s; recovered (say Continue to resume)")
                capture?.finishNow()
                capture = null
                captureOnPhoneMic = false
                val inp = input
                if (!earbudListening) worker.execute { finishEarbud(inp) }
            }
        }
    }

    private fun earbudMicState(): String {
        val dev = EarbudMic.device(audio)
        return when {
            Build.VERSION.SDK_INT < 31 -> "needs Android 12+"
            dev == null -> "no Bluetooth headset microphone connected"
            else -> "available: ${AudioNames.describe(dev)}"
        }
    }

    /** Picks the microphone for the question: the earbud mic when connected (much closer to the mouth), else the phone mic. */
    private fun prepareQuestionInput() {
        if (earbudListening) { startPhoneCapture("earbud mic (continuous)"); return }
        val dev = if (earbudMic) EarbudMic.device(audio) else null
        Diag.earbudMicStatus = earbudMicState()
        if (dev == null) { startPhoneCapture(if (earbudMic) "phone mic (no earbud mic connected)" else "phone mic"); return }
        val inp = input
        worker.execute {
            var handedOver = false
            try {
                inp?.pauseCapture()
                SystemClock.sleep(120)
                val ms = if (alive) EarbudMic.connect(audio, dev, 4000) else -1
                if (!alive) return@execute
                if (ms < 0) {
                    main.post {
                        Diag.log("error", "Earbud microphone did not become active within 4 s; using the phone mic")
                        if (alive) startPhoneCapture(MicReadiness.label(MicReadiness.Outcome.FALLBACK_NOT_CONNECTED), MicReadiness.Outcome.FALLBACK_NOT_CONNECTED)
                    }
                } else {
                    handedOver = true // startEarbudCapture now owns the call-mode request and will release it
                    main.post { if (alive) startEarbudCapture(ms) else worker.execute { finishEarbud(inp) } }
                }
            } catch (e: Throwable) {
                Diag.log("error", "Switching to the earbud mic failed: ${e.javaClass.simpleName}: ${e.message}")
                main.post { if (alive) startPhoneCapture(MicReadiness.label(MicReadiness.Outcome.FALLBACK_NOT_CONNECTED), MicReadiness.Outcome.FALLBACK_NOT_CONNECTED) }
            } finally {
                if (!handedOver) finishEarbud(inp)
            }
        }
    }

    /** Ends earbud-mic use: withdraws call mode, waits until Android has really applied it, then reopens the phone mic. */
    private fun finishEarbud(inp: AudioInput?) {
        val clean = AudioRouteGuard.releaseAndWait(audio, 2000)
        if (!clean) Diag.log("route-repair", "After the earbud mic: state not clean yet: ${AudioRouteGuard.describe(audio)}")
        SystemClock.sleep(500) // let Bluetooth return to music quality before anything is played
        inp?.resumeCapture()
    }

    /**
     * Question on the phone microphone (no earbuds, speaker mode, earbud call mode, or fallback). The microphone must
     * be delivering audio before Sada says it is listening; recording starts only after the cue has finished, so the
     * cue is never part of the question.
     */
    private fun startPhoneCapture(label: String, outcome: MicReadiness.Outcome = MicReadiness.Outcome.PHONE) {
        val inp = input
        inp?.resumeCapture() // opens the microphone if it was closed (wake word off)
        captureInput = label
        captureMicLabel = label
        val t0 = SystemClock.elapsedRealtime()
        worker.execute {
            val live = inp?.awaitLiveBlocks(1500) ?: false
            if (!live) Diag.log("error", "Phone microphone delivered no audio within 1.5 s")
            Diag.log("mic-ready", "Question mic: $label; live audio ${if (live) "after ${SystemClock.elapsedRealtime() - t0} ms" else "NOT confirmed"}")
            announceReady(if (live) MicReadiness.prompt(outcome, false) else MicReadiness.noMicPrompt(), null)
            main.post {
                val ph = machine.phase
                // ARMING: a new question. ASKING: continuing the same question after the earbud mic was lost.
                if (!alive || (ph != Phase.ARMING && ph != Phase.ASKING)) return@post
                captureSetupMs = SystemClock.elapsedRealtime() - t0
                capture = QuestionCapture().also { it.skipFirst(250); if (!live) it.finishNow() } // fresh: one microphone per question
                captureOnPhoneMic = live
                if (ph == Phase.ARMING) for (s in machine.onInputReady()) execute(s, System.currentTimeMillis(), "", "")
                syncPhase()
                if (!live) questionDone() // no working microphone: ends without an answer to an empty question
            }
        }
    }

    /**
     * Question on the earbud microphone. Android reporting the call route (setupMs) is not proof of audio: the
     * recorder is opened at once and must deliver live audio (MicReadiness) before Sada says "Go ahead". Everything
     * read before that is discarded (the user has not been invited to speak). If the earbud mic does not deliver audio
     * in time, or Bluetooth drops, call mode is released and the phone microphone is used instead, and the user is told.
     */
    private fun startEarbudCapture(setupMs: Long) {
        captureOnPhoneMic = false
        captureMicLabel = "earbud mic (getting ready)"
        val inp = input
        worker.execute {
            var rec: android.media.AudioRecord? = null
            var fallback: MicReadiness.Outcome? = null
            var asking = false
            var lostMidQuestion = false
            try {
                rec = EarbudMic.open()
                val readiness = MicReadiness()
                val liveMs = if (rec != null && alive) EarbudMic.awaitLive(rec, 1500, readiness) else -1L
                val outcome = MicReadiness.decide(true, setupMs, liveMs >= 0)
                Diag.log("mic-ready", "Earbud route reported after $setupMs ms; recorder on ${AudioNames.describe(rec?.routedDevice)}; " +
                    "live audio ${if (liveMs >= 0) "after $liveMs ms more" else "NOT received within 1.5 s"} → ${MicReadiness.label(outcome)}" +
                    if (readiness.userAlreadySpeaking()) " (you were already speaking)" else "")
                if (outcome != MicReadiness.Outcome.EARBUD_READY || rec == null || !alive) { fallback = outcome; return@execute }
                val r: android.media.AudioRecord = rec
                announceReady(MicReadiness.prompt(outcome, readiness.userAlreadySpeaking()), voiceAttrs)
                EarbudMic.drain(r) // the cue and anything before it are never part of the question
                val q = QuestionCapture().also {
                    it.skipFirst(150)
                    if (!readiness.backgroundDb().isNaN()) it.seedNoiseFloor(readiness.backgroundDb())
                }
                captureInput = "earbud mic"
                captureMicLabel = "earbud mic"
                captureSetupMs = setupMs + liveMs
                capture = q
                val started = CountDownLatch(1)
                main.post {
                    if (alive) { for (st in machine.onInputReady()) execute(st, System.currentTimeMillis(), "", ""); syncPhase() }
                    started.countDown()
                }
                started.await(2, TimeUnit.SECONDS)
                asking = true
                var why = ""
                val result = EarbudMic.recordInto(r, q, { !alive }, { earbudRouteActive(r) }, { why = it })
                if (result == EarbudMic.RecordResult.LOST) {
                    val plan = CaptureHealth.afterLoss(q.heardSpeech, q.speechMs(), q.msSinceSpeech())
                    Diag.log("mic-lost", "Earbud microphone lost while asking ($why); recorded speech ${q.speechMs()} ms, " +
                        "silence since ${q.msSinceSpeech()} ms → $plan")
                    if (plan == CaptureHealth.AfterLoss.RESTART_ON_PHONE) {
                        q.finishNow()
                        capture = null // the earbud recording is discarded: never mixed with the phone microphone
                        fallback = MicReadiness.Outcome.FALLBACK_DISCONNECTED
                        lostMidQuestion = true
                    }
                }
                q.finishNow()
            } catch (e: Throwable) {
                Diag.log("error", "Earbud question error: ${e.javaClass.simpleName}")
                if (!asking) fallback = MicReadiness.Outcome.FALLBACK_NO_AUDIO
            } finally {
                try { rec?.stop() } catch (_: Exception) {}
                try { rec?.release() } catch (_: Exception) {}
                finishEarbud(inp) // release call mode, wait until Android confirms, phone mic back
                val fb = fallback
                // exactly one continuation: a phone-mic fallback (before or during the question), or "question done"
                if (fb != null && (!asking || lostMidQuestion)) main.post { if (alive) startPhoneCapture(MicReadiness.label(fb), fb) }
                else main.post { questionDone() }
            }
        }
    }

    /** Android still reports the earbud call route and the recorder is on it (sampled while recording). */
    private fun earbudRouteActive(rec: android.media.AudioRecord): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        fun bt(t: Int?) = t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || t == AudioDeviceInfo.TYPE_BLE_HEADSET
        return bt(audio.communicationDevice?.type) && bt(rec.routedDevice?.type)
    }

    /** Speaks the readiness cue (worker thread) and waits until it has finished; a tone if no phone voice. */
    private fun announceReady(text: String, attrs: AudioAttributes?) {
        Diag.log("mic-ready", "Saying: “$text”")
        if (!speakAndWait(text, first = false, attrs = attrs)) {
            tone(if (attrs != null) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC)
            SystemClock.sleep(250)
        }
    }

    /** Consented 10-second recording of what the microphone and detector receive (started from the app). */
    private fun startMicCheck() {
        val inp = input
        if (inp == null || !alive) { Diag.recordingCheck = "start a test first"; return }
        val rec = MicCheck.Recorder()
        Diag.recordingCheck = "recording 20 s… stay silent 5 s, then Hey Sada, Continue, Kada (twice each)"
        Diag.log("mic-check", "Recording the 20 s phrase test (stored only on this phone)")
        inp.tap = { raw, det, ref ->
            if (rec.add(raw, det, ref)) {
                inp.tap = null
                worker.execute {
                    val stamp = try { MicCheck.save(this, rec) } catch (e: Exception) { "not saved" }
                    Diag.recordingCheck = "analysing…"
                    val result = MicCheck.analyse(this, rec)
                    Diag.recordingCheck = "$result (recording $stamp)"
                    Diag.log("mic-check", result)
                }
            }
        }
    }

    private fun questionDone() {
        val q = capture ?: return
        if (machine.phase != Phase.ASKING) return
        captureOnPhoneMic = false
        val summary = q.summary(captureInput, captureSetupMs)
        Diag.lastQuestion = summary
        Diag.log("question", summary, if (q.heardSpeech) q.speech().size * 1000L / SAMPLE_RATE else 0L)
        for (s in machine.onQuestionDone()) execute(s, System.currentTimeMillis(), "", "")
        syncPhase()
    }

    /** Stage 0 "answer": play back what was heard, so you can judge the capture yourself. */
    /**
     * The question → transcription (Muse Voice Transcribe) → answer (Muse Spark) → spoken answer, all automatic.
     * The recording itself is never played back. Every failure is said honestly; nothing is invented.
     */
    private fun answer() {
        val q = capture
        capture = null
        val prefs = getSharedPreferences("stage0", Context.MODE_PRIVATE)
        val api = SadaApi(prefs.getString("server", "") ?: "")
        val lang = prefs.getString("lang", "both") ?: "both"
        val level = prefs.getString("level", "B1") ?: "B1"
        val positionMs = savedPositionMs
        worker.execute {
            try {
                if (q == null || !q.usable) {
                    sayFailure(AnswerPlan.Failure.EMPTY_RECORDING, "no usable speech in the recording")
                    return@execute
                }
                tone(AudioManager.STREAM_MUSIC) // "got it, thinking"
                val t0 = SystemClock.elapsedRealtime()
                val wav = Wav.encodeMono16(ShortArray(q.speech().size).also { pcm ->
                    val sp = q.speech()
                    for (i in sp.indices) pcm[i] = (sp[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                }, SAMPLE_RATE)
                val text = api.transcribe(wav)
                val tAsr = SystemClock.elapsedRealtime() - t0
                Diag.lastTranscript = text
                Diag.log("transcript", "“$text” (${tAsr} ms)")
                if (!AnswerPlan.usableTranscript(text)) {
                    sayFailure(AnswerPlan.Failure.NOT_UNDERSTOOD, "transcript not usable: “$text”")
                    return@execute
                }
                val ctx = questionContext(positionMs)
                val result = api.ask(text, lang, level, ctx, positionMs / 1000.0)
                val tAi = SystemClock.elapsedRealtime() - t0 - tAsr
                val en = result.optString("answer_en", "")
                val ar = result.optString("answer_ar", "")
                Diag.lastAnswer = if (en.isNotEmpty()) en else ar
                Diag.log("answer", "Muse Spark answered in ${tAi} ms (${en.length + ar.length} characters)")
                val lines = AnswerPlan.answer(lang, en, ar)
                if (lines.isEmpty()) {
                    sayFailure(AnswerPlan.Failure.AI_ERROR, "empty answer")
                    return@execute
                }
                var first = true
                for (l in lines) { speakIn(l.text, l.lang, first); first = false }
                if (result.optBoolean("is_vocab", false) && result.optString("term", "").isNotBlank()) {
                    SavedWords.add(this, result.optString("term"), result.optString("sentence", ""), if (en.isNotEmpty()) en else ar, ctx.title)
                    Diag.log("saved", "Saved “${result.optString("term")}”")
                }
                speakAndWait(AnswerPlan.RESUME_HINT, first = false)
            } catch (e: SadaApi.Failure) {
                sayFailure(e.kind, e.detail)
            } catch (e: Throwable) {
                sayFailure(AnswerPlan.Failure.AI_ERROR, "${e.javaClass.simpleName}: ${e.message}")
            } finally {
                main.post {
                    for (s in machine.onAnswerDone()) execute(s, System.currentTimeMillis(), "", "")
                    syncPhase()
                }
            }
        }
    }

    /** Says what went wrong (and logs the technical detail), then how to continue. Worker thread. */
    private fun sayFailure(kind: AnswerPlan.Failure, detail: String) {
        Diag.log("answer-error", "$kind: $detail")
        Diag.lastAnswer = "(no answer: $kind)"
        speakAndWait(AnswerPlan.message(kind), first = true)
        speakAndWait("Or say continue to keep listening.", first = false)
    }

    /** Podcast context that is really known: test-episode title and nearby text (approximate), else the title only. */
    private fun questionContext(positionMs: Long): EpisodeContext {
        val isTest = Diag.source.startsWith("test episode")
        if (!isTest) return EpisodeContext.none(if (Diag.source.startsWith("your audio")) "the learner's own audio file" else "")
        val idx = TestEpisode.index(this) ?: return EpisodeContext.none("Sada test episode")
        return EpisodeContext.at(positionMs, idx.first, idx.second, EPISODE_TEXTS)
    }

    /** Speaks one answer line in English or Arabic (worker thread). Falls back with a note if no Arabic voice exists. */
    private fun speakIn(text: String, lang: String, first: Boolean) {
        val t = tts ?: return
        if (lang == "ar") {
            val r = t.setLanguage(Locale("ar"))
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                t.setLanguage(Locale.US)
                Diag.log("error", "No Arabic voice installed on this phone (code $r)")
                speakAndWait("An Arabic voice is not installed on this phone. The Arabic answer is shown in the app.", first)
                return
            }
            try { speakAndWait(text, first) } finally { t.setLanguage(Locale.US) }
        } else {
            speakAndWait(text, first)
        }
    }

    private fun resume(label: String) {
        val ms = System.currentTimeMillis() - continueAtMs
        val p = player
        if (p == null) {
            if (label.startsWith("button:")) Diag.log("resumed", "Resumed by ${label.removePrefix("button:")} (listen-only)", ms)
            else Diag.log("continue", "Heard $label (listen-only: no playback to resume)", ms)
            return
        }
        requestFocus()
        p.play()
        setPlaying(true)
        if (label.startsWith("button:")) Diag.log("resumed", "Resumed by ${label.removePrefix("button:")}", ms)
        else Diag.log("continue", "Heard $label → playing again", ms)
        main.postDelayed({
            Diag.log("resume-drift", "Position after resume vs paused (includes ~0.5 s of playback)", p.positionMs() - savedPositionMs)
        }, 500)
    }

    private fun tone(stream: Int) {
        try {
            val t = ToneGenerator(stream, 70)
            t.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            main.postDelayed({ t.release() }, 400)
        } catch (_: Exception) {}
    }

    // ---------------- speech ----------------

    private fun setUpTts() {
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) { tts?.setLanguage(Locale.US); tts?.setAudioAttributes(outAttrs) }
            Diag.log(if (ttsReady) "tts" else "error", if (ttsReady) "Phone voice ready" else "Phone voice (text-to-speech) not available")
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId?.startsWith("first-") == true) Diag.log("answer-start", "Speaking", System.currentTimeMillis() - wakeAtMs)
            }
            override fun onDone(utteranceId: String?) { utteranceId?.let { ttsDone.remove(it)?.countDown() } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { utteranceId?.let { ttsDone.remove(it)?.countDown() } }
        })
    }

    /**
     * Speaks and waits until finished (worker thread only). attrs: route this utterance differently (e.g. call audio to
     * the earbuds while the earbud microphone is active). Returns false if the phone voice is unavailable.
     */
    private fun speakAndWait(text: String, first: Boolean, attrs: AudioAttributes? = null): Boolean {
        val t = tts
        if (t == null || !ttsReady) { Diag.log("error", "No phone voice; skipped: $text"); return false }
        val id = (if (first) "first-" else "next-") + SystemClock.elapsedRealtime()
        val latch = CountDownLatch(1)
        ttsDone[id] = latch
        if (attrs != null) t.setAudioAttributes(attrs)
        try {
            t.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
            latch.await(15, TimeUnit.SECONDS)
        } finally {
            if (attrs != null) t.setAudioAttributes(outAttrs)
        }
        return true
    }

    // ---------------- earbud button ----------------

    private fun setUpMediaSession() {
        val ms = MediaSession(this, "SadaStage0")
        ms.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                @Suppress("DEPRECATION")
                val ev = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                if (ev != null && ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) {
                    Diag.log("button", "Earbud/media button ${KeyEvent.keyCodeToString(ev.keyCode)}")
                    onMediaKey(ev.keyCode)
                }
                return true
            }
        })
        // Volume keys: with "remote" volume, Android sends volume-key presses for this session to Sada instead of
        // changing the system volume, where Android allows it (verified per state on the device; see the report).
        val vp = object : VolumeProvider(VolumeProvider.VOLUME_CONTROL_ABSOLUTE, VOL_MAX, volLevel) {
            override fun onAdjustVolume(direction: Int) { main.post { onVolumeKey(direction, "media session") } }
            override fun onSetVolumeTo(volume: Int) { main.post { setPodcastVolume(volume) } }
        }
        ms.setPlaybackToRemote(vp)
        volumeProvider = vp
        ms.isActive = true
        session = ms
    }

    private val singleTap = Runnable { runSteps(machine.onTogglePlayback(), "button:earbud tap") }
    private var tapPending = false

    /**
     * Earbud controls: one tap = pause / continue (like any podcast app); two quick taps or the earbud's
     * "next track" gesture = Ask Sada. A single tap waits 600 ms to see whether a second tap follows.
     */
    private fun onMediaKey(code: Int) {
        when (code) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> { cancelSingleTap(); activate("earbud (next-track gesture)") }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                if (machine.phase == Phase.ASKING) { runSteps(machine.onTogglePlayback(), "button:earbud tap"); return } // end the question now
                if (tapPending) { cancelSingleTap(); activate("earbud double-tap") }
                else { tapPending = true; main.postDelayed(singleTap, 600); main.postDelayed({ tapPending = false }, 600) }
            }
            else -> {}
        }
    }

    private fun cancelSingleTap() { main.removeCallbacks(singleTap); tapPending = false }

    // ---------------- earbud listening: keep the call-mode route alive ----------------

    /** The headset route was lost: log it, keep the podcast off the phone speaker, and try to re-establish it. */
    private fun onEarbudRouteLost(why: String) {
        if (!earbudListening || !alive || reconnecting) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastReconnectAt < 10_000) return // at most one attempt every 10 s
        lastReconnectAt = now
        reconnecting = true
        Diag.scoDrops++
        Diag.log("earbud-route", "Lost: $why")
        val d = player?.track?.routedDevice
        if (player?.isPlaying == true && d != null && (d.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || d.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)) {
            runSteps(machine.onTogglePlayback(), "button:route lost (podcast was on the phone speaker)")
        }
        worker.execute {
            val dev = EarbudMic.device(audio)
            val ms = if (dev != null) EarbudMic.connect(audio, dev, 6000) else -1
            main.post {
                reconnecting = false
                if (ms >= 0) {
                    Diag.scoReconnects++
                    Diag.earbudRoute = "re-established after $ms ms: ${AudioRouteGuard.describe(audio)}"
                    Diag.log("earbud-route", "Restored after $ms ms")
                    input?.requestReopen("earbud link restored")
                } else {
                    Diag.earbudRoute = "LOST, retrying (${if (dev == null) "no headset microphone listed" else AudioRouteGuard.describe(audio)})"
                    Diag.log("earbud-route", "Could not restore: ${Diag.earbudRoute}")
                }
            }
        }
    }

    // ---------------- earbuds disconnected ----------------

    private val noisy = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            // Audio is about to move from the earbuds to the speaker: pause instead of playing out loud.
            if (machine.phase == Phase.LISTENING) runSteps(machine.onTogglePlayback(), "button:earbuds disconnecting")
            Diag.log("route", "Earbuds disconnected (audio becoming noisy)")
        }
    }
    private var noisyRegistered = false

    private fun registerNoisyReceiver() {
        if (noisyRegistered) return
        val f = android.content.IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(noisy, f)
        noisyRegistered = true
    }

    private fun unregisterNoisyReceiver() {
        if (!noisyRegistered) return
        try { unregisterReceiver(noisy) } catch (_: Exception) {}
        noisyRegistered = false
    }

    // ---------------- health: is the live detector still working? ----------------

    private var canaryFailStreak = 0
    private var lastCanaryAt = 0L
    private var lastHealthLogAt = 0L
    private var lastActivationAt = 0L
    private var pendingActivation = false

    /** Renders a known "Hey Sada" with the phone's voice once per session, at a normal speech level, for health checks. */
    private fun prepareCanary(det: WakeDetector) {
        worker.execute {
            try {
                val files = PhoneVoice.render(this, listOf("Hey Sada."))
                val f = files?.firstOrNull() ?: run { Diag.canaryLast = "unavailable (no phone voice)"; return@execute }
                val a = Wav.decode(f.readBytes()); f.delete()
                if (a == null) { Diag.canaryLast = "unavailable (voice format)"; return@execute }
                val x = Pcm.resample(a.samples, a.sampleRate, SAMPLE_RATE)
                var peak = 1e-6f
                for (v in x) peak = maxOf(peak, kotlin.math.abs(v))
                val g = 0.3f / peak // normal level, well below clipping
                det.setCanary(FloatArray(x.size) { x[it] * g })
                Diag.canaryLast = "ready"
                lastCanaryAt = SystemClock.elapsedRealtime() - 4 * 60_000L // first check about 1 minute after start
            } catch (e: Throwable) {
                Diag.canaryLast = "unavailable (${e.javaClass.simpleName})"
            }
        }
    }

    private fun onCanaryResult(ok: Boolean, ms: Double) {
        val t = Diag.time(System.currentTimeMillis())
        if (ok) {
            Diag.canaryOk++; canaryFailStreak = 0
            Diag.canaryLast = "passed at $t (${"%.0f".format(ms)} ms)"
        } else {
            Diag.canaryFailed++; canaryFailStreak++
            Diag.canaryLast = "FAILED at $t"
            Diag.log("health", "Detector check failed ($canaryFailStreak in a row): the live detector did not find a known “Hey Sada”")
            if (canaryFailStreak == 2) {
                detector?.requestRebuild("two failed checks in a row")
                main.postDelayed({ detector?.requestCanary() }, 10_000) // verify the rebuild
            }
        }
    }

    private fun readThreads(): Int = try {
        File("/proc/self/status").readLines().firstOrNull { it.startsWith("Threads:") }?.substringAfter(':')?.trim()?.toInt() ?: 0
    } catch (_: Exception) { 0 }

    // ---------------- volume keys: double press of Volume Up ----------------

    private val gesture = VolumeGesture()
    private var volumeProvider: VolumeProvider? = null
    private var volLevel = VOL_MAX
    private var wakeWord = false
    private val pollGesture = object : Runnable {
        override fun run() {
            val d = gesture.poll(SystemClock.uptimeMillis())
            if (d == null) { if (gesture.busy()) main.postDelayed(this, 20); return }
            if (d.isDouble) {
                val before = machine.phase
                val steps = machine.onVolumeDouble()
                Diag.log("volume-gesture", "Double press via $gestureSource in $before → " +
                    if (steps.isEmpty()) "no action (not running)" else steps.joinToString(", "))
                runSteps(steps, "button:volume double-press ($gestureSource)")
                if (steps.contains(StepKind.RESUME)) {
                    main.postDelayed({
                        Diag.log("volume-gesture", "After double-press resume: podcast " +
                            if (player?.isPlaying == true) "PLAYING" else "NOT playing (${machine.phase})")
                    }, 500)
                }
            } else {
                Diag.log("volume-gesture", "Not a double press (${d.volumeSteps} press(es)) via $gestureSource: podcast volume +${d.volumeSteps}")
                setPodcastVolume(volLevel + d.volumeSteps)
            }
        }
    }

    /** direction: AudioManager.ADJUST_RAISE / ADJUST_LOWER. source: how the key reached Sada (for the report). */
    private fun onVolumeKey(direction: Int, source: String) {
        Diag.countVolumeKey(source)
        when (direction) {
            AudioManager.ADJUST_RAISE -> {
                gesture.press(SystemClock.uptimeMillis())
                gestureSource = source
                Diag.log("volume-key", "Volume Up press ${gesture.pressesInBurst()} via $source (state ${machine.phase}, session ${reportedState ?: "-"})")
                main.removeCallbacks(pollGesture)
                main.postDelayed(pollGesture, gesture.nextPollDelay() + 5)
            }
            AudioManager.ADJUST_LOWER -> {
                Diag.log("volume-key", "Volume Down via $source (state ${machine.phase}): podcast volume")
                setPodcastVolume(volLevel - 1)
            }
            else -> Diag.log("volume-key", "Volume adjustment $direction via $source ignored")
        }
    }

    private var gestureSource = "-"

    private fun setPodcastVolume(level: Int) {
        volLevel = level.coerceIn(0, VOL_MAX)
        player?.setVolume(volLevel.toFloat() / VOL_MAX)
        volumeProvider?.currentVolume = volLevel
    }

    private var podcastPlaying = false
    private var reportedState: SessionState.Reported? = null

    private fun setPlaying(playing: Boolean) {
        podcastPlaying = playing
        refreshSessionState()
    }

    /**
     * Reports the session state for the current phase (see SessionState): while Sada waits for you it reports an
     * active "waiting" state, because Android only sends locked-screen volume keys to an active session.
     */
    private fun refreshSessionState() {
        val r = SessionState.forPhase(machine.phase, podcastPlaying)
        val (code, speed) = when (r) {
            SessionState.Reported.PLAYING -> PlaybackState.STATE_PLAYING to 1f
            SessionState.Reported.WAITING -> PlaybackState.STATE_BUFFERING to 0f
            SessionState.Reported.PAUSED -> PlaybackState.STATE_PAUSED to 0f
        }
        val state = PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
            .setState(code, player?.positionMs() ?: 0L, speed)
            .build()
        session?.setPlaybackState(state)
        if (r != reportedState) {
            reportedState = r
            Diag.sessionState = "$r (${machine.phase})"
            Diag.log("session", "Media session reports $r in ${machine.phase}: locked-screen volume keys " +
                if (SessionState.receivesVolumeKeys(r)) "reach Sada" else "go to Android (not Sada)")
        }
    }

    // ---------------- audio focus, routing, recording health ----------------

    private fun requestFocus() {
        val req = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(outAttrs)
            .setOnAudioFocusChangeListener({ change ->
                Diag.log("focus", "Audio focus change $change (another app or a call took the audio)")
                if ((change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) && machine.phase == Phase.LISTENING) {
                    player?.pause(); setPlaying(false)
                }
            }, main)
            .build()
        focus = req
        audio.requestAudioFocus(req)
    }

    private fun watchRecording() {
        if (Build.VERSION.SDK_INT < 29) return
        val cb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                val silenced = configs.any { it.isClientSilenced }
                if (silenced != Diag.micSilenced) {
                    Diag.micSilenced = silenced
                    Diag.log(if (silenced) "error" else "route", if (silenced) "Android silenced Sada's microphone (another app took it)" else "Microphone available again")
                }
                input?.updateRoute()
            }
        }
        audio.registerAudioRecordingCallback(cb, main)
        recordingCallback = cb
    }

    @Suppress("DEPRECATION")
    private fun refreshRoutes(log: Boolean) {
        input?.updateRoute()
        Diag.playbackRoute = if (player == null) "none (listen-only)" else AudioNames.describe(player?.track?.routedDevice)
        Diag.audioMode = when (audio.mode) {
            AudioManager.MODE_NORMAL -> "normal (media)"
            AudioManager.MODE_IN_COMMUNICATION -> "IN COMMUNICATION (call mode)"
            AudioManager.MODE_IN_CALL -> "IN CALL"
            else -> "mode ${audio.mode}"
        }
        Diag.scoOn = audio.isBluetoothScoOn
        if (log) Diag.log("route", "Mic: ${Diag.micRoute} | Playback: ${Diag.playbackRoute} | Mode: ${Diag.audioMode} | SCO: ${Diag.scoOn}")
    }

    private val heartbeat = object : Runnable {
        var ticks = 0
        override fun run() {
            if (!Diag.serviceRunning) return
            ticks++
            val now = System.currentTimeMillis()
            Diag.lastHeartbeat = now
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wasOn = Diag.screenOn
            Diag.screenOn = pm.isInteractive
            if (wasOn != Diag.screenOn) Diag.log("screen", if (Diag.screenOn) "Screen on" else "Screen off / locked")
            Diag.batteryPct = batteryPct()
            Diag.ignoringBatteryOpt = pm.isIgnoringBatteryOptimizations(packageName)
            val um = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            Diag.standbyBucket = when (um.appStandbyBucket) {
                UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "active"
                UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "working set"
                UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "frequent"
                UsageStatsManager.STANDBY_BUCKET_RARE -> "rare"
                UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "RESTRICTED"
                else -> "${um.appStandbyBucket}"
            }
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            Diag.bgRestricted = am.isBackgroundRestricted
            if (machine.phase == Phase.LISTENING) Diag.earbudMicStatus = earbudMicState()
            // No call mode should be active while listening for wake words or Continue.
            if (machine.phase == Phase.LISTENING || machine.phase == Phase.WAIT_CONTINUE) {
                if (earbudListening) {
                    if (AudioRouteGuard.isClean(audio)) onEarbudRouteLost("call mode is no longer active (${AudioRouteGuard.describe(audio)})")
                } else if (!AudioRouteGuard.isClean(audio)) {
                    worker.execute {
                        if (AudioRouteGuard.repairIfNeeded(audio, "While listening")) input?.requestReopen("call mode was found active while listening")
                    }
                }
            }
            // Health: resources, and a periodic check that the live detector still finds a known "Hey Sada".
            Diag.nativeHeapMb = android.os.Debug.getNativeHeapAllocatedSize() / 1048576.0
            Diag.threads = readThreads()
            val nowEl = SystemClock.elapsedRealtime()
            val idle = machine.phase == Phase.LISTENING && nowEl - lastActivationAt > 30_000
            if (idle && wakeWord && detector?.hasCanary == true && nowEl - lastCanaryAt > 5 * 60_000L) {
                lastCanaryAt = nowEl
                detector?.requestCanary()
            }
            if (nowEl - lastHealthLogAt > 60_000) { lastHealthLogAt = nowEl; Diag.log("health", Diag.healthText()) }
            // Stall watchdog: preparing, asking or answering must never block listening for long.
            val busy = machine.phase == Phase.ARMING || machine.phase == Phase.ASKING || machine.phase == Phase.ANSWERING
            // Answering includes transcription + Muse Spark over the network (up to ~75 s with timeouts).
            val limit = if (machine.phase == Phase.ANSWERING) 90_000L else 30_000L
            if (busy && SystemClock.elapsedRealtime() - phaseSince > limit) {
                for (s in machine.recover()) execute(s, System.currentTimeMillis(), "", "")
                syncPhase()
            }
            val before = "${Diag.micRoute}|${Diag.playbackRoute}|${Diag.audioMode}|${Diag.scoOn}"
            refreshRoutes(log = false)
            val after = "${Diag.micRoute}|${Diag.playbackRoute}|${Diag.audioMode}|${Diag.scoOn}"
            if (before != after) Diag.log("route", "Changed → Mic: ${Diag.micRoute} | Playback: ${Diag.playbackRoute} | Mode: ${Diag.audioMode} | SCO: ${Diag.scoOn}")
            try { File(filesDir, "heartbeat.txt").writeText("running\t$now\t${Diag.startedAt}") } catch (_: Exception) {}
            if (ticks % 60 == 0) Diag.log("alive", "Still running; battery ${Diag.batteryPct}%; ${Diag.aecText()}")
            main.postDelayed(this, 5000)
        }
    }

    private fun batteryPct(): Int {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Sada listening test", NotificationManager.IMPORTANCE_LOW))
    }
}
