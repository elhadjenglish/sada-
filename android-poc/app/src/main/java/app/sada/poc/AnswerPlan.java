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

    public static final String RESUME_HINT = "Say continue, or double-press volume up, to keep listening.";

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
            case EMPTY_RECORDING: return "I didn't hear a question. Double-press volume up to ask again.";
            case NOT_UNDERSTOOD: return "Sorry, I couldn't understand the question. Double-press volume up to ask again.";
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
