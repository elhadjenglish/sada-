package app.sada.poc;

import java.util.ArrayList;
import java.util.List;

/**
 * What Sada says after a question: the AI answer in the chosen language(s), or a natural message when something failed.
 * Plain Java (no Android), unit-tested.
 */
public final class AnswerPlan {
    /** One spoken line and the voice language to speak it in ("en" or "ar"). */
    public static final class Line {
        public final String text, lang;
        Line(String text, String lang) { this.text = text; this.lang = lang; }
        @Override public String toString() { return lang + ": " + text; }
    }

    /** Said after an answer (approved wording; does not offer another question, to keep it short). */
    public static final String RESUME_HINT = "To keep listening, say Continue or double-press Volume Up.";

    /** The answer lines for the selected language: "en", "ar", or "both" (English first, then Arabic). */
    public static List<Line> answer(String lang, String answerEn, String answerAr) {
        List<Line> out = new ArrayList<>();
        String en = answerEn == null ? "" : answerEn.trim(), ar = answerAr == null ? "" : answerAr.trim();
        boolean wantEn = !"ar".equals(lang), wantAr = !"en".equals(lang);
        if (wantEn && !en.isEmpty()) out.add(new Line(en, "en"));
        if (wantAr && !ar.isEmpty()) out.add(new Line(ar, "ar"));
        if (out.isEmpty()) { // the chosen language came back empty: use whatever exists rather than say nothing
            if (!en.isEmpty()) out.add(new Line(en, "en"));
            else if (!ar.isEmpty()) out.add(new Line(ar, "ar"));
        }
        return out;
    }

    /** A transcription worth sending to the AI: real words, not empty or a lone filler sound. */
    public static boolean usableTranscript(String t) {
        if (t == null) return false;
        String s = t.trim().replaceAll("[\\p{Punct}\\s]+", " ").trim().toLowerCase();
        if (s.isEmpty()) return false;
        if (s.matches("(uh|um|hmm|mm|ah|eh|oh)( (uh|um|hmm|mm|ah|eh|oh))*")) return false;
        return s.replace(" ", "").length() >= 2;
    }

    public enum Failure { EMPTY_RECORDING, NOT_UNDERSTOOD, NO_SERVER, OFFLINE, TIMEOUT, SERVER_SETUP, BUSY, AI_ERROR, BAD_REQUEST }

    /** Whether to add "Or say continue to keep listening." (the no-question message already says it: say it once). */
    public static boolean needsResumeHint(Failure f) { return f != Failure.EMPTY_RECORDING; }

    /** How long Sada waits for the user to START speaking after "Go ahead" (not the end-of-speech silence). */
    public static final int NO_SPEECH_MS = 4500;
    /** Longest question, counted from the first word. */
    public static final int MAX_QUESTION_MS = 20000;
    /** Highest gain a new session may start with (learned in an earlier session); limits podcast-driven false triggers. */
    public static final double MAX_START_GAIN_DB = 10;

    /** Starting gain for a new session from the gain learned before (never negative, never above the cap). */
    public static double startGainDb(double learnedDb) {
        if (Double.isNaN(learnedDb) || learnedDb < 0) return 0;
        return Math.min(MAX_START_GAIN_DB, learnedDb);
    }

    /**
     * Where the time went between the end of the question and Sada's first spoken word (ms; -1 = not measured).
     * endWait: silence the end-of-speech detector waits for; release: Bluetooth call-mode release that was NOT hidden
     * behind transcription (0 when it ran in parallel); asr, ai: server calls; voice: from answer received to first word.
     */
    public static String timingLine(long total, long endWait, long finalize, long release, long asr, long ai, long voice) {
        return "end of question → first word " + total + " ms = end-of-speech wait " + endWait + " + finalize " + finalize +
            (release >= 0 ? " + call-mode release " + release : "") + " + transcription " + asr + " + Muse Spark " + ai + " + voice start " + voice;
    }

    /** HTTP status from the Sada server → kind of failure (0 = no response at all). */
    public static Failure fromStatus(int status, String body) {
        if (status == 0) return Failure.OFFLINE;
        if (status == 429) return Failure.BUSY;
        if (status == 500 && body != null && body.contains("MODEL_API_KEY")) return Failure.SERVER_SETUP;
        if (status == 403 || status == 404 || status == 405) return Failure.SERVER_SETUP;
        if (status == 400 || status == 413 || status == 415) return Failure.BAD_REQUEST;
        if (status == 504) return Failure.TIMEOUT;
        return Failure.AI_ERROR;
    }

    /** What Sada says (never pretends an answer was generated). */
    public static String message(Failure f) {
        switch (f) {
            case EMPTY_RECORDING: return "I didn't hear a question. To try again, double-press Volume Down. To keep listening, say Continue or double-press Volume Up.";
            case NOT_UNDERSTOOD: return "Sorry, I couldn't understand the question.";
            case NO_SERVER: return "Sada's server address isn't set yet. Add it in the app settings.";
            case OFFLINE: return "I can't reach the internet right now, so I can't answer. Please check your connection and ask again.";
            case TIMEOUT: return "The answer is taking too long. Please ask again in a moment.";
            case SERVER_SETUP: return "Sada's server isn't set up correctly, so I can't answer right now.";
            case BUSY: return "Too many questions in a short time. Please wait a minute and ask again.";
            case BAD_REQUEST: return "Sorry, something went wrong with that question. Please ask again.";
            default: return "Sorry, I couldn't get an answer this time. Please ask again.";
        }
    }
}
