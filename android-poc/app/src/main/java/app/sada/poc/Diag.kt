package app.sada.poc

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One diagnostics event. value carries a number where useful (e.g. a delay in ms). */
data class DiagEvent(val t: Long, val kind: String, val value: Long, val text: String)

/**
 * Diagnostics shared by the service and the screen. Events are also written to a file, so results
 * survive if Android kills the app during a locked-screen test. Nothing here leaves the phone.
 */
object Diag {
    private val lock = Any()
    private val events = ArrayList<DiagEvent>()
    private val falseMarks = HashSet<Long>()
    private var eventsFile: File? = null
    private var marksFile: File? = null
    private var ready = false

    // Live values written by the service, read by the screen.
    @Volatile var phase: String = "STOPPED"
    @Volatile var serviceRunning = false
    @Volatile var startedAt = 0L
    @Volatile var micRoute = "-"
    @Volatile var playbackRoute = "-"
    @Volatile var audioMode = "-"
    @Volatile var scoOn = false
    @Volatile var micLevelDb = -99.0
    @Volatile var micLevelAvgDb = -99.0
    @Volatile var micSilenced = false
    @Volatile var kwsChunkMsAvg = 0.0
    @Volatile var kwsChunkMsMax = 0.0
    @Volatile var screenOn = true
    @Volatile var batteryPct = -1
    @Volatile var batteryAtStart = -1
    @Volatile var ignoringBatteryOpt = false
    @Volatile var standbyBucket = "-"
    @Volatile var bgRestricted = false
    @Volatile var lastHeartbeat = 0L
    @Volatile var source = "-"
    @Volatile var keywordsMode = "built-in thresholds"
    @Volatile var testMode = "walking"
    @Volatile var playback = true

    // Audio-to-keyword pipeline (what actually reaches the detector)
    @Volatile var micSource = "-"
    @Volatile var micFormat = "-"
    @Volatile var samplesFed = 0L
    @Volatile var buffersFed = 0L
    @Volatile var emptyReads = 0L
    @Volatile var lastReadCode = 0
    @Volatile var zeroBuffers = 0L
    @Volatile var clippedSamples = 0L
    @Volatile var micPeak = 0.0
    @Volatile var modelStatus = "not loaded yet"
    @Volatile var keywordCheck = "not checked yet"
    @Volatile var probeStatus = "-"
    @Volatile var selfTest = "not run yet"

    // Echo cancellation (podcast on the phone speaker) and gain control
    @Volatile var aecWanted = true
    @Volatile var aecOn = false
    @Volatile var aecErleDb = 0.0
    @Volatile var aecDelayMs = 0.0
    @Volatile var aecDisturbed = 0.0
    @Volatile var aecCopies = 0
    @Volatile var aecRealigns = 0L
    @Volatile var aecMissingRef = 0L
    @Volatile var aecHarmBlocks = 0L
    @Volatile var alignMethod = "-"
    @Volatile var alignOffsetMs = 0.0
    @Volatile var alignResyncs = 0
    @Volatile var agcGainDb = 0.0
    @Volatile var noiseFloorDb = 0.0

    // Questions
    @Volatile var earbudMicWanted = true
    @Volatile var earbudMicStatus = "-"
    @Volatile var lastQuestion = "none yet"

    // Audio evidence per stage (levels are 1-second averages, dBFS)
    @Volatile var inputGainDb = 0.0
    @Volatile var rawLevelDb = -120.0
    @Volatile var mainLevelDb = -120.0
    @Volatile var altLevelDb = -120.0
    @Volatile var micRecoveries = 0
    @Volatile var routeRepairs = 0
    @Volatile var lastSecondKind = "-"
    @Volatile var lastSpeechLevelDb = -120.0
    @Volatile var recordingCheck = "not run"

