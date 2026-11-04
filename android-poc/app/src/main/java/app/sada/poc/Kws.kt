package app.sada.poc

import android.content.Context
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.getFeatureConfig
import com.k2fsa.sherpa.onnx.getKwsModelConfig
import kotlin.math.min

const val MODEL_DIR = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
const val SAMPLE_RATE = 16000

/**
 * Keyword spotters from the bundled model, with explicit ownership.
 * Each spotter is an ONNX Runtime session with native memory and threads that the garbage collector does not see,
 * so they are created rarely and released explicitly: one live spotter per process (replaced only when its health
 * check fails) and one cached analysis spotter per keyword file for the self-test and microphone check.
 */
object Kws {
    private val lock = Any()
    private var live: KeywordSpotter? = null
    private val analysis = HashMap<String, KeywordSpotter>()
    @Volatile var spottersCreated = 0
    @Volatile var spottersReleased = 0
    @Volatile var streamsOpen = 0

    /** The live wake-word spotter (created once per process). */
    fun liveSpotter(ctx: Context): KeywordSpotter = synchronized(lock) {
        live ?: spotter(ctx, "$MODEL_DIR/keywords.txt").also { live = it }
    }

    /** Replaces the live spotter with a fresh one (health recovery). Only call from the thread that uses it. */
    fun rebuildLive(ctx: Context): KeywordSpotter = synchronized(lock) {
        live?.let { release(it); spottersReleased++ }
        live = null
        spotter(ctx, "$MODEL_DIR/keywords.txt").also { live = it }
    }

    /** Cached spotter for offline checks (self-test, microphone check); never the live one. */
    fun analysisSpotter(ctx: Context, keywordsAsset: String): KeywordSpotter = synchronized(lock) {
        analysis.getOrPut(keywordsAsset) { spotter(ctx, keywordsAsset) }
    }

    fun openStream(sp: KeywordSpotter, keywords: String? = null): OnlineStream {
        val st = if (keywords == null) sp.createStream() else sp.createStream(keywords)
        streamsOpen++
        return st
    }

    fun closeStream(st: OnlineStream?) {
        if (st == null) return
        release(st)
        streamsOpen--
    }

    @Volatile var releaseSupported = true

    /**
     * Frees native resources through the class's public release()/delete(). Never calls the finalizer directly:
     * if a version's finalizer does not mark the object as freed, the garbage collector would free it twice.
     */
    fun release(obj: Any?) {
        if (obj == null) return
        for (name in listOf("release", "delete")) {
            try { obj.javaClass.getMethod(name).invoke(obj); return } catch (_: NoSuchMethodException) {} catch (_: Throwable) { return }
        }
        releaseSupported = false
    }

    private fun spotter(ctx: Context, keywordsAsset: String): KeywordSpotter {
        spottersCreated++
        val config = KeywordSpotterConfig(
            featConfig = getFeatureConfig(SAMPLE_RATE, 80),
            modelConfig = getKwsModelConfig(1)!!, // 1 = English GigaSpeech model (checked in CI)
            keywordsFile = keywordsAsset,
        )
        return KeywordSpotter(ctx.assets, config)
    }

    /**
     * Feeds 16 kHz float samples to a stream and returns every keyword the spotter reports.
     * The live microphone and the self-test both go through this function.
     */
    fun feed(spotter: KeywordSpotter, stream: OnlineStream, samples: FloatArray): List<String> {
        stream.acceptWaveform(samples, SAMPLE_RATE)
        while (spotter.isReady(stream)) spotter.decode(stream)
        val k = spotter.getResult(stream).keyword
        if (k.isBlank()) return emptyList()
        spotter.reset(stream)
        return listOf(k.trim())
    }

    /** Feeds a whole recording in 100 ms pieces with silence before and after, like live audio (own stream, released). */
    fun feedRecording(spotter: KeywordSpotter, audio: FloatArray): List<String> {
        val stream = openStream(spotter)
        return try { feedRecordingInto(spotter, stream, audio) } finally { closeStream(stream) }
    }

    fun feedRecordingInto(spotter: KeywordSpotter, stream: OnlineStream, audio: FloatArray): List<String> {
        val out = ArrayList<String>()
        val padded = FloatArray(SAMPLE_RATE / 2) + audio + FloatArray(SAMPLE_RATE)
        var i = 0
        while (i < padded.size) {
            val end = min(padded.size, i + SAMPLE_RATE / 10)
            out.addAll(feed(spotter, stream, padded.copyOfRange(i, end)))
            i = end
        }
        return out
    }

    fun readAssetLines(ctx: Context, path: String): List<String> =
        ctx.assets.open(path).bufferedReader().readLines()

    /** Checks the bundled keywords against the model's vocabulary and records the result. */
    fun checkKeywords(ctx: Context): Boolean {
        return try {
            val kw = readAssetLines(ctx, "$MODEL_DIR/keywords.txt").filter { it.isNotBlank() }
            val vocab = KeywordFile.vocabulary(readAssetLines(ctx, "$MODEL_DIR/tokens.txt"))
            val issues = KeywordFile.validate(kw, vocab, listOf("HEY_SADA", "CONTINUE"))
            val list = kw.mapNotNull { KeywordFile.parseLine(it) }.joinToString("; ") {
                "${it.label}: ${it.tokens.joinToString(" ")} (boost ${it.score ?: "default"}, threshold ${it.threshold ?: "default"})"
            }
            Diag.keywordCheck = if (issues.isEmpty()) "✓ ${kw.size} keywords valid for the model: $list" else "✗ ${issues.joinToString("; ")}"
            Diag.log(if (issues.isEmpty()) "keywords" else "error", Diag.keywordCheck)
            issues.isEmpty()
        } catch (e: Exception) {
            Diag.keywordCheck = "✗ could not read keyword files: ${e.message}"
            Diag.log("error", Diag.keywordCheck)
            false
        }
    }
}
