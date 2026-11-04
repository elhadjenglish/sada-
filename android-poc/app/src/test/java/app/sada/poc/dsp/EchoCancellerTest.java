package app.sada.poc.dsp;

import static app.sada.poc.dsp.SignalSim.*;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import java.util.Random;

/**
 * SIMULATION tests of the echo canceller with synthetic speech-like signals and synthetic room responses.
 * They show the algorithm converges, protects the user's voice and recovers; they do not prove how much echo
 * a real Infinix speaker and microphone produce. The phone reports real figures in its diagnostics.
 */
public class EchoCancellerTest {
    static final int S = FS; // one second

    private static float[] mix(float[]... parts) {
        float[] y = new float[parts[0].length];
        for (float[] p : parts) for (int i = 0; i < y.length; i++) y[i] += p[i];
        return y;
    }

    private static float[] noise(int n, double rms, long seed) {
        Random r = new Random(seed); float[] x = new float[n];
        for (int i = 0; i < n; i++) x[i] = (float) (r.nextGaussian() * rms);
        return x;
    }

    @Test public void podcastOnlyConvergesAndCancelsEcho() {
        int n = 12 * S;
        float[] ref = speechLike(n, 1, 0.1);
        float[] echo = convolve(ref, echoPath(40 * 16, 1600, 2), 0.5);
        float[] mic = mix(echo, noise(n, 0.0005, 3));
        float[] out = run(new EchoCanceller(), null, mic, ref);
        double erle = db(power(mic, 6 * S, 12 * S) / power(out, 6 * S, 12 * S));
        System.out.printf("    linear echo reduction after convergence: %.1f dB%n", erle);
        assertTrue("echo reduction " + erle + " dB", erle > 20);
    }

    @Test public void residualSuppressorAddsReduction() {
        int n = 12 * S;
        float[] ref = speechLike(n, 11, 0.1);
        float[] echo = convolve(ref, echoPath(40 * 16, 1600, 12), 0.5);
        float[] mic = mix(echo, noise(n, 0.0005, 13));
        float[] lin = run(new EchoCanceller(), null, mic, ref);
        float[] both = run(new EchoCanceller(), new ResidualEchoSuppressor(), mic, ref);
        double a = db(power(mic, 6 * S, 12 * S) / power(lin, 6 * S, 12 * S));
        double b = db(power(mic, 6 * S, 12 * S) / power(both, 6 * S, 12 * S));
        System.out.printf("    linear %.1f dB → with residual suppressor %.1f dB%n", a, b);
        assertTrue(b > a + 3);
    }

    @Test public void userVoiceSurvivesTalkingOverThePodcast() {
        int n = 14 * S;
        float[] ref = speechLike(n, 21, 0.1);
        float[] echo = convolve(ref, echoPath(40 * 16, 1600, 22), 0.5);
        float[] near = new float[n];
        float[] voice = speechLike(2 * S, 23, 0.3 * rms(echo)); // user 10 dB below the echo at the mic
        System.arraycopy(voice, 0, near, 8 * S, 2 * S);
        float[] mic = mix(echo, near, noise(n, 0.0005, 24));
        float[] out = run(new EchoCanceller(), null, mic, ref);
        double inRatio = db(power(near, 8 * S, 10 * S) / power(echo, 8 * S, 10 * S));
        float[] leftover = new float[n]; for (int i = 0; i < n; i++) leftover[i] = out[i] - near[i];
        double outRatio = db(power(near, 8 * S, 10 * S) / power(leftover, 8 * S, 10 * S));
        double corr = correlation(out, 8 * S, near, 8 * S, 2 * S);
        double after = db(power(mic, 11 * S, 14 * S) / power(out, 11 * S, 14 * S));
        System.out.printf("    voice-to-echo at mic %.1f dB → after canceller %.1f dB; output/voice correlation %.3f; echo reduction after talking %.1f dB%n",
            inRatio, outRatio, corr, after);
        assertTrue("voice should dominate after cancellation", outRatio > 10);
        assertTrue("voice preserved", corr > 0.95);
        assertTrue("no divergence after double talk", after > 15);
    }

    @Test public void distortedSpeakerStillReduced() {
        int n = 12 * S;
        float[] ref = speechLike(n, 31, 0.1);
        float[] lin = convolve(ref, echoPath(40 * 16, 1600, 32), 0.5);
        float[] mic = new float[n];
        for (int i = 0; i < n; i++) mic[i] = (float) (0.3 * Math.tanh(lin[i] / 0.3)); // speaker overdriven
        float[] out = run(new EchoCanceller(), new ResidualEchoSuppressor(), mic, ref);
        double erle = db(power(mic, 6 * S, 12 * S) / power(out, 6 * S, 12 * S));
        System.out.printf("    overdriven speaker: echo reduction %.1f dB (with residual suppressor)%n", erle);
        assertTrue(erle > 15);
    }

    @Test public void worksAcrossAlignmentErrors() {
        for (int delayMs : new int[]{5, 40, 120, 200}) {
            int n = 10 * S;
            float[] ref = speechLike(n, 40 + delayMs, 0.1);
            float[] mic = convolve(ref, echoPath(delayMs * 16, 800, 41), 0.5);
            EchoCanceller aec = new EchoCanceller();
            float[] out = run(aec, null, mic, ref);
            double erle = db(power(mic, 5 * S, 10 * S) / power(out, 5 * S, 10 * S));
            double learnedMs = aec.peakTap() / 16.0;
            System.out.printf("    true delay %d ms: reduction %.1f dB, learned delay %.1f ms%n", delayMs, erle, learnedMs);
            assertTrue("delay " + delayMs, erle > 15);
            assertTrue("delay estimate " + learnedMs, Math.abs(learnedMs - delayMs) < 3);
        }
    }

    @Test public void recoversWhenThePhoneMoves() {
        int n = 16 * S;
        float[] ref = speechLike(n, 51, 0.1);
        float[] a = convolve(ref, echoPath(30 * 16, 1600, 52), 0.5);
        float[] b = convolve(ref, echoPath(70 * 16, 1600, 53), 0.8);
        float[] mic = new float[n];
        for (int i = 0; i < n; i++) mic[i] = i < 8 * S ? a[i] : b[i];
        float[] out = run(new EchoCanceller(), null, mic, ref);
        double erle = db(power(mic, 12 * S, 16 * S) / power(out, 12 * S, 16 * S));
        System.out.printf("    after the echo path changed: %.1f dB within 4 s%n", erle);
        assertTrue(erle > 15);
    }

    @Test public void silentPodcastPassesMicThrough() {
        int n = 3 * S;
        float[] ref = new float[n];
        float[] mic = speechLike(n, 61, 0.05);
        float[] out = run(new EchoCanceller(), null, mic, ref);
        double diff = 0; for (int i = 0; i < out.length; i++) diff = Math.max(diff, Math.abs(out[i] - mic[i]));
        assertTrue("max difference " + diff, diff < 1e-6);
    }
}
