package app.sada.poc;

/**
 * What Sada's media session reports to Android in each state.
 *
 * Android delivers volume-key presses (screen off / app in background) only to a media session whose playback state
 * is ACTIVE: MediaSessionStack.getDefaultVolumeSession() requires checkPlaybackActiveState(true) && canHandleVolumeKey().
 * A PAUSED session is skipped, so the keys never reach Sada. While Sada is waiting for the user (paused for a question
 * or for "Continue"), it therefore reports WAITING (an active state, mapped to STATE_BUFFERING with speed 0): the
 * podcast stays paused, but the Volume Up double press keeps reaching Sada with the screen locked.
 */
public final class SessionState {
    public enum Reported { PLAYING, WAITING, PAUSED }

    public static Reported forPhase(PocStateMachine.Phase phase, boolean podcastPlaying) {
        switch (phase) {
            case LISTENING: return podcastPlaying ? Reported.PLAYING : Reported.PAUSED; // e.g. paused by a phone call
            case WAIT_CONTINUE:                                                        // double press = resume
            case ASKING: return Reported.WAITING;                                      // double press = finish question
            default: return Reported.PAUSED; // getting ready / answering (presses ignored) / stopped
        }
    }

    /** True if Android will route volume keys to the session in this state (the active-state rule above). */
    public static boolean receivesVolumeKeys(Reported r) { return r != Reported.PAUSED; }
}
