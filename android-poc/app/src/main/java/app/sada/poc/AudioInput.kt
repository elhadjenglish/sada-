package app.sada.poc

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.SystemClock
import app.sada.poc.dsp.BandwidthMeter
import app.sada.poc.dsp.ClockAlign
import app.sada.poc.dsp.FrontEnd
import app.sada.poc.dsp.SignalClassifier
import kotlin.math.max

/**
 * Phone-microphone capture for wake words (and questions without earbuds): 16 kHz mono blocks of 16 ms,
 * aligned with the podcast and processed by [FrontEnd]. Echo cancellation is on only while the podcast plays
 * through the phone speaker. Audio is never stored.
 */
class AudioInput(
    private val ctx: Context,
    private val frontEnd: FrontEnd,
    private val player: () -> PodcastPlayer?,
    private val onBlock: (main: FloatArray, alt: FloatArray, mainName: String, altName: String) -> Unit,
) {
    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile var aecAllowed = true          // user switch for A/B testing
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private val align = ClockAlign()
    private val classifier = SignalClassifier(FrontEnd.BLOCK, SAMPLE_RATE)
    @Volatile private var micMode = "recognition"
    @Volatile private var reopenRequested: String? = null
    private var bufferFrames = SAMPLE_RATE
    private var lastBlockErrorAt = 0L
    /** Optional tap for the consented microphone check: raw block and the detector's calibrated stream. */
    @Volatile var tap: ((raw: FloatArray, detector: FloatArray, reference: FloatArray) -> Unit)? = null

    /** Blocks delivered since the microphone was last (re)opened: proof that audio is really arriving. */
    @Volatile var blocksSinceOpen = 0
        private set

    /** Waits (blocking, worker thread) until the microphone delivers audio again. False on timeout. */
    fun awaitLiveBlocks(timeoutMs: Long): Boolean {
        val t0 = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - t0 < timeoutMs) {
            if (!paused && record != null && blocksSinceOpen >= MicReadiness.NEED_LIVE) return true
            SystemClock.sleep(10)
        }
        return false
    }

    /** Asks the capture thread to close and reopen the phone microphone (logged with the reason). */
    fun requestReopen(reason: String) { reopenRequested = reason }

    /** Earbud listening experiment: record from the Bluetooth headset microphone (call mode) instead of the phone mic. */
    @Volatile var earbuds = false
        private set
    /** Called (throttled) in earbud mode when the recorder is not on the headset microphone. */
    @Volatile var onWrongRoute: ((String) -> Unit)? = null
    private val bandwidth = BandwidthMeter()
    private var lastWrongRouteAt = 0L

    /** A/B option: use Android's built-in echo canceller + noise suppressor instead of Sada's own canceller. */
    @Volatile var platformAec = false
        private set
    private var effects: List<android.media.audiofx.AudioEffect> = emptyList()

    fun start(mode: String, earbudMic: Boolean = false, androidAec: Boolean = false): Boolean {
        micMode = mode
        earbuds = earbudMic
        platformAec = androidAec && !earbudMic
        if (!open()) return false
        running = true
        thread = Thread({ loop() }, "sada-mic").also { it.start() }
        return true
    }

    private fun open(): Boolean {
        val am0 = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // Describe only what THIS microphone session requested (it used to keep showing an earlier session's effects).
        Diag.platformEffects = "none requested (Android built-in effects not used)"
        // Phone mode: no call mode may be active. Earbud mode: call mode is the point, so it is NOT repaired here.
        if (!earbuds) AudioRouteGuard.repairIfNeeded(am0, "Before opening the phone mic")
        // VOICE_RECOGNITION is calibrated low by Android rules (90 dB SPL ≈ -22 dBFS, no automatic gain): speech at
        // chest height or in a pocket arrives around -45 to -55 dBFS. A fixed gain restores normal speech levels.
        // Earbud microphone: measured on the device at -43 to -51 dBFS for speech (two sessions), while the detector only
        // found "Hey Sada" at -30 dBFS or louder in the level sweep. The same +20 dB calibration as the phone mic applies.
        // Android's call processing (VOICE_COMMUNICATION) usually includes its own gain control: smaller fixed gain.
        frontEnd.setInputGainDb(if (earbuds) 20.0 else if (platformAec) 10.0 else if (micMode == "plain") 10.0 else 20.0)
        Diag.inputGainDb = frontEnd.inputGainDb()
        val source = when {
            earbuds -> MediaRecorder.AudioSource.VOICE_COMMUNICATION // the source Android routes to the communication device
            platformAec -> MediaRecorder.AudioSource.VOICE_COMMUNICATION // the source Android's echo canceller attaches to
            micMode == "plain" -> MediaRecorder.AudioSource.MIC
            else -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufBytes = max(minBuf, SAMPLE_RATE * 2)
        bufferFrames = bufBytes / 2
        val rec = try {
            AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes)
        } catch (e: SecurityException) {
            Diag.log("error", "Microphone permission missing"); return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Diag.log("error", "Phone microphone could not be opened (another app may be using it)"); rec.release(); return false
        }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val wantType = if (earbuds) AudioDeviceInfo.TYPE_BLUETOOTH_SCO else AudioDeviceInfo.TYPE_BUILTIN_MIC
        val want = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == wantType }
        Diag.micRequested = if (want != null) AudioNames.describe(want) else if (earbuds) "Bluetooth headset mic (NOT listed by Android)" else "phone microphone"
        want?.let {
            val ok = rec.setPreferredDevice(it)
            Diag.log("route", "Asked Android for the ${AudioNames.describe(it)}: ${if (ok) "accepted" else "refused"}")
        }
        rec.startRecording()
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            Diag.log("error", "Phone microphone did not start (state ${rec.recordingState})"); rec.release(); return false
        }
        record = rec
        blocksSinceOpen = 0
        if (platformAec) {
            // Request Android's echo canceller and noise suppressor on this recording, and report what actually happened.
            val got = ArrayList<android.media.audiofx.AudioEffect>()
            val parts = ArrayList<String>()
            fun attach(name: String, available: Boolean, make: () -> android.media.audiofx.AudioEffect?) {
                if (!available) { parts.add("$name not available"); return }
                val e = try { make() } catch (_: Exception) { null }
                if (e == null) { parts.add("$name could not be created"); return }
                val rc = try { e.setEnabled(true) } catch (_: Exception) { -1 }
                parts.add("$name ${if (rc == android.media.audiofx.AudioEffect.SUCCESS && e.enabled) "ENABLED" else "not enabled (code $rc)"}")
                got.add(e)
            }
            attach("echo canceller", android.media.audiofx.AcousticEchoCanceler.isAvailable()) { android.media.audiofx.AcousticEchoCanceler.create(rec.audioSessionId) }
            attach("noise suppressor", android.media.audiofx.NoiseSuppressor.isAvailable()) { android.media.audiofx.NoiseSuppressor.create(rec.audioSessionId) }
            effects = got
            Diag.platformEffects = "Android built-in: " + parts.joinToString(", ")
            Diag.log("route", Diag.platformEffects)
        }
        if (earbuds) {
            // What Android offers for call audio on this phone (availability only; nothing is enabled or changed here).
            Diag.platformEffects = "echo canceller ${if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) "available" else "not available"}, " +
                "noise suppressor ${if (android.media.audiofx.NoiseSuppressor.isAvailable()) "available" else "not available"}, " +
                "gain control ${if (android.media.audiofx.AutomaticGainControl.isAvailable()) "available" else "not available"}"
            Diag.log("route", "Platform call-audio effects: ${Diag.platformEffects}")
        }
        Diag.micSource = when {
            earbuds -> "earbud microphone, call mode (VOICE_COMMUNICATION)"
            platformAec -> "phone microphone with Android's echo canceller (VOICE_COMMUNICATION)"
            micMode == "plain" -> "plain microphone (MIC)"
            else -> "speech recognition (VOICE_RECOGNITION)"
        }
        Diag.micFormat = "${rec.sampleRate} Hz, ${rec.channelCount} channel(s), " +
            (if (rec.audioFormat == AudioFormat.ENCODING_PCM_16BIT) "PCM 16-bit" else "encoding ${rec.audioFormat}") + ", buffer $bufBytes bytes"
        Diag.log("mic", "Phone mic: ${Diag.micSource}; ${Diag.micFormat}")
        Diag.micRoute = AudioNames.describe(rec.routedDevice)
        align.reset()
        return true
    }

    private fun close() {
        for (e in effects) try { e.release() } catch (_: Exception) {}
        effects = emptyList()
        try { record?.stop() } catch (_: Exception) {}
        record?.release()
        record = null
    }

    /** Releases the phone microphone (e.g. while the earbud microphone records a question). */
    fun pauseCapture() { paused = true }
    /** Re-opens the phone microphone after pauseCapture(). */
    fun resumeCapture() { paused = false }

    fun stop() {
        running = false
        try { thread?.join(1500) } catch (_: InterruptedException) {}
        thread = null
        close()
    }

    fun updateRoute() { record?.let { Diag.micRoute = AudioNames.describe(it.routedDevice) } }

    private fun loop() {
        val n = FrontEnd.BLOCK
        val pcm = ShortArray(n)
        val mic = FloatArray(n)
        val main = FloatArray(n)
        val alt = FloatArray(n)
        val recTs = AudioTimestamp()
        val playTs = AudioTimestamp()
        var micIndex = 0L
        var blocks = 0L
        var levelSum = 0.0
        var levelN = 0
        var offset = Double.NaN
        var deadBlocks = 0
        while (running) {
            reopenRequested?.let { why ->
                reopenRequested = null
                Diag.micRecoveries++
                Diag.log("mic-recover", "Reopening the phone mic: $why")
                close()
            }
            if (paused) {
                if (record != null) { close(); Diag.log("mic", "Phone mic released (earbud mic in use)") }
                SystemClock.sleep(20); continue
            }
            if (record == null) {
                if (!open()) { SystemClock.sleep(500); continue }
                micIndex = 0; offset = Double.NaN
                Diag.log("mic", "Phone mic back")
            }
            val rec = record ?: continue
            var got = 0
            while (got < n && running && !paused) {
                val r = rec.read(pcm, got, n - got)
                if (r <= 0) { Diag.emptyReads++; Diag.lastReadCode = r; SystemClock.sleep(5); continue }
                got += r
            }
            if (got < n) continue
            // Every ~1 s: is the recorder really on the phone microphone, and are we keeping up with it?
            if (blocks % 64 == 0L) {
                if (rec.getTimestamp(recTs, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                    // frames the microphone has captured that we have not read yet
                    val lag = recTs.framePosition - (micIndex + n)
                    Diag.captureLagMs = lag * 1000.0 / SAMPLE_RATE
                    if (lag > bufferFrames) {
                        Diag.captureOverruns++
                        Diag.log("health", "Audio fell behind by ${lag * 1000 / SAMPLE_RATE} ms: some microphone audio was dropped")
                        micIndex = recTs.framePosition - n // re-sync
                    }
                }
                val d = rec.routedDevice
                Diag.micRoute = AudioNames.describe(d)
                if (earbuds) {
                    // Requesting the headset mic does not guarantee it: report what Android actually routed.
                    if (d == null || d.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                        val now = System.currentTimeMillis()
                        if (now - lastWrongRouteAt > 5000) { lastWrongRouteAt = now; onWrongRoute?.invoke(AudioNames.describe(d)) }
                    }
                    Diag.bandwidth = bandwidth.verdict()
                } else if (d != null && d.type != AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                    reopenRequested = "it was routed to ${AudioNames.describe(d)} instead of the phone microphone"
                }
            }
            val st = Pcm.stats(pcm, n)
            // Digital near-silence for 3 s means a dead or wrongly routed input, not a quiet room.
            deadBlocks = if (st.rmsDb < -85) deadBlocks + 1 else 0
            if (deadBlocks == 190) reopenRequested = "3 s of digital silence (${"%.0f".format(st.rmsDb)} dBFS)"
            Diag.micLevelDb = st.rmsDb
            Diag.micPeak = max(Diag.micPeak * 0.995, st.peak)
            Diag.clippedSamples += st.clipped
            if (st.zeros == n) Diag.zeroBuffers++
            levelSum += st.rmsDb; levelN++
            if (levelN >= 60) { Diag.micLevelAvgDb = levelSum / levelN; levelSum = 0.0; levelN = 0 }
            for (i in 0 until n) mic[i] = pcm[i] / 32768f
            if (earbuds) bandwidth.process(mic)
            classifier.process(mic, n)?.let { kind ->
                Diag.noteSecond(kind.name, classifier.lastLevelDb(), classifier.lastSpeechDb())
            }

            val p = player()
            val speaker = p != null && p.isPlaying && isSpeaker(p)

            frontEnd.setPodcastActive(p != null && p.isPlaying)
            frontEnd.setEchoCancellation(aecAllowed && speaker)
            // Align with the podcast about 8 times a second.
            if (speaker && p != null && blocks % 8 == 0L) {
                val pl: PodcastPlayer = p
                val haveRec = rec.getTimestamp(recTs, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
                val havePlay = haveRec && pl.timestamp(playTs)
                val raw = if (havePlay) {
                    Diag.alignMethod = "playback + recording timestamps"
                    ClockAlign.rawOffset(recTs.framePosition, recTs.nanoTime, playTs.framePosition, playTs.nanoTime, pl.sampleRate)
                } else {
                    // Fallback: what has been handed to the speaker vs what has been read from the mic, right now.
                    Diag.alignMethod = "fallback (no timestamps)"
                    val head = (pl.track?.playbackHeadPosition?.toLong() ?: 0L) and 0xffffffffL
                    head.toDouble() * SAMPLE_RATE / max(1, pl.sampleRate) - micIndex
                }
                offset = align.update(raw)
                Diag.alignOffsetMs = offset / 16.0
                Diag.alignResyncs = align.resyncs()
            }
            frontEnd.process(mic, micIndex, offset, main, alt)
            blocksSinceOpen++
            tap?.invoke(mic, alt, frontEnd.lastReferenceTap())
            Diag.noteLevels(frontEnd.rawDb(), frontEnd.mainDb(), frontEnd.altDb())
            micIndex += n
            blocks++
            if (blocks % 30 == 0L) {
                val a = frontEnd.echoCanceller()
                Diag.aecOn = frontEnd.echoCancellationOn()
                Diag.aecErleDb = a.erleDb()
                Diag.aecDelayMs = a.peakTap() / 16.0 - FrontEnd.LEAD / 16.0
                Diag.aecDisturbed = a.doubleTalkFraction()
                Diag.aecCopies = a.copies()
                Diag.aecRealigns = frontEnd.realigns()
                Diag.aecMissingRef = frontEnd.missingReferenceBlocks()
                Diag.aecHarmBlocks = frontEnd.harmBlocks()
                Diag.agcGainDb = frontEnd.agc().gainDb()
                Diag.noiseFloorDb = frontEnd.agc().noiseFloorDb()
            }
            val aecOn = frontEnd.echoCancellationOn()
            Diag.micBlocksTotal++
            try {
                onBlock(main, alt, if (aecOn) "echo-cancelled+suppressed" else "adaptive gain", if (aecOn) "echo-cancelled" else "calibrated")
            } catch (e: Throwable) {
                // A detector problem must never stop the microphone thread; the health check recovers the detector.
                val now = System.currentTimeMillis()
                if (now - lastBlockErrorAt > 10_000) { lastBlockErrorAt = now; Diag.log("error", "Detector error on audio block: ${e.javaClass.simpleName}") }
            }
        }
    }

    private fun isSpeaker(p: PodcastPlayer): Boolean {
        val d = p.track?.routedDevice ?: return true // unknown: assume speaker (cancelling costs little)
        return d.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || d.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
    }
}
