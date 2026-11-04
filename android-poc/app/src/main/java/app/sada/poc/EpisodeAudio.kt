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
 * The narration of an episode as an audio file, made once with the phone's English voice from the episode text the
 * app supplies, then reused. The native player plays this file (the app has a single player).
 */
object EpisodeAudio {
    private const val CHUNK = 3000 // the phone's voice engines accept about 4000 characters at a time

    private fun dir(ctx: Context) = File(ctx.filesDir, "episodes").also { it.mkdirs() }

    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_").take(60)

    fun fileFor(ctx: Context, id: String, text: String) = File(dir(ctx), "${safe(id)}-${Integer.toHexString(text.hashCode())}.wav")

    fun durationMs(f: File): Long {
        val (fmt, data) = Wav.read(f) ?: return 0L
        val rate = Wav.le32(fmt, 4).coerceAtLeast(1)
        val block = Wav.le16(fmt, 12).coerceAtLeast(1)
        return data.size.toLong() / block * 1000L / rate
    }

    /** Sentences grouped into pieces of at most [CHUNK] characters. */
    fun chunks(text: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (s in text.trim().split(Regex("(?<=[.!?])\\s+"))) {
            if (cur.isNotEmpty() && cur.length + s.length + 1 > CHUNK) { out.add(cur.toString()); cur.setLength(0) }
            if (s.length > CHUNK) { s.chunked(CHUNK).forEach { out.add(it) }; continue }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(s)
        }
        if (cur.isNotBlank()) out.add(cur.toString())
        return out
    }

    /** The narration file (made with the phone's voice the first time), or an error text. Blocking: use a worker thread. */
    fun ensure(ctx: Context, id: String, text: String): Pair<File?, String?> {
        val out = fileFor(ctx, id, text)
        if (out.exists() && out.length() > 1000) return Pair(out, null)
        val pieces = chunks(text)
        if (pieces.isEmpty()) return Pair(null, "This episode has no text to narrate.")
        val ready = CountDownLatch(1)
        var ok = false
        val tts = TextToSpeech(ctx.applicationContext) { status -> ok = status == TextToSpeech.SUCCESS; ready.countDown() }
        val parts = ArrayList<File>()
        try {
            if (!ready.await(15, TimeUnit.SECONDS) || !ok) return Pair(null, "The phone's text-to-speech engine did not start.")
            tts.setLanguage(Locale.US)
            for ((i, piece) in pieces.withIndex()) {
                val part = File(ctx.cacheDir, "narr-${safe(id)}-$i.wav")
                val done = CountDownLatch(1)
                var failed = false
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { done.countDown() }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { failed = true; done.countDown() }
                })
                if (tts.synthesizeToFile(piece, Bundle(), part, "narr-$i") != TextToSpeech.SUCCESS) return Pair(null, "The phone's voice could not narrate this episode.")
                if (!done.await(180, TimeUnit.SECONDS) || failed || !part.exists()) return Pair(null, "Making the narration failed or took too long.")
                parts.add(part)
            }
            dir(ctx).listFiles { f -> f.name.startsWith(safe(id) + "-") }?.forEach { it.delete() } // older version of this episode
            val err = Wav.concat(parts, out, silenceSeconds = 0.3)
            return if (err == null) Pair(out, null) else Pair(null, err)
        } finally {
            tts.shutdown()
            parts.forEach { it.delete() }
        }
    }
}