    // Earbud listening experiment (call mode for the whole session)
    @Volatile var listenMic = "phone (Build #8 behaviour)"
    @Volatile var micRequested = "-"
    @Volatile var playbackRequested = "-"
    @Volatile var earbudRoute = "not used"
    @Volatile var scoDrops = 0
    @Volatile var scoReconnects = 0
    @Volatile var bandwidth = "-"
    @Volatile var platformEffects = "-"
    @Volatile var usageMode = "-"
    @Volatile var stateText = "Not started"
    /** Identifies the build in every report: APK version (GitHub Actions run) and source revision. */
    const val SOURCE_VERSION = "2026-11-04 final delivery"
    @Volatile var appVersion = "?"
    @Volatile var lastTranscript = ""
    @Volatile var lastAnswer = ""
    @Volatile var lastTiming = "-"
    // wake-word pipeline health (SadaService checks every 10 s while listening)
    @Volatile var micBlocksTotal = 0L
    @Volatile var keywordsRejectedByState = 0
    @Volatile var wakeStatus = "not checked yet"
    @Volatile var sessionState = "-"
    @Volatile var wakeWordOn = false
    private val volumeKeys = java.util.concurrent.ConcurrentHashMap<String, Int>()
    fun countVolumeKey(source: String) { volumeKeys.merge(source, 1) { a, b -> a + b } }
    /** How volume-key presses reached Sada: "app on screen" (Activity) or "media session" (background / locked). */
    fun volumeKeysText(): String = if (volumeKeys.isEmpty()) "none received yet" else volumeKeys.entries.joinToString(", ") { "${it.key}: ${it.value}" }
    @Volatile var aecKind = "-"

    fun earbudText(): String =
        "Volume keys received: ${volumeKeysText()} | media session reports: $sessionState | voice wake word: ${if (wakeWordOn) "on (experimental)" else "off"}\n" +
            "Usage mode: $usageMode | echo cancellation: $aecKind\n" +
            "Listening microphone: $listenMic\n" +
            "  mic requested: $micRequested | actual: $micRoute\n" +
            "  podcast output requested: $playbackRequested | actual: $playbackRoute\n" +
            "  call route: $earbudRoute | link lost: $scoDrops, restored: $scoReconnects\n" +
            "  microphone link quality: $bandwidth\n" +
            "  platform effects: $platformEffects"

    // Operational health (long sessions)
    @Volatile var detectorRebuilds = 0
    @Volatile var streamRefreshes = 0
    @Volatile var rtfAvg = 0.0          // processing time / audio time, smoothed
    @Volatile var rtfMax = 0.0
    @Volatile var captureLagMs = 0.0
    @Volatile var captureOverruns = 0
    @Volatile var nativeHeapMb = 0.0
    @Volatile var threads = 0
    @Volatile var canaryLast = "not run yet"
    @Volatile var canaryOk = 0
    @Volatile var canaryFailed = 0

    fun noteChunkTime(ms: Double, audioMs: Double) {
        val r = ms / audioMs
        rtfAvg = if (rtfAvg == 0.0) r else 0.98 * rtfAvg + 0.02 * r
        if (r > rtfMax) rtfMax = r
    }

    fun healthText(): String =
        "detector check: $canaryLast (ok $canaryOk, failed $canaryFailed); rebuilds $detectorRebuilds; stream refreshes $streamRefreshes; " +
            "processing ${"%.0f".format(rtfAvg * 100)}% of real time (max ${"%.0f".format(rtfMax * 100)}%); capture lag ${"%.0f".format(captureLagMs)} ms, overruns $captureOverruns; " +
            "native memory ${"%.0f".format(nativeHeapMb)} MB; threads $threads; spotters created ${Kws.spottersCreated}, released ${Kws.spottersReleased}; open streams ${Kws.streamsOpen}" +
            if (Kws.releaseSupported) "" else " (this sherpa-onnx version has no release(): bounded by reuse only)"
    private val secondKinds = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val detectorOutputs = java.util.concurrent.ConcurrentLinkedDeque<Long>()
    private val pendingSpeech = java.util.concurrent.ConcurrentLinkedDeque<Pair<Long, String>>()
    @Volatile var speechSecondsNoKeyword = 0
    @Volatile var quietSpeechSecondsNoKeyword = 0
    private var lvN = 0
    private var lvRaw = 0.0
    private var lvMain = 0.0
    private var lvAlt = 0.0

