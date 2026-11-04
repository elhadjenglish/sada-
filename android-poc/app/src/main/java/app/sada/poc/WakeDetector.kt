package app.sada.poc

import android.content.Context
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.OnlineStream

/**
 * Offline "Hey Sada" / "Continue" detection on the two processed microphone streams from the front end.
 * A keyword counts if either stream catches it (the stream is recorded for diagnostics). A third, very sensitive
 * stream on the main signal only logs near misses. Audio is never stored.
 */
class WakeDetector(private val ctx: Context) {
    private var kws: KeywordSpotter? = null
    private var main: OnlineStream? = null
    private var alt: OnlineStream? = null
    private var probe: OnlineStream? = null
    private val chunk = 1536 // 96 ms
    private val bufMain = FloatArray(chunk)
    private val bufAlt = FloatArray(chunk)
    private var fill = 0
    private var lastHit = 0L
    private var lastProbe = 0L
    @Volatile var muted = false
    @Volatile private var flush = false
    private var chunks = 0L
    private var totalMs = 0.0
    private var maxMs = 0.0
    private var sensitivity = "default"
    private var lastRefreshChunk = 0L
    @Volatile private var rebuildRequested: String? = null
    @Volatile private var canaryAudio: FloatArray? = null
    @Volatile private var canaryRequested = false
    /** Result of the last health check: true = the live detector still finds a known "Hey Sada". */
    @Volatile var onCanary: ((Boolean, Double) -> Unit)? = null

    data class Hit(val label: String, val stream: String, val atMs: Long)

    /** sensitivity: "default" uses the thresholds in keywords.txt; others adjust them (experimental). */
    fun load(sensitivity: String): Boolean {
        this.sensitivity = sensitivity
        Kws.checkKeywords(ctx)
        return try {
            val k = Kws.liveSpotter(ctx)
            kws = k
            openStreams(k)
            Diag.modelStatus = "✓ model loaded ($MODEL_DIR)"
            Diag.log("model", Diag.modelStatus)
            true
        } catch (e: Throwable) {
            Diag.modelStatus = "✗ model failed to load: ${e.javaClass.simpleName}: ${e.message}"
            Diag.log("error", Diag.modelStatus)
            false
        }
    }

    private fun openStreams(k: KeywordSpotter) {
        // The keyword thresholds actually in force are decided here (also after a rebuild or refresh), so report them here.
        val custom = customKeywords(sensitivity)
        Diag.keywordsMode = if (custom == null) "built-in thresholds" else "sensitivity: $sensitivity (experimental)"
        main = Kws.openStream(k, custom)
        alt = Kws.openStream(k, custom)
        probe = try {
            Kws.openStream(k, KeywordFile.withThreshold(Kws.readAssetLines(ctx, "$MODEL_DIR/keywords.txt"), 0.08).joinToString("/"))
                .also { Diag.probeStatus = "on (threshold 0.08, logs only)" }
        } catch (e: Throwable) { Diag.probeStatus = "unavailable: ${e.javaClass.simpleName}"; null }
    }

    private fun closeStreams() {
        Kws.closeStream(main); Kws.closeStream(alt); Kws.closeStream(probe)
        main = null; alt = null; probe = null
    }

    /** Releases this session's streams (the live spotter itself is kept for the next session). */
    fun close() { closeStreams() }

    /** Health recovery: replace the live spotter and streams (done on the audio thread at the next block). */
    fun requestRebuild(reason: String) { rebuildRequested = reason }

    /** Sets the known "Hey Sada" recording used by the health check (16 kHz). */
    fun setCanary(audio: FloatArray) { canaryAudio = audio }
    val hasCanary: Boolean get() = canaryAudio != null

    /** Runs the health check at the next block (on the audio thread). */
    fun requestCanary() { if (canaryAudio != null) canaryRequested = true }

    /** Drops buffered audio and detector state (after Sada has spoken, or after a question). */
    fun flushNow() { flush = true }

