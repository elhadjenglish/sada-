package app.sada.poc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EpisodeContextOfTest {
    static final String TEXT = "First sentence here. Second sentence here. Third sentence here. Fourth sentence here. Fifth sentence here.";

    @Test public void contextFollowsThePlaybackPosition() {
        EpisodeContext early = EpisodeContext.of("My episode", 1000, 100000, TEXT);
        EpisodeContext late = EpisodeContext.of("My episode", 99000, 100000, TEXT);
        assertEquals("My episode", early.title);
        assertTrue(early.recent, early.recent.startsWith("First") && !early.recent.contains("Fifth"));
        assertTrue(late.recent, late.recent.endsWith("Fifth sentence here.") && late.recent.contains("Fourth"));
    }

    @Test public void noTextOrDurationGivesNoInventedContext() {
        assertEquals("", EpisodeContext.of("T", 5, 100, "").recent);
        assertEquals("", EpisodeContext.of("T", 5, 0, TEXT).recent);
        assertEquals("T", EpisodeContext.of("T", 5, 0, TEXT).title);
    }

    @Test public void positionBeyondTheEndIsClamped() {
        assertTrue(EpisodeContext.of("T", 999999, 100, TEXT).recent.endsWith("Fifth sentence here."));
    }
}