    /** Called for every 16 ms block by the capture thread. */
    fun noteLevels(raw: Double, main: Double, alt: Double) {
        lvRaw += raw; lvMain += main; lvAlt += alt
        if (++lvN >= 62) { rawLevelDb = lvRaw / lvN; mainLevelDb = lvMain / lvN; altLevelDb = lvAlt / lvN; lvN = 0; lvRaw = 0.0; lvMain = 0.0; lvAlt = 0.0 }
    }

    /** Any detector output (keyword or near miss), from the detector thread. */
    fun noteDetectorOutput() {
        detectorOutputs.addLast(System.currentTimeMillis())
        while (detectorOutputs.size > 100) detectorOutputs.pollFirst()
    }

    /** One second of raw microphone audio has been classified. */
    fun noteSecond(kind: String, levelDb: Double, speechDb: Double) {
        if (phase != "LISTENING" && phase != "WAIT_CONTINUE") return // questions and answers are not wake-word listening
        secondKinds.merge(kind, 1) { a, b -> a + b }
        lastSecondKind = kind
        if (kind == "USABLE_SPEECH" || kind == "QUIET_SPEECH") { lastSpeechLevelDb = speechDb; pendingSpeech.addLast(System.currentTimeMillis() to kind) }
        val now = System.currentTimeMillis()
        // A speech second is "without keyword" if the detector produced nothing (not even a near miss) within 2.5 s.
        while (true) {
            val first = pendingSpeech.peekFirst() ?: break
            if (now - first.first < 3000) break
            pendingSpeech.pollFirst()
            val hit = detectorOutputs.any { kotlin.math.abs(it - first.first) < 2500 }
            if (!hit) { speechSecondsNoKeyword++; if (first.second == "QUIET_SPEECH") quietSpeechSecondsNoKeyword++ }
        }
    }

    fun secondsText(): String {
        val order = listOf("SILENCE", "NOISE", "QUIET_SPEECH", "CLIPPED", "USABLE_SPEECH")
        val names = mapOf("SILENCE" to "silence", "NOISE" to "noise only", "QUIET_SPEECH" to "quiet speech", "CLIPPED" to "clipped", "USABLE_SPEECH" to "usable speech")
        return order.joinToString(", ") { "${names[it]} ${secondKinds[it] ?: 0}" }
    }
    private val streamHits = java.util.concurrent.ConcurrentHashMap<String, Int>()
    fun countStreamHit(name: String) { streamHits.merge(name, 1) { a, b -> a + b } }
    fun streamHitsText(): String = if (streamHits.isEmpty()) "-" else streamHits.entries.joinToString(", ") { "${it.key}: ${it.value}" }

    fun resetInputCounters() {
        samplesFed = 0; buffersFed = 0; emptyReads = 0; lastReadCode = 0; zeroBuffers = 0; clippedSamples = 0; micPeak = 0.0
        streamHits.clear()
        volumeKeys.clear()
        secondKinds.clear(); detectorOutputs.clear(); pendingSpeech.clear()
        speechSecondsNoKeyword = 0; quietSpeechSecondsNoKeyword = 0; micRecoveries = 0; routeRepairs = 0
        rtfAvg = 0.0; rtfMax = 0.0; captureOverruns = 0; captureLagMs = 0.0; streamRefreshes = 0
    }

    fun aecText(): String = when {
        !aecWanted -> "off (switched off in the app)"
        !aecOn -> "standing by (only used when the podcast plays through the phone speaker)"
        else -> "ON: echo reduced by ${"%.0f".format(aecErleDb)} dB; echo delay ${"%.0f".format(aecDelayMs)} ms; " +
            "user-talking time ${"%.0f".format(aecDisturbed * 100)}%; filter updates $aecCopies; realignments $aecRealigns; " +
            "blocks without podcast reference $aecMissingRef; blocks where the plain mic was used instead (no-harm rule) $aecHarmBlocks"
    }

