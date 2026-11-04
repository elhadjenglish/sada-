package app.sada.poc.dsp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import java.util.Random;

/** SIMULATION: speaker echo with unknown delays, room response, a talker and noise. */
public class DelayEstimateTest {
    static final int S = 16000;

    static float[] mic(float[] ref, int delay, double voiceRms, long seed) {
        float[] echo = SignalSim.convolve(ref, SignalSim.echoPath(Math.max(0, delay), 1200, seed), 0.5);
        float[] v = SignalSim.speechLike(ref.length, seed + 1, voiceRms);
        Random r = new Random(seed + 2);
        float[] m = new float[ref.length];
        for (int i = 0; i < m.length; i++) m[i] = echo[i] + v[i] + (float) (r.nextGaussian() * 0.001);
        return m;
    }

    @Test public void findsDelaysInsideAndOutsideTheCancellerWindow() {
        float[] ref = SignalSim.speechLike(10 * S, 5, 0.1);
        for (int ms : new int[]{20, 120, 300, 600}) {
            DelayEstimate.Result r = DelayEstimate.estimate(mic(ref, ms * 16, 0.02, ms), ref, 200 * 16, 900 * 16);
            System.out.printf("    true echo delay %d ms → measured %.1f ms (strength %.0f)%n", ms, r.lag / 16.0, r.strength);
            assertEquals("delay " + ms, ms, r.lag / 16.0, 2.0);
            assertTrue(r.strength > 10);
        }
    }

    @Test public void survivesATalkerAsLoudAsTheEcho() {
        float[] ref = SignalSim.speechLike(10 * S, 7, 0.1);
        DelayEstimate.Result r = DelayEstimate.estimate(mic(ref, 150 * 16, 0.08, 8), ref, 200 * 16, 900 * 16);
        assertEquals(150, r.lag / 16.0, 2.0);
    }

    @Test public void silenceGivesNoResult() {
        assertTrue(DelayEstimate.estimate(new float[S], SignalSim.speechLike(S, 9, 0.1), 100, 100) == null);
    }
}
