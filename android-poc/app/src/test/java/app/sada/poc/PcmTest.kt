package app.sada.poc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class PcmTest {
    @Test fun scaleIsWhatSherpaExpects() {
        val f = Pcm.toFloat(shortArrayOf(-32768, 0, 16384, 32767), 4)
        assertEquals(-1.0f, f[0], 0f)
        assertEquals(0.0f, f[1], 0f)
        assertEquals(0.5f, f[2], 0f)
        assertTrue(f[3] < 1.0f && f[3] > 0.9999f)
    }

    @Test fun onlyTheSamplesReadAreConverted() {
        assertEquals(2, Pcm.toFloat(shortArrayOf(1, 2, 3, 4), 2).size)
    }

    @Test fun silenceIsReportedAsSilence() {
        val s = Pcm.stats(ShortArray(1600), 1600)
        assertEquals(1600, s.zeros)
        assertTrue(s.rmsDb < -150)
    }

    @Test fun fullScaleSineLevelAndClipping() {
        val n = 1600
        val x = ShortArray(n) { (32767 * sin(2 * PI * 440 * it / 16000)).toInt().toShort() }
        val s = Pcm.stats(x, n)
        assertEquals(-3.0, s.rmsDb, 0.2)   // a full-scale sine is about -3 dBFS
        assertTrue(s.peak > 0.99)
        val loud = ShortArray(n) { 32767 }
        assertEquals(n, Pcm.stats(loud, n).clipped)
    }

    private fun zeroCrossings(x: FloatArray) = (1 until x.size).count { (x[it - 1] < 0) != (x[it] < 0) }

    @Test fun resampleKeepsLengthAndPitch() {
        for (from in listOf(22050, 24000, 48000)) {
            val x = FloatArray(from) { sin(2 * PI * 440 * it / from).toFloat() }
            val y = Pcm.resample(x, from, 16000)
            assertEquals("length from $from", 16000, y.size)
            assertEquals("pitch from $from", 440.0, zeroCrossings(y) / 2.0, 3.0)
        }
    }

    @Test fun resampleSameRateIsACopy() {
        val x = floatArrayOf(0.1f, -0.2f, 0.3f)
        val y = Pcm.resample(x, 16000, 16000)
        assertTrue(x.contentEquals(y))
    }
}
