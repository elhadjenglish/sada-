package app.sada.poc

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sqrt

/** Microphone sample handling, kept free of Android classes so it is unit-tested on the build machine. */
object Pcm {
    /** 16-bit PCM → floats in [-1, 1), the scale sherpa-onnx expects. */
    fun toFloat(src: ShortArray, n: Int): FloatArray = FloatArray(n) { src[it] / 32768.0f }

    class Stats(val rmsDb: Double, val peak: Double, val clipped: Int, val zeros: Int, val n: Int)

    /** Level (dBFS), peak (0..1), clipped samples (at full scale) and exact-zero samples in one buffer. */
    fun stats(src: ShortArray, n: Int): Stats {
        var sum = 0.0
        var peak = 0
        var clipped = 0
        var zeros = 0
        for (i in 0 until n) {
            val v = src[i].toInt()
            sum += v.toDouble() * v
            val a = abs(v)
            if (a > peak) peak = a
            if (a >= 32767) clipped++
            if (v == 0) zeros++
        }
        val rms = if (n > 0) sqrt(sum / n) / 32768.0 else 0.0
        return Stats(20 * log10(rms + 1e-9), peak / 32768.0, clipped, zeros, n)
    }

    /** Converts to another sample rate (e.g. the phone voice's 22–24 kHz to the model's 16 kHz). */
    fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        if (x.isEmpty() || from == to) return x.copyOf()
        var src = x
        if (from > to) {
            // simple low-pass (moving average) before downsampling, to limit aliasing
            val w = ceil(from.toDouble() / to).toInt()
            if (w > 1) src = FloatArray(x.size) { i ->
                var s = 0f; var c = 0
                for (k in 0 until w) { val j = i - k; if (j >= 0) { s += x[j]; c++ } }
                s / c
            }
        }
        val outLen = ((src.size.toLong() * to) / from).toInt()
        return FloatArray(outLen) { i ->
            val pos = i.toDouble() * from / to
            val i0 = pos.toInt()
            val f = (pos - i0).toFloat()
            val a = src[min(i0, src.size - 1)]
            val b = src[min(i0 + 1, src.size - 1)]
            a + (b - a) * f
        }
    }
}
