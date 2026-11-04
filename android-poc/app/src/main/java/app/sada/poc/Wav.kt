package app.sada.poc

import java.io.File
import java.io.FileOutputStream

/** Minimal WAV reader/writer (no Android classes, so it is unit-tested on the build machine). */
object Wav {
    internal fun le32(b: ByteArray, i: Int) = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8) or
        ((b[i + 2].toInt() and 0xff) shl 16) or ((b[i + 3].toInt() and 0xff) shl 24)
    internal fun le16(b: ByteArray, i: Int) = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)
    private fun put32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun put16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())

    /** Returns (fmt chunk, pcm data) or null. Tolerates engines that write an unknown data length. */
    fun parse(b: ByteArray): Pair<ByteArray, ByteArray>? {
        if (b.size < 44 || String(b, 0, 4, Charsets.US_ASCII) != "RIFF" || String(b, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        var i = 12
        var fmt: ByteArray? = null
        var data: ByteArray? = null
        while (i + 8 <= b.size) {
            val id = String(b, i, 4, Charsets.US_ASCII)
            val len = le32(b, i + 4)
            val start = i + 8
            if (id == "data") {
                val end = if (len <= 0 || start + len > b.size) b.size else start + len
                data = b.copyOfRange(start, end)
                break
            }
            if (len < 0 || start + len > b.size) break
            if (id == "fmt ") fmt = b.copyOfRange(start, start + len)
            i = start + len + (len and 1)
        }
        return if (fmt != null && data != null) Pair(fmt, data) else null
    }

    fun read(f: File): Pair<ByteArray, ByteArray>? = parse(f.readBytes())

    /** Decoded audio: mono samples in [-1, 1) and the sample rate. */
    class Audio(val samples: FloatArray, val sampleRate: Int)

    /** Decodes 16-bit PCM WAV (mono or multi-channel, averaged to mono). Null if the format is not 16-bit PCM. */
    fun decode(b: ByteArray): Audio? {
        val (fmt, data) = parse(b) ?: return null
        if (fmt.size < 16) return null
        val format = le16(fmt, 0)
        val channels = le16(fmt, 2).coerceAtLeast(1)
        val rate = le32(fmt, 4)
        val bits = le16(fmt, 14)
        if (format != 1 || bits != 16 || rate <= 0) return null
        val frames = data.size / (2 * channels)
        val out = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) {
                val i = (f * channels + c) * 2
                sum += ((data[i].toInt() and 0xff) or (data[i + 1].toInt() shl 8)).toShort().toInt()
            }
            out[f] = sum / (channels * 32768.0f)
        }
        return Audio(out, rate)
    }

    /** Builds a 16-bit mono PCM WAV (used by tests). */
    fun encodeMono16(samples: ShortArray, rate: Int): ByteArray {
        val data = ByteArray(samples.size * 2)
        for (i in samples.indices) { data[2 * i] = samples[i].toByte(); data[2 * i + 1] = (samples[i].toInt() shr 8).toByte() }
        val fmt = put16(1) + put16(1) + put32(rate) + put32(rate * 2) + put16(2) + put16(16)
        return "RIFF".toByteArray(Charsets.US_ASCII) + put32(4 + 8 + fmt.size + 8 + data.size) + "WAVE".toByteArray(Charsets.US_ASCII) +
            "fmt ".toByteArray(Charsets.US_ASCII) + put32(fmt.size) + fmt + "data".toByteArray(Charsets.US_ASCII) + put32(data.size) + data
    }

    fun concat(inputs: List<File>, out: File, silenceSeconds: Double): String? {
        val decoded = inputs.mapNotNull { read(it) }
        if (decoded.isEmpty()) return "The rendered audio could not be read."
        val fmt = decoded[0].first
        val same = decoded.filter { it.first.contentEquals(fmt) }
        val rate = le32(fmt, 4)
        val blockAlign = le16(fmt, 12).coerceAtLeast(1)
        val silence = ByteArray(((rate * silenceSeconds).toInt()) * blockAlign)
        val total = same.sumOf { it.second.size + silence.size }
        FileOutputStream(out).use { o ->
            o.write("RIFF".toByteArray(Charsets.US_ASCII)); o.write(put32(4 + 8 + fmt.size + 8 + total))
            o.write("WAVE".toByteArray(Charsets.US_ASCII))
            o.write("fmt ".toByteArray(Charsets.US_ASCII)); o.write(put32(fmt.size)); o.write(fmt)
            o.write("data".toByteArray(Charsets.US_ASCII)); o.write(put32(total))
            for (d in same) { o.write(d.second); o.write(silence) }
        }
        return null
    }
}