    fun alignText(): String =
        "$alignMethod; podcast offset ${"%.0f".format(alignOffsetMs)} ms; re-syncs $alignResyncs"


    fun init(ctx: Context) {
        appVersion = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?" } catch (_: Exception) { "?" }
        synchronized(lock) {
            if (ready) return
            ready = true
            eventsFile = File(ctx.filesDir, "events.tsv")
            // Keep the log file bounded: rewrite it with the most recent lines when it grows past 1 MB.
            eventsFile?.let { f ->
                if (f.exists() && f.length() > 1_000_000) {
                    try { val keep = f.readLines().takeLast(3000); f.writeText(keep.joinToString("\n", postfix = "\n")) } catch (_: Exception) {}
                }
            }
            marksFile = File(ctx.filesDir, "false-marks.txt")
            eventsFile?.let { f ->
                if (f.exists()) f.forEachLine { line ->
                    val p = line.split('\t', limit = 4)
                    if (p.size == 4) {
                        val t = p[0].toLongOrNull()
                        val v = p[2].toLongOrNull()
                        if (t != null && v != null) events.add(DiagEvent(t, p[1], v, p[3]))
                    }
                }
            }
            marksFile?.let { f -> if (f.exists()) f.forEachLine { l -> l.trim().toLongOrNull()?.let { falseMarks.add(it) } } }
        }
    }

    /**
     * If the last test's heartbeat says "running" but no service is running in this process,
     * Android stopped it. Logs that once (value = time of the last heartbeat).
     */
    fun checkLostSession(ctx: Context) {
        if (serviceRunning) return
        val f = File(ctx.filesDir, "heartbeat.txt")
        if (!f.exists()) return
        val p = try { f.readText().split('\t') } catch (_: Exception) { return }
        if (p.size >= 3 && p[0] == "running") {
            val last = p[1].toLongOrNull() ?: return
            val began = p[2].toLongOrNull() ?: last
            log("session-lost", "The previous test was stopped by Android at about ${time(last)} after ${(last - began) / 60000} min (not by you)", last)
            try { f.writeText("lost\t$last\t$began") } catch (_: Exception) {}
        }
    }

    fun log(kind: String, text: String, value: Long = 0L): DiagEvent {
        val e = DiagEvent(System.currentTimeMillis(), kind, value, text.replace('\t', ' ').replace('\n', ' '))
        synchronized(lock) {
            events.add(e)
            if (events.size > 3000) events.removeAt(0)
            try { eventsFile?.appendText("${e.t}\t${e.kind}\t${e.value}\t${e.text}\n") } catch (_: Exception) {}
        }
        return e
    }

    fun snapshot(): List<DiagEvent> = synchronized(lock) { ArrayList(events) }

    fun isFalse(t: Long): Boolean = synchronized(lock) { falseMarks.contains(t) }

    fun setFalse(t: Long, isFalse: Boolean) {
        synchronized(lock) {
            if (isFalse) falseMarks.add(t) else falseMarks.remove(t)
            try { marksFile?.writeText(falseMarks.joinToString("\n")) } catch (_: Exception) {}
        }
    }

    fun reset() {
        synchronized(lock) {
            events.clear()
            falseMarks.clear()
            try { eventsFile?.delete(); marksFile?.delete() } catch (_: Exception) {}
        }
        log("reset", "Results cleared")
    }

    // ---- statistics ----

    data class Stats(
        val wakes: Int,
        val wakesMarkedFalse: Int,
        val continues: Int,
        val continueWhilePlaying: Int,
        val missedWake: Int,
        val missedContinue: Int,
        val pauseMs: List<Long>,
        val answerMs: List<Long>,
        val resumeMs: List<Long>,
        val driftMs: List<Long>,
        val unexpectedStops: Int,
        val listeningMinutes: Double,
        val rawDetections: Int,
        val earbudActivations: Int,
        val questions: Int,
        val questionsWithSpeech: Int,
        val nearMisses: Int,
        val ignored: Int,
    )

