package app.sada.poc;

/**
 * Health of the wake-word pipeline over a time window, from counters the app keeps anyway. Tells apart:
 *   MIC_FAILURE        the microphone delivers (almost) no audio, or only digital silence
 *   DETECTOR_STALLED   audio arrives but the detector is not processing it
 *   OK                 audio and processing are fine (a missed "Hey Sada" is then a no-match, not a failure)
 * Recovery is rate-limited so a persistent problem can never become a CPU/battery loop.
 */
public final class WakeHealth {
    public enum Status { OK, MIC_FAILURE, DETECTOR_STALLED, NOT_CHECKED }

    public static final long MIN_RECOVERY_GAP_MS = 60_000;

    /**
     * windowMs: length of the window; micBlocks: 16 ms blocks delivered by the microphone; micLevelDb: average raw level;
     * detectorChunks: 96 ms chunks the detector processed. listening: wake-word listening was expected the whole window.
     */
    public static Status evaluate(boolean listening, long windowMs, long micBlocks, double micLevelDb, long detectorChunks) {
        if (!listening || windowMs < 5000) return Status.NOT_CHECKED;
        long expectedBlocks = windowMs / 16;
        if (micBlocks < expectedBlocks / 2 || micLevelDb < -85) return Status.MIC_FAILURE;
        long expectedChunks = micBlocks * 256 / 1536;
        if (detectorChunks < expectedChunks / 2) return Status.DETECTOR_STALLED;
        return Status.OK;
    }

    /** At most one recovery per MIN_RECOVERY_GAP_MS. */
    public static boolean mayRecover(long now, long lastRecoveryAt) { return now - lastRecoveryAt >= MIN_RECOVERY_GAP_MS; }
}
