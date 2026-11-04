package app.sada.poc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WavTest {
    @Test fun roundTripMono16() {
        val pcm = shortArrayOf(0, 1000, -1000, 32767, -32768)
        val a = Wav.decode(Wav.encodeMono16(pcm, 22050))
        assertNotNull(a)
        assertEquals(22050, a!!.sampleRate)
        assertEquals(pcm.size, a.samples.size)
        assertEquals(1000 / 32768f, a.samples[1], 1e-6f)
        assertEquals(-1f, a.samples[4], 0f)
    }

    @Test fun unknownDataLengthIsTolerated() {
        val b = Wav.encodeMono16(ShortArray(100) { 5 }, 16000)
        // engines that stream sometimes write 0 or 0xFFFFFFFF as the data length
        b[40] = 0xFF.toByte(); b[41] = 0xFF.toByte(); b[42] = 0xFF.toByte(); b[43] = 0xFF.toByte()
        assertEquals(100, Wav.decode(b)!!.samples.size)
    }

    @Test fun rejectsNonWav() {
        assertNull(Wav.decode(ByteArray(100)))
    }
}
