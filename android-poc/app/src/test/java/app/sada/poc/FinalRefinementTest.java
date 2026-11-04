package app.sada.poc;

import static app.sada.poc.PocStateMachine.Phase;
import static app.sada.poc.PocStateMachine.StepKind.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.sada.poc.dsp.Agc;
import app.sada.poc.dsp.Endpointer;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.Random;

public class FinalRefinementTest {
    static final int B = 256, FS = 16000;
    static float[] noise(int n, double rms, long seed) { Random r = new Random(seed); float[] x = new float[n]; for (int i = 0; i < n; i++) x[i] = (float) (r.nextGaussian() * rms); return x; }
    static float[] tone(int n, long seed) { Random r = new Random(seed); float[] x = new float[n]; for (int i = 0; i < n; i++) x[i] = (float) (0.05 * Math.sin(2 * Math.PI * 180 * i / FS) + 0.005 * r.nextGaussian()); return x; }
    static float[] cat(float[]... p) { int n = 0; for (float[] a : p) n += a.length; float[] y = new float[n]; int o = 0; for (float[] a : p) { System.arraycopy(a, 0, y, o, a.length); o += a.length; } return y; }

    /** Runs the question endpointer as the app does; returns {reason, seconds when it decided}. */
    static Object[] run(float[] x) {
        Endpointer e = new Endpointer(AnswerPlan.NO_SPEECH_MS, AnswerPlan.MAX_QUESTION_MS);
        float[] b = new float[B];
        for (int p = 0; p + B <= x.length; p += B) {
            System.arraycopy(x, p, b, 0, B);
            if (e.process(b, B) == Endpointer.State.DONE) return new Object[]{e.reason(), (p + B) / (double) FS};
        }
        return new Object[]{Endpointer.Reason.NONE, x.length / (double) FS};
    }

    // ---- no-speech timeout ----

    @Test public void silenceAfterTheCueEndsAfterAbout4point5Seconds() {
        Object[] r = run(noise(10 * FS, 0.001, 1));
        System.out.printf("    no speech: gave up after %.2f s%n", (double) r[1]);
        assertEquals(Endpointer.Reason.NO_SPEECH, r[0]);
        assertEquals(4.5, (double) r[1], 0.1);
    }

    @Test public void speechStartingAt4SecondsIsStillCaptured() {
        Object[] r = run(cat(noise(4 * FS, 0.001, 2), tone(2 * FS, 3), noise(3 * FS, 0.001, 4)));
        assertEquals(Endpointer.Reason.END_OF_SPEECH, r[0]);
    }

    @Test public void shortPausesAfterSpeechStartedDoNotEndTheQuestion() {
        // 1.5 s speech, 1.0 s pause, 1.5 s speech: one question, ends ~1.4 s after the last word
        Object[] r = run(cat(noise(FS / 2, 0.001, 5), tone(FS * 3 / 2, 6), noise(FS, 0.001, 7), tone(FS * 3 / 2, 8), noise(4 * FS, 0.001, 9)));
        assertEquals(Endpointer.Reason.END_OF_SPEECH, r[0]);
        assertEquals("ended about 1.4 s after the last word (4.5 s)", 5.9, (double) r[1], 0.15);
    }

    @Test public void noQuestionMessageIsSaidOnceAndTellsHowToContinue() {
        assertTrue(AnswerPlan.message(AnswerPlan.Failure.EMPTY_RECORDING).startsWith("I didn't hear a question."));
        assertFalse("no second message", AnswerPlan.needsResumeHint(AnswerPlan.Failure.EMPTY_RECORDING));
        assertTrue(AnswerPlan.needsResumeHint(AnswerPlan.Failure.OFFLINE));
    }

    @Test public void afterNoQuestionThePodcastStaysPausedUntilContinue() {
        PocStateMachine m = new PocStateMachine(); m.start();
        m.onVolumeDouble(); m.onInputReady();
        assertEquals(Arrays.asList(ANSWER), m.onQuestionDone());          // empty question → the "didn't hear" message
        assertEquals(Arrays.asList(LISTEN_FOR_CONTINUE), m.onAnswerDone()); // no RESUME step: podcast stays paused
        assertEquals(Phase.WAIT_CONTINUE, m.getPhase());
        assertEquals("nothing resumes on its own", Collections.emptyList(), m.recover());
        assertEquals(Phase.WAIT_CONTINUE, m.getPhase());
    }

    @Test public void continueByVoiceOrDoublePressResumes() {
        for (int how = 0; how < 2; how++) {
            PocStateMachine m = new PocStateMachine(); m.start();
            m.onVolumeDouble(); m.onInputReady(); m.onQuestionDone(); m.onAnswerDone();
            assertEquals(Arrays.asList(CONTINUE_DETECTED, RESUME), how == 0 ? m.onKeyword("CONTINUE") : m.onVolumeDouble());
            assertEquals(Phase.LISTENING, m.getPhase());
        }
    }

    // ---- wake word: starting gain ----

    @Test public void newSessionStartsFromTheLearnedGainWithinACap() {
        assertEquals(0, AnswerPlan.startGainDb(Double.NaN), 0);
        assertEquals(0, AnswerPlan.startGainDb(-3), 0);
        assertEquals(7.5, AnswerPlan.startGainDb(7.5), 0);
        assertEquals("never more than the cap (podcast false triggers)", AnswerPlan.MAX_START_GAIN_DB, AnswerPlan.startGainDb(24), 0);
    }

    @Test public void startingGainStillAdaptsDownForLoudInput() {
        Agc a = new Agc();
        a.setGainDb(10);
        float[] quiet = noise(FS / 2, 0.001, 11), loud = tone(FS, 10), o = new float[B], in = new float[B];
        for (int i = 0; i + B <= quiet.length; i += B) { System.arraycopy(quiet, i, in, 0, B); a.process(in, o, B); } // room background first
        for (int i = 0; i < FS / 8; i += B) {
            System.arraycopy(loud, i, in, 0, B);
            for (int k = 0; k < B; k++) in[k] *= 6; // about -10 dBFS speech: above the target
            a.process(in, o, B);
        }
        assertTrue("gain came down from 10 dB: " + a.gainDb(), a.gainDb() < 10);
    }

    // ---- timing report ----

    @Test public void timingLineNamesEveryStage() {
        String t = AnswerPlan.timingLine(6200, 1400, 40, 0, 1300, 3100, 360);
        assertTrue(t.contains("first word 6200 ms") && t.contains("transcription 1300") && t.contains("Muse Spark 3100") && t.contains("call-mode release 0"));
        assertFalse("phone mic: no release stage", AnswerPlan.timingLine(1, 1, 1, -1, 1, 1, 1).contains("release"));
    }
}
