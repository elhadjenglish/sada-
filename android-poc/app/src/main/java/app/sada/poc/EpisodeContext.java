package app.sada.poc;

import java.util.ArrayList;
import java.util.List;

/**
 * Podcast context for a question, from facts Sada really has: which test episode was playing (from its rendered
 * start/end times) and the sentences around the pause point. The position inside an episode is estimated from the
 * share of time elapsed, so the text is marked APPROXIMATE. Plain Java, unit-tested.
 */
public final class EpisodeContext {
    public final String title;   // "" if unknown
    public final String recent;  // "" if no transcript is available

    private EpisodeContext(String title, String recent) { this.title = title; this.recent = recent; }

    public static EpisodeContext none(String title) { return new EpisodeContext(title == null ? "" : title, ""); }

    /**
     * Context for the episode that is playing (text supplied by the app): the title and the sentences around the
     * pause point, estimated from how far through the narration it is (by character count), so APPROXIMATE.
     */
    public static EpisodeContext of(String title, long positionMs, long durationMs, String text) {
        String t = title == null ? "" : title;
        List<String> ss = sentences(text == null ? "" : text);
        if (ss.isEmpty() || durationMs <= 0) return none(t);
        double frac = Math.min(1.0, Math.max(0.0, positionMs / (double) durationMs));
        int total = 0;
        for (String s : ss) total += s.length() + 1;
        int acc = 0, i = ss.size() - 1;
        for (int k = 0; k < ss.size(); k++) {
            acc += ss.get(k).length() + 1;
            if (frac * total < acc) { i = k; break; }
        }
        StringBuilder sb = new StringBuilder();
        for (int k = Math.max(0, i - 2); k <= i; k++) sb.append(sb.length() == 0 ? "" : " ").append(ss.get(k));
        String recent = sb.length() > 780 ? sb.substring(sb.length() - 780) : sb.toString();
        return new EpisodeContext(t, recent);
    }

    /** Splits an episode script into sentences. */
    static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        for (String s : text.trim().split("(?<=[.!?])\\s+")) if (!s.trim().isEmpty()) out.add(s.trim());
        return out;
    }

    /**
     * startMs/endMs: each episode's span in the rendered test file; texts: the episode scripts (same order).
     * Returns the episode title and the sentences around the pause point (the one estimated there and the two before).
     */
    public static EpisodeContext at(long positionMs, long[] startMs, long[] endMs, List<String> texts) {
        for (int e = 0; e < startMs.length && e < texts.size(); e++) {
            if (positionMs < startMs[e] || positionMs > endMs[e] + 1500) continue; // +1.5 s gap after an episode
            List<String> ss = sentences(texts.get(e));
            if (ss.isEmpty()) return none("Sada test episode " + (e + 1));
            double frac = Math.min(1.0, Math.max(0.0, (positionMs - startMs[e]) / (double) Math.max(1, endMs[e] - startMs[e])));
            int i = Math.min(ss.size() - 1, (int) Math.floor(frac * ss.size()));
            StringBuilder sb = new StringBuilder();
            for (int k = Math.max(0, i - 2); k <= i; k++) sb.append(sb.length() == 0 ? "" : " ").append(ss.get(k));
            String recent = sb.length() > 780 ? sb.substring(sb.length() - 780) : sb.toString();
            return new EpisodeContext("Sada test episode " + (e + 1), recent);
        }
        return none("Sada test episode");
    }
}
