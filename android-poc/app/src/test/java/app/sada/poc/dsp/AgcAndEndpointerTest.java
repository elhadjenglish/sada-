package app.sada.poc.dsp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import java.util.Random;

/** SIMULATION tests with synthetic signals. */
public class AgcAndEndpointerTest {
    static final int B = 256;

    private static float[] concat(float[]... parts) {
        int n = 0; for (float[] p : parts) n += p.length;
        float[] y = new float[n]; int o = 0;
        for (float[] p : parts) { System.arraycopy(p, 0, y, o, p.length); o += p.length; }
        return y;
    }

    private static float[] noise(int n, double rms, long seed) {
        Random r = new Random(seed); float[] x = new float[n];
        for (int i = 0; i < n; i++) x[i] = (float) (r.nextGaussian() * rms);
        return x;
    }

    private static float[] runAgc(Agc a, float[] x) {
        float[] y = new float[x.length]; float[] i = new float[B], o = new float[B];
        for (int p = 0; p + B <= x.length; p += B) { System.arraycopy(x, p, i, 0, B); a.process(i, o, B); System.arraycopy(o, 0, y, p, B); }
        return y;
    }

    @Test public void quietPocketSpeechIsRaisedTowardTarget() {
        float[] x = concat(noise(16000, 0.0003, 1), SignalSim.speechLike(10 * 16000, 2, 0.004)); // speech around -48 dBFS
        Agc a = new Agc();
        float[] y = runAgc(a, x);
        double outDb = SignalSim.db(SignalSim.power(y, 6 * 16000, 11 * 16000));
        System.out.printf("    speech -48 dBFS → %.1f dBFS (gain %.1f dB)%n", outDb, a.gainDb());
        assertTrue(outDb > -35);
        assertTrue(a.gainDb() <= 24.0001);
    }

    @Test public void noiseAloneIsNotPumpedUp() {
        Agc a = new Agc();
        runAgc(a, noise(8 * 16000, 0.001, 3));
        assertTrue("gain " + a.gainDb(), a.gainDb() < 3);
    }

    @Test public void loudInputIsNotClipped() {
        Agc a = new Agc();
        float[] y = runAgc(a, SignalSim.speechLike(4 * 16000, 4, 0.5));
        double peak = 0; for (float v : y) peak = Math.max(peak, Math.abs(v));
        assertTrue("peak " + peak, peak <= 1.0);
    }

    private static Endpointer runEp(float[] x, Endpointer e) {
        float[] blk = new float[B];
        for (int p = 0; p + B <= x.length && e.state() != Endpointer.State.DONE; p += B) { System.arraycopy(x, p, blk, 0, B); e.process(blk, B); }
        return e;
    }

    @Test public void questionStartAndEndAreFound() {
        float[] x = concat(noise(16000, 0.001, 5), SignalSim.speechLike(2 * 16000, 6, 0.05), noise(3 * 16000, 0.001, 7));
        // speechLike has pauses; force continuous speech for a clean boundary
        float[] speech = new float[2 * 16000];
        Random r = new Random(8);
        for (int i = 0; i < speech.length; i++) speech[i] = (float) (0.05 * Math.sin(2 * Math.PI * 180 * i / 16000) + 0.01 * r.nextGaussian());
        x = concat(noise(16000, 0.001, 5), speech, noise(3 * 16000, 0.001, 7));
        Endpointer e = runEp(x, new Endpointer());
        assertEquals(Endpointer.Reason.END_OF_SPEECH, e.reason());
        double startS = e.speechStartBlock() * 0.016, endS = e.speechEndBlock() * 0.016;
        System.out.printf("    speech 1.00–3.00 s detected %.2f–%.2f s%n", startS, endS);
        assertEquals(1.0, startS, 0.15);
        assertEquals(3.0, endS, 0.15);
    }

    @Test public void noisyStreetDoesNotLookLikeSpeech() {
        // background noise at about -40 dBFS (street, walking) then a question at about -20 dBFS
        float[] speech = new float[2 * 16000];
        Random r = new Random(11);
        for (int i = 0; i < speech.length; i++) speech[i] = (float) (0.1 * Math.sin(2 * Math.PI * 160 * i / 16000) + 0.01 * r.nextGaussian());
        float[] x = concat(noise(16000, 0.01, 12), speech, noise(3 * 16000, 0.01, 13));
        Endpointer e = runEp(x, new Endpointer());
        System.out.printf("    noisy street: %s, speech %.2f–%.2f s (true 1.00–3.00)%n", e.reason(), e.speechStartBlock() * 0.016, e.speechEndBlock() * 0.016);
        assertEquals(Endpointer.Reason.END_OF_SPEECH, e.reason());
        assertEquals(1.0, e.speechStartBlock() * 0.016, 0.2);
        assertEquals(3.0, e.speechEndBlock() * 0.016, 0.2);
    }

    @Test public void silenceGivesUp() {
        Endpointer e = runEp(noise(8 * 16000, 0.001, 9), new Endpointer(6000, 12000));
        assertEquals(Endpointer.Reason.NO_SPEECH, e.reason());
    }

    @Test public void footstepClicksAreNotSpeech() {
        float[] x = noise(8 * 16000, 0.001, 10);
        for (int s = 8000; s < x.length; s += 8000) for (int i = 0; i < 200; i++) x[s + i] += 0.3f * (float) Math.exp(-i / 40.0); // 12 ms thumps
        assertEquals(Endpointer.Reason.NO_SPEECH, runEp(x, new Endpointer(6000, 12000)).reason());
    }

    @Test public void longTalkStopsAtMaximum() {
        float[] x = new float[20 * 16000];
        for (int i = 0; i < x.length; i++) x[i] = (float) (0.05 * Math.sin(2 * Math.PI * 200 * i / 16000));
        Endpointer e = new Endpointer(6000, 12000);
        e.setNoiseFloorDb(-60);
        assertEquals(Endpointer.Reason.MAX_LENGTH, runEp(x, e).reason());
    }
}
