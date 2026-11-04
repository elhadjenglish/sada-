package app.sada.poc.dsp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import java.util.Random;

public class AlignmentAndBuffersTest {
    @Test public void referenceBufferReadsWhatWasWritten() {
        ReferenceBuffer b = new ReferenceBuffer(1000);
        float[] x = new float[300]; for (int i = 0; i < 300; i++) x[i] = i;
        b.write(0, x, 300);
        float[] out = new float[10];
        assertEquals(10, b.read(100, out, 10));
        assertEquals(105.0, out[5], 0);
        assertEquals("not yet written reads as silence", 5, b.read(295, out, 10));
        assertEquals(0.0, out[9], 0);
    }

    @Test public void referenceBufferForgetsOldAudio() {
        ReferenceBuffer b = new ReferenceBuffer(100);
        float[] x = new float[250]; java.util.Arrays.fill(x, 1f);
        b.write(0, x, 250);
        float[] out = new float[10];
        assertEquals(0, b.read(50, out, 10));
        assertEquals(10, b.read(200, out, 10));
    }

    @Test public void resamplerIsSeamlessAcrossChunks() {
        for (int from : new int[]{44100, 48000, 22050, 16000}) {
            int n = from * 2;
            float[] x = new float[n]; for (int i = 0; i < n; i++) x[i] = (float) Math.sin(2 * Math.PI * 300 * i / from);
            float[] whole = new StreamResampler(from, 16000).process(x, n);
            StreamResampler r = new StreamResampler(from, 16000);
            Random rnd = new Random(from);
            float[] pieces = new float[whole.length + 10]; int got = 0, pos = 0;
            while (pos < n) {
                int len = Math.min(n - pos, 1 + rnd.nextInt(3000));
                float[] c = new float[len]; System.arraycopy(x, pos, c, 0, len); pos += len;
                float[] o = r.process(c, len); System.arraycopy(o, 0, pieces, got, o.length); got += o.length;
            }
            assertEquals("count at " + from, whole.length, got);
            assertEquals("about two seconds at 16 kHz", 32000, got, 2);
            double maxDiff = 0; for (int i = 0; i < got; i++) maxDiff = Math.max(maxDiff, Math.abs(whole[i] - pieces[i]));
            assertTrue("chunking changed samples by " + maxDiff + " at " + from, maxDiff < 1e-4);
        }
    }

    @Test public void offsetFromTimestamps() {
        // mic frame 16000 captured at t = 2.0 s; podcast at 48 kHz was at frame 72000 at t = 1.5 s
        double off = ClockAlign.rawOffset(16000, 2_000_000_000L, 72000, 1_500_000_000L, 48000);
        // at t = 2.0 s the podcast is at frame 72000 + 0.5*48000 = 96000 → 32000 in 16 kHz samples
        assertEquals(32000 - 16000, off, 1e-6);
    }

    @Test public void smoothingRemovesJitterAndResyncsOnJumps() {
        ClockAlign c = new ClockAlign();
        Random r = new Random(1);
        double truth = 1234;
        for (int i = 0; i < 400; i++) c.update(truth + r.nextGaussian() * 32); // ±2 ms timestamp jitter
        assertEquals("smoothed within 0.5 ms", truth, c.offset(), 8);
        c.update(truth + 5000); // podcast restarted / track recreated
        assertEquals(truth + 5000, c.offset(), 1e-9);
        assertEquals(1, c.resyncs());
    }
}