    /**
     * Feeds one 256-sample block of each stream. Returns keywords detected (empty most of the time).
     * mainName/altName describe what the streams are right now (they differ with and without echo cancellation).
     */
    fun process(mainBlock: FloatArray, altBlock: FloatArray, mainName: String, altName: String): List<Hit> {
        rebuildRequested?.let { why ->
            rebuildRequested = null
            try {
                closeStreams()
                val nk = Kws.rebuildLive(ctx)
                kws = nk
                openStreams(nk)
                fill = 0
                Diag.detectorRebuilds++
                Diag.log("health", "Detector rebuilt: $why")
            } catch (e: Throwable) {
                // Never keep a reference to a released detector: no detector at all is a clean state that the health
                // check (SadaService) retries at most once a minute.
                kws = null; main = null; alt = null; probe = null
                Diag.log("error", "Detector rebuild failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        val k = kws ?: return emptyList()
        if (canaryRequested) {
            canaryRequested = false
            val audio = canaryAudio
            if (audio != null) {
                val t0 = System.nanoTime()
                val ok = try { Kws.feedRecording(k, audio).any { it.startsWith("HEY_SADA") } } catch (_: Throwable) { false }
                onCanary?.invoke(ok, (System.nanoTime() - t0) / 1e6)
            }
        }
        val m = main ?: return emptyList()
        val a = alt ?: return emptyList()
        System.arraycopy(mainBlock, 0, bufMain, fill, mainBlock.size)
        System.arraycopy(altBlock, 0, bufAlt, fill, altBlock.size)
        fill += mainBlock.size
        if (fill < chunk) return emptyList()
        fill = 0
        if (flush) { k.reset(m); k.reset(a); probe?.let { k.reset(it) }; flush = false }
        val t0 = System.nanoTime()
        val hitsMain = Kws.feed(k, m, bufMain.copyOf())
        val hitsAlt = Kws.feed(k, a, bufAlt.copyOf())
        val hitsProbe = probe?.let { p -> try { Kws.feed(k, p, bufMain.copyOf()) } catch (_: Throwable) { emptyList() } } ?: emptyList()
        val ms = (System.nanoTime() - t0) / 1e6
        chunks++; totalMs += ms; if (ms > maxMs) maxMs = ms
        Diag.noteChunkTime(ms, chunk * 1000.0 / SAMPLE_RATE)
        if (chunks % 10 == 0L) { Diag.kwsChunkMsAvg = totalMs / chunks * (1600.0 / chunk); Diag.kwsChunkMsMax = maxMs }
        Diag.samplesFed += chunk
        Diag.buffersFed++

        val now = System.currentTimeMillis()
        val out = ArrayList<Hit>()
        // the same keyword from both streams within 1.5 s is one detection
        for ((label, stream) in hitsMain.map { it to mainName } + hitsAlt.map { it to altName }) {
            val dup = out.any { it.label == label } || now - lastHit < 1500
            Diag.log("kws-raw", "Detector reported $label on $stream${if (muted) " (ignored: busy)" else if (dup) " (same detection)" else ""}")
            if (!dup) { out.add(Hit(label, stream, now)); lastHit = now }
        }
        if (hitsMain.isNotEmpty() || hitsAlt.isNotEmpty() || hitsProbe.isNotEmpty()) Diag.noteDetectorOutput()
        if (hitsMain.isNotEmpty() || hitsAlt.isNotEmpty()) Diag.countStreamHit(if (hitsMain.isNotEmpty() && hitsAlt.isNotEmpty()) "both" else if (hitsMain.isNotEmpty()) mainName else altName)
        for (label in hitsProbe) {
            if (now - lastHit > 1500 && now - lastProbe > 1500) Diag.log("near-miss", "Almost detected $label (below the real threshold)")
            lastProbe = now
        }
        // Every ~2 minutes, during a quiet moment (no speech, nothing heard for 5 s), start fresh streams so nothing a
        // stream accumulates internally can build up over a long session. Never mid-phrase.
        if (chunks - lastRefreshChunk > 1250 && now - lastHit > 5000 && now - lastProbe > 5000 &&
            (Diag.lastSecondKind == "SILENCE" || Diag.lastSecondKind == "NOISE")) {
            lastRefreshChunk = chunks
            try {
                closeStreams()
                openStreams(k)
                Diag.streamRefreshes++
            } catch (e: Throwable) {
                main = null; alt = null; probe = null // health check rebuilds; never crash the capture thread
                Diag.log("error", "Detector stream refresh failed: ${e.javaClass.simpleName}")
            }
        }
        if (muted && out.isNotEmpty()) Diag.keywordsRejectedByState += out.size // heard, but Sada was busy
        return if (muted) emptyList() else out
    }

    private fun customKeywords(sensitivity: String): String? {
        if (sensitivity == "default") return null
        val delta = if (sensitivity == "more") -0.08 else 0.08
        val lines = Kws.readAssetLines(ctx, "$MODEL_DIR/keywords.txt").filter { it.isNotBlank() }
        return lines.joinToString("/") { line ->
            val m = Regex("#([0-9.]+)").find(line)
            if (m != null) {
                val v = (m.groupValues[1].toDouble() + delta).coerceIn(0.05, 0.9)
                line.replace(m.value, "#" + String.format(java.util.Locale.US, "%.2f", v))
            } else line
        }
    }
}