    fun stats(): Stats {
        val ev = snapshot()
        val wakes = ev.filter { it.kind == "wake" }
        var listening = 0L
        var openAt = -1L
        for (e in ev) {
            when (e.kind) {
                "session-start" -> openAt = e.t
                "session-stop", "session-lost" -> { if (openAt > 0) listening += (if (e.kind == "session-lost") e.value else e.t) - openAt; openAt = -1 }
            }
        }
        if (openAt > 0 && serviceRunning) listening += System.currentTimeMillis() - openAt
        return Stats(
            wakes = wakes.size,
            wakesMarkedFalse = wakes.count { isFalse(it.t) },
            continues = ev.count { it.kind == "continue" },
            continueWhilePlaying = ev.count { it.kind == "continue-while-playing" },
            missedWake = ev.count { it.kind == "miss-wake" },
            missedContinue = ev.count { it.kind == "miss-continue" },
            pauseMs = wakes.map { it.value },
            answerMs = ev.filter { it.kind == "answer-start" }.map { it.value },
            resumeMs = ev.filter { it.kind == "continue" }.map { it.value },
            driftMs = ev.filter { it.kind == "resume-drift" }.map { it.value },
            unexpectedStops = ev.count { it.kind == "session-lost" },
            listeningMinutes = listening / 60000.0,
            rawDetections = ev.count { it.kind == "kws-raw" },
            earbudActivations = ev.count { it.kind == "activate" && it.text.contains("earbud") },
            questions = ev.count { it.kind == "question" },
            questionsWithSpeech = ev.count { it.kind == "question" && it.value > 0 },
            nearMisses = ev.count { it.kind == "near-miss" },
            ignored = ev.count { it.kind == "ignored" },
        )
    }

    fun summarize(xs: List<Long>): String {
        if (xs.isEmpty()) return "-"
        val s = xs.sorted()
        return "median ${s[s.size / 2]} ms, max ${s.last()} ms (n=${s.size})"
    }

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    fun time(t: Long): String = timeFmt.format(Date(t))

