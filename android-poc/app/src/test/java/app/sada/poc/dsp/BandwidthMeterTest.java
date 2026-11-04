package app.sada.poc.dsp;

import static org.junit.Assert.assertTrue;

import org.junit.Test;
import java.util.Random;

/** SIMULATION: narrowband vs wideband speech-like audio, as a Bluetooth 8 kHz or 16 kHz link would deliver it. */
public class BandwidthMeterTest {
    static final int FS = 16000;

    /** Speech-like signal, optionally band-limited to below 3.6 kHz (what an 8 kHz link leaves after upsampling). */
    static float[] signal(boolean narrow, long seed) {
        float[] x = SignalSim.speechLike(6 * FS, seed, 0.05);
        Random r = new Random(seed);
        for (int i = 0; i < x.length; i++) x[i] += (float) (r.nextGaussian() * 0.01 * (x[i] != 0 ? 1 : 0)); // fricative-like hiss
        if (!narrow) return x;
        // brick-wall low-pass via FFT in 256-sample frames with overlap-add (good enough for a test signal)
        int n = 512; Fft f = new Fft(n); float[] y = new float[x.length + n];
        float[] w = new float[n]; for (int i = 0; i < n; i++) w[i] = (float) Math.sqrt(0.5 - 0.5 * Math.cos(2 * Math.PI * i / n));
        for (int p = 0; p + n <= x.length; p += n / 2) {
            float[] re = new float[n], im = new float[n];
            for (int i = 0; i < n; i++) re[i] = x[p + i] * w[i];
            f.transform(re, im, false);
            for (int k = 0; k < n; k++) { int kk = Math.min(k, n - k); if (kk * (double) FS / n > 3600) { re[k] = 0; im[k] = 0; } }
            f.transform(re, im, true);
            for (int i = 0; i < n; i++) y[p + i] += re[i] * w[i];
        }
        float[] out = new float[x.length]; System.arraycopy(y, 0, out, 0, x.length);
        return out;
    }

    static BandwidthMeter measure(float[] x) {
        BandwidthMeter m = new BandwidthMeter();
        float[] b = new float[256];
        for (int p = 0; p + 256 <= x.length; p += 256) { System.arraycopy(x, p, b, 0, 256); m.process(b); }
        return m;
    }

    @Test public void tellsNarrowbandFromWideband() {
        BandwidthMeter wide = measure(signal(false, 1)), narrow = measure(signal(true, 1));
        System.out.printf("    wideband: %s%n    narrowband: %s%n", wide.verdict(), narrow.verdict());
        assertTrue(wide.verdict(), wide.verdict().startsWith("wideband"));
        assertTrue(narrow.verdict(), narrow.verdict().startsWith("NARROWBAND"));
    }

    @Test public void silenceGivesNoVerdict() {
        assertTrue(measure(new float[3 * FS]).verdict().startsWith("not enough"));
    }
}
