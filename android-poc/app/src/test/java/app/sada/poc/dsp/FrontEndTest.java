package app.sada.poc.dsp;

import static app.sada.poc.dsp.SignalSim.*;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import java.util.Random;

/**
 * SIMULATION of the whole microphone chain as it runs on the phone: the player writes 48 kHz podcast audio into
 * the reference buffer through the resampler, timestamps carry jitter, the "speaker" plays it into a synthetic room,
 * the user speaks 10 dB below the echo, and the chain must hand the detector the user's voice.
 */
public class FrontEndTest {
    @Test public void speakerPodcastIsRemovedAndUserVoiceKept() {
        int sec = 16000, n = 14 * sec;
        // podcast at 48 kHz → reference buffer at 16 kHz via the same resampler the player uses
        float[] pod48 = new float[n * 3];
        float[] pod16 = speechLike(n, 71, 0.1);
        for (int i = 0; i < pod48.length; i++) pod48[i] = pod16[Math.min(n - 1, i / 3)];
        // This test writes the whole podcast first, so the buffer must hold all of it (the real player writes <1 s ahead).
        ReferenceBuffer refBuf = new ReferenceBuffer(n + 4 * sec);
        StreamResampler rs = new StreamResampler(48000, 16000);
        // playback runs ahead of the mic by a buffer; the speaker emits sample p at time p/16000 + 0.120 s
        int playDelay = (int) (0.120 * sec);
        float[] played16 = new float[n];
        // reference as the player resamples it, in chunks
        int pos = 0; long refWritten = 0;
        Random r = new Random(72);
        while (pos < pod48.length) {
            int len = Math.min(pod48.length - pos, 1024 + r.nextInt(2048));
            float[] c = new float[len]; System.arraycopy(pod48, pos, c, 0, len); pos += len;
            float[] o = rs.process(c, len);
            for (int i = 0; i < o.length && refWritten + i < n; i++) played16[(int) (refWritten + i)] = o[i];
            refBuf.write(refWritten, o, o.length); refWritten += o.length;
        }
        // acoustic echo of what the speaker played: mic sample m hears reference sample m - playDelay (+ room)
        float[] shifted = new float[n];
        for (int m = playDelay; m < n; m++) shifted[m] = played16[m - playDelay];
        float[] echo = convolve(shifted, echoPath(16, 1200, 73), 0.6);
        float[] voice = new float[n];
        float[] v = speechLike(2 * sec, 74, 0.3 * rms(echo));
        System.arraycopy(v, 0, voice, 10 * sec, 2 * sec);
        float[] mic = new float[n];
        for (int i = 0; i < n; i++) mic[i] = echo[i] + voice[i] + (float) (r.nextGaussian() * 0.0005);

        FrontEnd fe = new FrontEnd(refBuf);
        fe.setEchoCancellation(true);
        fe.setPodcastActive(true);
        float[] cancelled = new float[n], linear = new float[n], ab = new float[FrontEnd.BLOCK];
        ClockAlign align = new ClockAlign();
        float[] out = new float[n], blk = new float[FrontEnd.BLOCK], ob = new float[FrontEnd.BLOCK];
        for (int b = 0; b + FrontEnd.BLOCK <= n; b += FrontEnd.BLOCK) {
            // timestamps say: reference index = mic index - playDelay, with ±1.5 ms jitter
            double offset = align.update(-playDelay + r.nextGaussian() * 24);
            System.arraycopy(mic, b, blk, 0, FrontEnd.BLOCK);
            fe.process(blk, b, offset, ob, ab);
            System.arraycopy(ob, 0, out, b, FrontEnd.BLOCK);
            System.arraycopy(ab, 0, linear, b, FrontEnd.BLOCK);
            System.arraycopy(fe.lastEchoCancelled(), 0, cancelled, b, FrontEnd.BLOCK);
        }
        // Measured on the echo-cancelled signal (before gain control), which has the suppressor's 16 ms delay.
        int d = FrontEnd.BLOCK;
        double corr = correlation(cancelled, 10 * sec + d, voice, 10 * sec, 2 * sec - d);
        double echoOnlyIn = power(mic, 6 * sec, 10 * sec), echoOnlyOut = power(cancelled, 6 * sec + d, 10 * sec + d);
        double voiceOut = power(cancelled, 10 * sec + d, 12 * sec + d);
        double inRatio = db(power(voice, 10 * sec, 12 * sec) / echoOnlyIn);
        double outRatio = db(voiceOut / echoOnlyOut);
        double linRatio = db(power(linear, 10 * sec, 12 * sec) / power(linear, 6 * sec, 10 * sec));
        double linCorr = correlation(linear, 10 * sec, voice, 10 * sec, 2 * sec);
        System.out.printf("    user voice vs podcast: %.1f dB at the mic%n", inRatio);
        System.out.printf("      main stream (canceller + suppressor): %.1f dB, voice correlation %.3f%n", outRatio, corr);
        System.out.printf("      alt stream (canceller only):          %.1f dB, voice correlation %.3f%n", linRatio, linCorr);
        System.out.printf("      echo reduction %.1f dB; learned delay %.1f ms; re-alignments %d; blocks before the podcast reached the speaker %d%n",
            db(echoOnlyIn / echoOnlyOut), fe.echoCanceller().peakTap() / 16.0, fe.realigns(), fe.missingReferenceBlocks());
        assertTrue("gain held while the podcast plays through the speaker", Math.abs(fe.agc().gainDb()) < 1e-9);
        // the simulated speaker starts 120 ms late: the first (120 - 32) ms have no podcast audio yet
        assertTrue("reference missing for " + fe.missingReferenceBlocks() + " blocks", fe.missingReferenceBlocks() <= 8);
        assertTrue("main stream: voice should stand out", outRatio > 20);
        assertTrue("main stream: voice recognisable", corr > 0.75);
        assertTrue("alt stream: voice should stand out", linRatio > 15);
        assertTrue("alt stream: voice preserved", linCorr > 0.95);
    }

    @Test public void noEchoCancellationWithEarbuds() {
        FrontEnd fe = new FrontEnd(new ReferenceBuffer(16000));
        float[] mic = speechLike(256, 81, 0.05), out = new float[256], alt = new float[256];
        fe.process(mic, 0, 0, out, alt);
        assertTrue(fe.aecBlocks() == 0);
        for (int i = 0; i < 256; i++) assertTrue("alt is the raw mic with earbuds", alt[i] == mic[i]);
    }
}
