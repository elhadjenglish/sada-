package app.sada.poc;

/**
 * Watches a question microphone WHILE recording, and decides what to do if it is lost.
 *
 * When Bluetooth earbuds disconnect, Android usually keeps the recorder open and delivers digital silence (zeros),
 * so reading never fails. Loss is therefore judged from the audio itself, confirmed by the route:
 *   no audio (digital silence) for 0.4 s while Android no longer reports the earbud route → lost
 *   no audio for 2 s even if Android still reports the route (late route updates)        → lost
 * A route change alone never counts as a loss while audio is still flowing.
 */
public final class CaptureHealth {
    public static final double DEAD_DB = -90;          // digital silence (a disconnected link delivers zeros)
    public static final int DEAD_WITH_ROUTE_LOSS = 25; // 0.4 s of 16 ms blocks
    public static final int DEAD_ALONE = 125;          // 2 s

    private int dead = 0;
    private String reason = null;

    /** One 16 ms block: its level, and whether Android still reports the earbud route. Returns true once lost. */
    public boolean push(double blockDb, boolean routeOk) {
        if (reason != null) return true;
        if (blockDb < DEAD_DB) dead++; else dead = 0;
        if (!routeOk && dead >= DEAD_WITH_ROUTE_LOSS) reason = "earbud route lost and no audio for 0.4 s";
        else if (dead >= DEAD_ALONE) reason = "no audio for 2 s";
        return reason != null;
    }

    public boolean lost() { return reason != null; }
    public String reason() { return reason; }

    // ---------------- after a loss ----------------

    public enum AfterLoss { KEEP_AS_QUESTION, RESTART_ON_PHONE }

    /**
     * heardSpeech/speechMs: what was recorded before the loss; msSinceSpeech: silence between the last speech and the
     * loss. If the user had clearly finished (real speech, then a pause), the recording IS the question: keep it.
     * Otherwise start the question again on the phone microphone (never mix two microphones in one question).
     */
    public static AfterLoss afterLoss(boolean heardSpeech, long speechMs, long msSinceSpeech) {
        return heardSpeech && speechMs >= 700 && msSinceSpeech >= 500 ? AfterLoss.KEEP_AS_QUESTION : AfterLoss.RESTART_ON_PHONE;
    }

    /** A recording is a usable question only with detected speech, at a real level, of a minimum length. */
    public static boolean isUsable(boolean heardSpeech, double peak, long speechMs) {
        return heardSpeech && peak >= 0.003 && speechMs >= 300;
    }
}
