package app.sada.poc

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Renders Sada's six original episodes with the phone's voice into one WAV file (about 15 minutes, played on loop). */
object TestEpisode {
    fun file(ctx: Context) = File(ctx.filesDir, "test-episode.wav")

    /** Runs on a background thread. progress(done, total); returns null on success or an error message. */
    private fun indexFile(ctx: Context) = File(ctx.filesDir, "test-episode-index.txt")

    /** Start/end (ms) of each episode inside the rendered file, measured from the audio data (for question context). */
    private fun writeIndex(ctx: Context, parts: List<File>, gapSeconds: Double) {
        try {
            val lines = ArrayList<String>()
            var t = 0L
            for (p in parts) {
                val (fmt, data) = Wav.read(p) ?: return
                val rate = Wav.le32(fmt, 4)
                val block = Wav.le16(fmt, 12).coerceAtLeast(1)
                val ms = data.size.toLong() / block * 1000L / rate.coerceAtLeast(1)
                lines.add("$t\t${t + ms}")
                t += ms + (gapSeconds * 1000).toLong()
            }
            indexFile(ctx).writeText(lines.joinToString("\n"))
        } catch (_: Exception) {}
    }

    /** (starts, ends) of each episode, or null if this test episode was rendered by an older version. */
    fun index(ctx: Context): Pair<LongArray, LongArray>? = try {
        val rows = indexFile(ctx).readLines().filter { it.isNotBlank() }.map { it.split('\t') }
        Pair(LongArray(rows.size) { rows[it][0].toLong() }, LongArray(rows.size) { rows[it][1].toLong() })
    } catch (_: Exception) { null }

    fun generate(ctx: Context, progress: (Int, Int) -> Unit): String? {
        val ready = CountDownLatch(1)
        var ok = false
        lateinit var tts: TextToSpeech
        tts = TextToSpeech(ctx.applicationContext) { status -> ok = status == TextToSpeech.SUCCESS; ready.countDown() }
        if (!ready.await(15, TimeUnit.SECONDS) || !ok) return "The phone's text-to-speech engine did not start."
        tts.setLanguage(Locale.US)
        val parts = ArrayList<File>()
        try {
            for ((i, text) in EPISODE_TEXTS.withIndex()) {
                progress(i, EPISODE_TEXTS.size)
                val out = File(ctx.cacheDir, "ep$i.wav")
                val done = CountDownLatch(1)
                var failed = false
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { done.countDown() }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { failed = true; done.countDown() }
                })
                val r = tts.synthesizeToFile(text, Bundle(), out, "ep$i")
                if (r != TextToSpeech.SUCCESS) return "The phone's voice could not render episode ${i + 1}."
                if (!done.await(180, TimeUnit.SECONDS) || failed || !out.exists()) return "Rendering episode ${i + 1} failed or timed out."
                parts.add(out)
            }
            progress(EPISODE_TEXTS.size, EPISODE_TEXTS.size)
            val err = Wav.concat(parts, file(ctx), silenceSeconds = 1.5)
            if (err == null) writeIndex(ctx, parts, 1.5)
            return err
        } finally {
            tts.shutdown()
            parts.forEach { it.delete() }
        }
    }
}
