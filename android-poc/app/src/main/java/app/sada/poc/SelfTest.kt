package app.sada.poc

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Checks the detection pipeline without the microphone, so a failure can be pinned down:
 *  1. keywords valid for the model,
 *  2. the model's own recorded speech samples through the same Android code path, compared with the
 *     result the build machine got from the reference (Python) sherpa-onnx on the same files,
 *  3. "Hey Sada" / "Continue" spoken by the phone's voice, fed digitally into the live keywords.
 * Nothing is recorded from the microphone and nothing leaves the phone.
 */
object SelfTest {
    private const val DIR = "selftest"

    fun run(ctx: Context, progress: (String) -> Unit): String {
        val lines = ArrayList<String>()
        fun add(s: String) { lines.add(s); Diag.log("selftest", s); progress(lines.joinToString("\n")) }

        Kws.checkKeywords(ctx)
        add("1. Keywords: ${Diag.keywordCheck}")

        try {
            val wavs = ctx.assets.list(DIR)?.filter { it.endsWith(".wav") }?.sorted().orEmpty()
            if (wavs.isEmpty()) {
                add("2. Model check on recorded speech: no sample recordings in this build (skipped)")
            } else {
                val expected = readExpected(ctx)
                val sp = Kws.analysisSpotter(ctx, "$DIR/keywords.txt")
                var agree = 0
                for (f in wavs) {
                    val audio = ctx.assets.open("$DIR/$f").use { Wav.decode(it.readBytes()) }
                    if (audio == null) { add("   $f: unreadable"); continue }
                    val got = Kws.feedRecording(sp, Pcm.resample(audio.samples, audio.sampleRate, SAMPLE_RATE))
                    val want = expected?.get(f)
                    val verdict = when {
                        want == null -> "(no reference)"
                        want == got -> { agree++; "✓ same as reference" }
                        else -> "✗ reference found ${if (want.isEmpty()) "nothing" else want.joinToString(", ")}"
                    }
                    add("   $f: detected ${if (got.isEmpty()) "nothing" else got.joinToString(", ")} $verdict")
                }
                add("2. Model check on recorded speech: " +
                    if (expected == null) "no reference from the build machine; detections listed above" else "$agree of ${wavs.size} match the reference")
            }
        } catch (e: Throwable) {
            add("2. Model check failed: ${e.javaClass.simpleName}: ${e.message}")
        }

        try {
            val phrases = listOf("Hey Sada." to "HEY_SADA", "Hey Sada, what does reliable mean?" to "HEY_SADA", "Continue." to "CONTINUE")
            val files = PhoneVoice.render(ctx, phrases.map { it.first })
            if (files == null) {
                add("3. Phone-voice check: the phone's text-to-speech is not available (skipped)")
            } else {
                val sp = Kws.analysisSpotter(ctx, "$MODEL_DIR/keywords.txt")
                var passed = 0
                var heySada: FloatArray? = null
                for ((i, f) in files.withIndex()) {
                    val audio = Wav.decode(f.readBytes())
                    f.delete()
                    if (audio == null) { add("   “${phrases[i].first}”: the phone's voice wrote audio in a format this test can't read"); continue }
                    val x16 = Pcm.resample(audio.samples, audio.sampleRate, SAMPLE_RATE)
                    if (i == 0) heySada = x16
                    val got = Kws.feedRecording(sp, x16)
                    val ok = got.any { it.startsWith(phrases[i].second) }
                    if (ok) passed++
                    add("   “${phrases[i].first}” (voice at ${audio.sampleRate} Hz): detected ${if (got.isEmpty()) "nothing" else got.joinToString(", ")} ${if (ok) "✓" else "✗"}")
                }
                add("3. Phone-voice check: $passed of ${phrases.size} phrases detected")
                heySada?.let { add("4. Level sweep (same “Hey Sada”, speech level → detected?): " + levelSweep(sp, it)) }
            }
        } catch (e: Throwable) {
            add("3. Phone-voice check failed: ${e.javaClass.simpleName}: ${e.message}")
        }

        try {
            val ref = Kws.readAssetLines(ctx, "$DIR/level-sensitivity.tsv").filter { it.isNotBlank() }
            if (ref.isNotEmpty()) add("5. Build machine, real recorded speech (level → detections / recordings): " + ref.joinToString("; ") { it.replace('\t', ' ') })
        } catch (_: Exception) {}

        Diag.selfTest = lines.filter { Regex("^[0-9]\\.").containsMatchIn(it) }.joinToString(" | ")
        return lines.joinToString("\n")
    }

    /** Scales the phrase so its speech parts sit at each level, and reports whether the detector still finds it. */
    private fun levelSweep(sp: com.k2fsa.sherpa.onnx.KeywordSpotter, x: FloatArray): String {
        // speech level = RMS of the louder half of 16 ms blocks (ignores the pauses)
        val blockDb = ArrayList<Double>()
        var i = 0
        while (i + 256 <= x.size) {
            var s = 0.0
            for (k in i until i + 256) s += x[k] * x[k]
            blockDb.add(10 * kotlin.math.log10(s / 256 + 1e-14)); i += 256
        }
        val sorted = blockDb.sortedDescending()
        val speechDb = if (sorted.isEmpty()) -120.0 else sorted.take(maxOf(1, sorted.size / 2)).average()
        return listOf(-20, -30, -40, -50).joinToString(", ") { target ->
            val g = Math.pow(10.0, (target - speechDb) / 20).toFloat()
            val y = FloatArray(x.size) { (x[it] * g).coerceIn(-0.98f, 0.98f) }
            val hit = Kws.feedRecording(sp, y).any { it.startsWith("HEY_SADA") }
            "$target dBFS ${if (hit) "✓" else "✗"}"
        }
    }

    /** "file<TAB>LABEL|LABEL" per line, written by the build machine's reference run. */
    private fun readExpected(ctx: Context): Map<String, List<String>>? = try {
        Kws.readAssetLines(ctx, "$DIR/expected.tsv").filter { it.isNotBlank() }.associate { line ->
            val p = line.split('\t')
            p[0] to (p.getOrNull(1)?.split('|')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList())
        }
    } catch (_: Exception) { null }
}

/** Renders short phrases with the phone's own text-to-speech into WAV files (cache, deleted after use). */
object PhoneVoice {
    fun render(ctx: Context, texts: List<String>): List<File>? {
        val ready = CountDownLatch(1)
        var ok = false
        val tts = TextToSpeech(ctx.applicationContext) { status -> ok = status == TextToSpeech.SUCCESS; ready.countDown() }
        try {
            if (!ready.await(15, TimeUnit.SECONDS) || !ok) return null
            tts.setLanguage(Locale.US)
            val out = ArrayList<File>()
            for ((i, t) in texts.withIndex()) {
                val f = File(ctx.cacheDir, "selftest-$i.wav")
                val done = CountDownLatch(1)
                var failed = false
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { done.countDown() }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { failed = true; done.countDown() }
                })
                if (tts.synthesizeToFile(t, Bundle(), f, "selftest-$i") != TextToSpeech.SUCCESS) return null
                if (!done.await(30, TimeUnit.SECONDS) || failed || !f.exists()) return null
                out.add(f)
            }
            return out
        } finally {
            tts.shutdown()
        }
    }
}
