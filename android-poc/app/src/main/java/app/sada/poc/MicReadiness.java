package app.sada.poc;

/**
 * Decides when a question microphone is genuinely ready: Android reporting the route is not enough, the recorder
 * must deliver live audio (Bluetooth call links often deliver digital silence for a while after switching).
 * Fed with the level (dBFS) of each 16 ms block read from the opened recorder. Plain Java, unit-tested.
 */
public final class MicReadiness {
    /** Below this a block is digital silence: the link is not delivering audio yet. */
    public static final double SILENT_DB = -85;
    /** Above this a block is speech-level: the user is already talking. */
    public static final double SPEECH_DB = -45;
    /** Consecutive live blocks required (about 100 ms). */
    public static final int NEED_LIVE = 6;

    private int live = 0, speechBlocks = 0, floorN = 0;
    private double floorSum = 0;

    /** Returns true once the microphone is delivering live audio. */
    public boolean push(double blockDb) {
        if (blockDb > SILENT_DB) live++; else live = 0; // a stutter (silence again) restarts the count
        if (blockDb > SPEECH_DB) speechBlocks++;
        else if (blockDb > SILENT_DB) { floorSum += blockDb; floorN++; }
        return ready();
    }

    public boolean ready() { return live >= NEED_LIVE; }

    /** The user was already talking while the microphone was being prepared (the start of the question is missing). */
    public boolean userAlreadySpeaking() { return speechBlocks >= 3; }

    /** Background level measured while getting ready (NaN if none): seeds the end-of-speech detector. */
    public double backgroundDb() { return floorN == 0 ? Double.NaN : floorSum / floorN; }

    // ---------------- what to do, and what to tell the user ----------------

    public enum Outcome { EARBUD_READY, PHONE, FALLBACK_NOT_CONNECTED, FALLBACK_NO_AUDIO, FALLBACK_DISCONNECTED }

    /**
     * earbudAvailable: a Bluetooth headset microphone is connected and wanted.
     * connectMs: time until Android reported the route, or -1 if it did not within the timeout (or Bluetooth dropped).
     * liveAudio: whether the opened recorder delivered live audio within its timeout (ignored if not connected).
     */
    public static Outcome decide(boolean earbudAvailable, long connectMs, boolean liveAudio) {
        if (!earbudAvailable) return Outcome.PHONE;
        if (connectMs < 0) return Outcome.FALLBACK_NOT_CONNECTED;
        return liveAudio ? Outcome.EARBUD_READY : Outcome.FALLBACK_NO_AUDIO;
    }

    /** The spoken readiness cue. Recording starts only after it has finished. */
    public static String prompt(Outcome o, boolean userAlreadySpeaking) {
        String lead = userAlreadySpeaking ? "Sorry, I missed the start. " : "";
        switch (o) {
            case FALLBACK_NOT_CONNECTED:
            case FALLBACK_NO_AUDIO: return lead + "The earbud microphone isn't ready. Go ahead, I'm listening on the phone.";
            case FALLBACK_DISCONNECTED: return "The earbud microphone disconnected. Go ahead, I'm listening on the phone.";
            default: return lead + "Go ahead, I'm listening.";
        }
    }

    /** Said when no microphone delivers audio at all (the question is then cancelled, never answered empty). */
    public static String noMicPrompt() { return "Sorry, no microphone is working right now."; }

    public static String label(Outcome o) {
        switch (o) {
            case EARBUD_READY: return "earbud mic";
            case FALLBACK_NOT_CONNECTED: return "phone mic (earbud mic did not connect)";
            case FALLBACK_NO_AUDIO: return "phone mic (earbud mic gave no audio)";
            case FALLBACK_DISCONNECTED: return "phone mic (earbud mic disconnected)";
            default: return "phone mic";
        }
    }
}
