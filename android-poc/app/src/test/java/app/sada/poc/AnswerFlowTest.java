package app.sada.poc;

import static app.sada.poc.AnswerPlan.Failure.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.sada.poc.dsp.Endpointer;
import org.junit.Test;
import java.util.Arrays;
import java.util.Random;

public class AnswerFlowTest {
    // ---- spoken answer in the selected language ----

    @Test public void bothLanguagesEnglishThenArabic() {
        assertEquals("[en: Reliable means trustworthy., ar: موثوق.]", AnswerPlan.answer("both", "Reliable means trustworthy.", "موثوق.").toString());
    }

    @Test public void singleLanguageOnly() {
        assertEquals("[ar: موثوق.]", AnswerPlan.answer("ar", "Reliable means trustworthy.", "موثوق.").toString());
        assertEquals("[en: Reliable means trustworthy.]", AnswerPlan.answer("en", "Reliable means trustworthy.", "موثوق.").toString());
    }

    @Test public void emptyChosenLanguageFallsBackToTheOther() {
        assertEquals("[en: Reliable means trustworthy.]", AnswerPlan.answer("ar", "Reliable means trustworthy.", "").toString());
        assertTrue(AnswerPlan.answer("both", "", "").isEmpty());
    }

    // ---- transcription and failures ----

    @Test public void emptyOrFillerTranscriptsAreNotSent() {
        assertFalse(AnswerPlan.usableTranscript(""));
        assertFalse(AnswerPlan.usableTranscript("  . "));
        assertFalse(AnswerPlan.usableTranscript("Um."));
        assertFalse(AnswerPlan.usableTranscript("uh um"));
        assertTrue(AnswerPlan.usableTranscript("What does reliable mean?"));
    }

    @Test public void failuresBecomeHonestMessages() {
        assertEquals(OFFLINE, AnswerPlan.fromStatus(0, null));
        assertEquals(BUSY, AnswerPlan.fromStatus(429, "{}"));
        assertEquals(SERVER_SETUP, AnswerPlan.fromStatus(500, "{\"error\":\"The server has no MODEL_API_KEY yet\"}"));
        assertEquals(SERVER_SETUP, AnswerPlan.fromStatus(403, "{}"));
        assertEquals(TIMEOUT, AnswerPlan.fromStatus(504, "{}"));
        assertEquals(AI_ERROR, AnswerPlan.fromStatus(502, "{}"));
        for (AnswerPlan.Failure f : AnswerPlan.Failure.values()) {
            String m = AnswerPlan.message(f).toLowerCase();
            assertFalse("never claims an answer: " + m, m.contains("here is") || m.contains("the answer is:"));
        }
    }

    // ---- podcast context: real facts only ----

    @Test public void contextPicksTheEpisodeAndNearbySentences() {
        long[] start = {0, 61500}, end = {60000, 121500};
        EpisodeContext c = EpisodeContext.at(110000, start, end, Arrays.asList("A one. A two.", "B one. B two. B three. B four. B five."));
        assertEquals("Sada test episode 2", c.title);
        assertTrue(c.recent, c.recent.endsWith("B five.") && c.recent.contains("B four."));
    }

    @Test public void unknownPositionGivesNoInventedText() {
        EpisodeContext c = EpisodeContext.at(999999, new long[]{0}, new long[]{1000}, Arrays.asList("A."));
        assertEquals("", c.recent);
    }

    // ---- automatic end of speech ----

    static final int B = 256, FS = 16000;
    static float[] noise(int n, double rms, long seed) { Random r = new Random(seed); float[] x = new float[n]; for (int i = 0; i < n; i++) x[i] = (float) (r.nextGaussian() * rms); return x; }
    static float[] tone(int n, long seed) { Random r = new Random(seed); float[] x = new float[n]; for (int i = 0; i < n; i++) x[i] = (float) (0.05 * Math.sin(2 * Math.PI * 180 * i / FS) + 0.005 * r.nextGaussian()); return x; }
    static float[] cat(float[]... p) { int n = 0; for (float[] a : p) n += a.length; float[] y = new float[n]; int o = 0; for (float[] a : p) { System.arraycopy(a, 0, y, o, a.length); o += a.length; } return y; }
    static Endpointer run(float[] x, Endpointer e) {
        float[] b = new float[B];
        for (int p = 0; p + B <= x.length && e.state() != Endpointer.State.DONE; p += B) { System.arraycopy(x, p, b, 0, B); e.process(b, B); }
        return e;
    }

    @Test public void thinkingPauseDoesNotEndTheQuestion() {
        // "What does..." (1.5 s) — 1.0 s thinking pause — "...reliable mean?" (1.5 s) — silence
        float[] x = cat(noise(FS, 0.001, 1), tone(FS * 3 / 2, 2), noise(FS, 0.001, 3), tone(FS * 3 / 2, 4), noise(4 * FS, 0.001, 5));
        Endpointer e = run(x, new Endpointer(8000, 20000));
        assertEquals(Endpointer.Reason.END_OF_SPEECH, e.reason());
        double end = e.speechEndBlock() * 0.016;
        System.out.printf("    question with a 1.0 s pause: ended after the second part, speech until %.2f s (true 5.00 s)%n", end);
        assertEquals("both parts kept", 5.0, end, 0.2);
    }

    @Test public void endsAbout1point4SecondsAfterTheLastWord() {
        float[] x = cat(noise(FS, 0.001, 6), tone(2 * FS, 7), noise(5 * FS, 0.001, 8));
        Endpointer e = new Endpointer(8000, 20000);
        float[] b = new float[B]; int doneAt = -1;
        for (int p = 0; p + B <= x.length; p += B) { System.arraycopy(x, p, b, 0, B); if (e.process(b, B) == Endpointer.State.DONE) { doneAt = p; break; } }
        double after = doneAt / (double) FS - 3.0;
        System.out.printf("    recording stopped %.2f s after the last word%n", after);
        assertEquals(1.4, after, 0.1);
    }

    @Test public void silentWaitBeforeSpeakingDoesNotShortenTheQuestion() {
        // 5 s before speaking, then a 10 s question: the 20 s limit counts from the first word
        float[] x = cat(noise(5 * FS, 0.001, 9), tone(10 * FS, 10), noise(3 * FS, 0.001, 11));
        assertEquals(Endpointer.Reason.END_OF_SPEECH, run(x, new Endpointer(8000, 20000)).reason());
    }

    @Test public void endlessNoiseStopsAtTheSafetyLimit() {
        Endpointer e = new Endpointer(8000, 20000);
        e.setNoiseFloorDb(-60);
        assertEquals(Endpointer.Reason.MAX_LENGTH, run(tone(30 * FS, 12), e).reason());
    }
}
