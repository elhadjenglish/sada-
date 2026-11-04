package app.sada.poc.dsp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import java.util.Random;

/** SIMULATION tests with synthetic signals. */
public class CalibrationAndClassifierTest {
    static final int B = 256, FS = 16000;

    private static float[] tone(int n, double rms, double hz, long seed) {
        Random r = new Random(seed); float[] x = new float[n];
        for (int i = 0; i < n; i++) x[i] = (float) (rms * Math.sqrt(2) * Math.sin(2 * Math.PI * hz * i / FS) + rms * 0.05 * r.nextGaussian());
        return x;
    }
    private static float[] noise(int n, double rms, long seed) {
        Random r = new Random(seed); float[] x = new float[n];
        for (int i = 0; i < n; i++) x[i] = (float) (r.nextGaussian() * rms);
        return x;
    }
    private static double db(float[] x, int from, int to) { double s = 0; for (int i = from; i < to; i++) s += x[i] * x[i]; return 10 * Math.log10(s / (to - from) + 1e-14); }

    @Test public void calibrationGainRestoresNormalSpeechLevel() {
        InputGain g = new InputGain(); g.setGainDb(20);
        float[] quiet = SignalSim.speechLike(FS, 1, Math.pow(10, -48 / 20.0)), out = new float[FS];
        g.process(quiet, out, FS);
        double in = db(quiet, 0, FS), o = db(out, 0, FS);
        System.out.printf("    pocket-level speech %.1f dBFS → %.1f dBFS after +20 dB calibration%n", in, o);
        assertEquals(in + 20, o, 0.5);
    }

    @Test public void calibrationNeverExceedsFullScale() {
        InputGain g = new InputGain(); g.setGainDb(20);
        float[] loud = SignalSim.speechLike(FS, 2, 0.3), out = new float[FS];
        g.process(loud, out, FS);
        for (float v : out) assertTrue(Math.abs(v) < 1f);
    }

    @Test public void gainControlReactsWithinHalfASecondOfSpeech() {
        Agc a = new Agc();
        float[] x = new float[3 * FS];
        System.arraycopy(noise(FS, Math.pow(10, -70 / 20.0), 3), 0, x, 0, FS);
        System.arraycopy(tone(2 * FS, Math.pow(10, -48 / 20.0), 200, 4), 0, x, FS, 2 * FS);
        float[] y = new float[x.length], i = new float[B], o = new float[B];
        for (int p = 0; p + B <= x.length; p += B) { System.arraycopy(x, p, i, 0, B); a.process(i, o, B); System.arraycopy(o, 0, y, p, B); }
        double after500ms = db(y, FS + FS / 2, FS + FS / 2 + 4000);
        System.out.printf("    speech at -48 dBFS: %.1f dBFS 0.5 s after it starts (gain %.1f dB)%n", after500ms, a.gainDb());
        assertTrue("level after 0.5 s: " + after500ms, after500ms > -30);
    }

    private static SignalClassifier.Kind classify(float[] x) {
        SignalClassifier c = new SignalClassifier(B, FS);
        float[] blk = new float[B]; SignalClassifier.Kind k = null;
        for (int p = 0; p + B <= x.length; p += B) { System.arraycopy(x, p, blk, 0, B); SignalClassifier.Kind r = c.process(blk, B); if (r != null) k = r; }
        return k;
    }

    private static float[] then(float[] a, float[] b) { float[] y = new float[a.length + b.length]; System.arraycopy(a, 0, y, 0, a.length); System.arraycopy(b, 0, y, a.length, b.length); return y; }

    @Test public void classifierSeparatesTheCases() {
        double bg = Math.pow(10, -65 / 20.0);
        // each case: 1 s of background to learn the floor, then 1 s of the case
        assertEquals(SignalClassifier.Kind.SILENCE, classify(new float[2 * FS]));
        assertEquals(SignalClassifier.Kind.NOISE, classify(noise(2 * FS, 0.003, 5)));
        float[] quietSpeech = then(noise(FS, bg, 6), SignalSim.speechLike(FS, 7, Math.pow(10, -52 / 20.0)));
        float[] usable = then(noise(FS, bg, 8), SignalSim.speechLike(FS, 9, Math.pow(10, -30 / 20.0)));
        float[] clipped = then(noise(FS, bg, 10), tone(FS, 0.9, 300, 11));
        for (int i = FS; i < 2 * FS; i++) clipped[i] = Math.max(-1f, Math.min(1f, clipped[i] * 2));
        assertEquals("quiet speech", SignalClassifier.Kind.QUIET_SPEECH, classify(quietSpeech));
        assertEquals("usable speech", SignalClassifier.Kind.USABLE_SPEECH, classify(usable));
        assertEquals("clipped", SignalClassifier.Kind.CLIPPED, classify(clipped));
    }

    @Test public void frontEndStreamsWithoutEchoCancellation() {
        FrontEnd fe = new FrontEnd(new ReferenceBuffer(FS));
        fe.setInputGainDb(20);
        float[] quiet = SignalSim.speechLike(2 * FS, 12, Math.pow(10, -48 / 20.0));
        float[] blk = new float[B], main = new float[B], alt = new float[B];
        double altSum = 0; int altN = 0;
        for (int p = 0; p + B <= quiet.length; p += B) {
            System.arraycopy(quiet, p, blk, 0, B);
            fe.process(blk, p, Double.NaN, main, alt);
            if (p > FS / 2) { altSum += fe.altDb(); altN++; }
        }
        double rawDb = db(quiet, 0, quiet.length);
        System.out.printf("    raw %.1f dBFS → calibrated stream %.1f dBFS, adaptive stream now %.1f dBFS%n", rawDb, altSum / altN, fe.mainDb());
        assertTrue(fe.aecBlocks() == 0);
        assertTrue("calibrated stream should sit about 20 dB above raw", altSum / altN > rawDb + 15);
    }
}
