package app.sada.poc.dsp;

import static app.sada.poc.dsp.SignalSim.*;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import java.util.Random;

/**
 * SIMULATION of Phone Speaker Mode as the app runs it: podcast on the phone speaker a few cm from the microphone,
 * the phone's +20 dB calibration gain, the user 50 cm away speaking 10 dB below the echo. Not device evidence:
 * real speaker distortion, room and timestamps are only measured on the phone.
 */
public class SpeakerModeTest {
    static final int S = 16000;

    static final class Run { float[] mic, voice, out, alt; FrontEnd fe; }

    /** echoDbfs: raw level of the podcast echo at the mic. pauseAt/resumeAt: seconds, or -1 for no pause. */
    static Run run(double echoDbfs, double gainDb, int pauseAt, int resumeAt, int voiceAt) {
        int n = 20 * S;
        float[] pod = speechLike(n, 91, 0.1);
        float[] played = new float[n];
        ReferenceBuffer refBuf = new ReferenceBuffer(n + 4 * S);
        // Paused podcast: nothing is written to the speaker; the reference index stops too.
        long ri = 0; int src = 0;
        for (int i = 0; i < n; i++) {
            boolean paused = pauseAt >= 0 && i >= pauseAt * S && i < resumeAt * S;
            if (!paused) { played[i] = pod[src++]; }
        }
        // reference = the podcast samples in playback order (what the player writes)
        float[] refStream = new float[src];
        System.arraycopy(pod, 0, refStream, 0, src);
        refBuf.write(0, refStream, src);
        int delay = (int) (0.12 * S);
        float[] shifted = new float[n];
        for (int m = delay; m < n; m++) shifted[m] = played[m - delay];
        float[] echo = convolve(shifted, echoPath(16, 1200, 92), 1.0);
        echo = scale(echo, Math.pow(10, echoDbfs / 20) * rms(echo) / Math.max(1e-9, rms(echo)));
        double echoRms = Math.sqrt(power(echo, 2 * S, 8 * S));
        for (int i = 0; i < n; i++) echo[i] *= (float) (Math.pow(10, echoDbfs / 20) / echoRms);
        float[] voice = new float[n];
        float[] v = speechLike(2 * S, 93, Math.pow(10, (echoDbfs - 10) / 20));
        if (voiceAt * S + 2 * S <= n) System.arraycopy(v, 0, voice, voiceAt * S, 2 * S);
        Random r = new Random(94);
        float[] mic = new float[n];
        for (int i = 0; i < n; i++) mic[i] = Math.max(-1f, Math.min(1f, echo[i] + voice[i] + (float) (r.nextGaussian() * 0.0003)));

        FrontEnd fe = new FrontEnd(refBuf);
        fe.setInputGainDb(gainDb);
        ClockAlign align = new ClockAlign();
        float[] out = new float[n], alt = new float[n], blk = new float[FrontEnd.BLOCK], ob = new float[FrontEnd.BLOCK], ab = new float[FrontEnd.BLOCK];
        for (int b = 0; b + FrontEnd.BLOCK <= n; b += FrontEnd.BLOCK) {
            boolean paused = pauseAt >= 0 && b >= pauseAt * S && b < resumeAt * S;
            // what the app does: echo cancellation only while the podcast plays on the speaker
            fe.setEchoCancellation(!paused);
            fe.setPodcastActive(!paused);
            // timestamps: reference index of the sample being heard = played count at that moment, minus the speaker delay
            int heard = 0; for (int i = 0; i <= Math.max(0, b - delay) && i < n; i++) if (pauseAt < 0 || i < pauseAt * S || i >= resumeAt * S) heard++;
            double offset = paused ? Double.NaN : align.update((heard - 1) - (b - delay) - delay + r.nextGaussian() * 16);
            System.arraycopy(mic, b, blk, 0, FrontEnd.BLOCK);
            fe.process(blk, b, offset, ob, ab);
            System.arraycopy(ob, 0, out, b, FrontEnd.BLOCK);
            System.arraycopy(ab, 0, alt, b, FrontEnd.BLOCK);
        }
        Run x = new Run(); x.mic = mic; x.voice = voice; x.out = out; x.alt = alt; x.fe = fe;
        return x;
    }

    /** Voice-to-residual-echo ratio on the detector's alternative (linear) stream over the voice window. */
    static double voiceRatio(Run x, int voiceAt) {
        int a = voiceAt * S, b = a + 2 * S;
        double echoOnly = power(x.alt, a - 3 * S, a - S);
        return db(power(x.alt, a, b) / echoOnly);
    }

    @Test public void loudSpeakerEchoWithCalibrationGain() {
        int voiceAt = 12;
        Run x = run(-26, 20, -1, -1, voiceAt);
        double inRatio = db(power(x.voice, voiceAt * S, (voiceAt + 2) * S) / power(x.mic, (voiceAt - 3) * S, (voiceAt - 1) * S));
        double outRatio = voiceRatio(x, voiceAt);
        System.out.printf("    speaker echo -26 dBFS raw, +20 dB calibration: voice vs podcast %.1f dB at the mic → %.1f dB into the detector; echo reduction %.1f dB%n",
            inRatio, outRatio, x.fe.echoCanceller().erleDb());
        assertTrue("voice should stand out after echo cancellation, got " + outRatio, outRatio > 15);
    }

    @Test public void afterResumingNeverWorseThanTheMicAndRecoversWithinSeconds() {
        // podcast plays 0–8 s, paused 8–10 s (question + "Continue"), resumes at 10 s; user silent (voice outside the window)
        Run x = run(-26, 20, 8, 10, 30);
        double worst = -999;
        for (int b = 10 * S; b + S / 4 <= 12 * S; b += S / 4) {
            double excess = db(power(x.alt, b, b + S / 4)) - (db(power(x.mic, b, b + S / 4)) + 20);
            worst = Math.max(worst, excess);
        }
        double recovered = db(power(x.mic, 13 * S, 15 * S)) + 20 - db(power(x.alt, 13 * S, 15 * S));
        System.out.printf("    after resuming: detector stream at most %.1f dB above the plain mic (0 = no harm); echo reduction 3–5 s later %.1f dB%n", worst, recovered);
        assertTrue("never louder than the calibrated microphone, got +" + worst, worst < 0.5);
        assertTrue("echo reduction should be back within 5 s, got " + recovered, recovered > 15);
    }
}