    fun report(attempts: Int?): String {
        val st = stats()
        val sb = StringBuilder()
        sb.append("Sada app ${appVersion} (source ${SOURCE_VERSION})\n")
        sb.append("Last response timing: $lastTiming\n")
        sb.append("Wake pipeline: $wakeStatus | keywords heard while Sada was busy: $keywordsRejectedByState\n")
        sb.append("Sada Stage 0 report — ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}\n")
        sb.append("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        sb.append("Test mode: $testMode | playback: ${if (playback) "on" else "OFF (listen only)"} | keywords: $keywordsMode | source: $source\n\n")
        sb.append("Model: $modelStatus\nKeywords: $keywordCheck\nNear-miss listener: $probeStatus\n")
        sb.append("Mic input: $micSource; $micFormat\n")
        sb.append("Reached detector: $samplesFed samples in $buffersFed buffers (${"%.1f".format(samplesFed / 16000.0)} s) | empty reads: $emptyReads (last code $lastReadCode) | all-zero buffers: $zeroBuffers | clipped samples: $clippedSamples | peak ${"%.2f".format(micPeak)}\n")
        sb.append("Raw detector outputs: ${st.rawDetections} | near misses: ${st.nearMisses} | ignored before start/while speaking: ${st.ignored}\n")
        sb.append("Self-test: $selfTest\n")
        sb.append("Levels (1 s avg): raw mic ${"%.0f".format(rawLevelDb)} dBFS → calibration +${"%.0f".format(inputGainDb)} dB → detector streams ${"%.0f".format(altLevelDb)} / ${"%.0f".format(mainLevelDb)} dBFS\n")
        sb.append("Seconds of audio: ${secondsText()} | last speech level ${"%.0f".format(lastSpeechLevelDb)} dBFS (raw)\n")
        sb.append("Speech while listening that produced no detector output: $speechSecondsNoKeyword s (quiet: $quietSpeechSecondsNoKeyword s). Not a failure count: it includes ordinary talking; compare with your Hey Sada attempts.\n")
        sb.append("Mic recoveries: $micRecoveries | call-mode repairs: $routeRepairs\n")
        sb.append("Health: ${healthText()}\n")
        sb.append(earbudText()).append("\n")
        sb.append("Activations: by voice ${st.wakes}; by earbud tap ${st.earbudActivations} (a tap after an unanswered “Hey Sada” = a missed voice attempt)\n")
        sb.append("Microphone check: $recordingCheck\n")
        sb.append("Echo cancellation: ${aecText()}\nAlignment: ${alignText()}\n")
        sb.append("Gain control: ${"%.0f".format(agcGainDb)} dB (background ${"%.0f".format(noiseFloorDb)} dBFS) | detections by stream: ${streamHitsText()}\n")
        sb.append("Earbud mic for questions: ${if (earbudMicWanted) "on" else "off"} ($earbudMicStatus)\n")
        sb.append("Questions: ${st.questions} (with speech ${st.questionsWithSpeech}) | last: $lastQuestion\n\n")
        sb.append("Listening time: ${"%.1f".format(st.listeningMinutes)} min | unexpected stops: ${st.unexpectedStops}\n")
        sb.append("Wake detections: ${st.wakes} (marked false: ${st.wakesMarkedFalse})\n")
        if (attempts != null && attempts > 0) {
            val hits = (st.wakes - st.wakesMarkedFalse).coerceAtLeast(0)
            sb.append("Hey Sada attempts (you entered): $attempts → detected ${hits} (${100 * hits / attempts}%), missed ${(attempts - hits).coerceAtLeast(0)}\n")
        }
        sb.append("Missed wake (earbud taps): ${st.missedWake} | missed Continue (earbud taps): ${st.missedContinue}\n")
        sb.append("Continue detections: ${st.continues} | 'Continue' heard while playing (false): ${st.continueWhilePlaying}\n")
        val perHour = if (st.listeningMinutes > 0) (st.wakesMarkedFalse + st.continueWhilePlaying) * 60.0 / st.listeningMinutes else 0.0
        sb.append("False activations per hour: ${"%.1f".format(perHour)}\n\n")
        sb.append("Wake → paused: ${summarize(st.pauseMs)}\n")
        sb.append("Wake → response starts: ${summarize(st.answerMs)}\n")
        sb.append("Continue → playing: ${summarize(st.resumeMs)}\n")
        sb.append("Resume position error: ${summarize(st.driftMs.map { kotlin.math.abs(it) })}\n\n")
        sb.append("Microphone: $micRoute\nPlayback: $playbackRoute\nAudio mode: $audioMode | Bluetooth call audio (SCO): ${if (scoOn) "ON" else "off"}\n")
        sb.append("Mic level now/avg: ${"%.0f".format(micLevelDb)} / ${"%.0f".format(micLevelAvgDb)} dBFS | silenced by system: $micSilenced\n")
        sb.append("Keyword engine per 100 ms of audio: avg ${"%.1f".format(kwsChunkMsAvg)} ms, max ${"%.0f".format(kwsChunkMsMax)} ms\n")
        sb.append("Battery: start $batteryAtStart% now $batteryPct% | ignoring battery optimization: $ignoringBatteryOpt | standby bucket: $standbyBucket | background restricted: $bgRestricted\n\n")
        sb.append("Last events:\n")
        snapshot().takeLast(60).forEach { e ->
            sb.append("${time(e.t)} ${e.kind}${if (e.value != 0L) " (${e.value})" else ""} ${e.text}${if (isFalse(e.t)) " [marked false]" else ""}\n")
        }
        return sb.toString()
    }
}
