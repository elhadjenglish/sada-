package app.sada.poc;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Stage 0 interaction flow, free of Android classes so it is unit-tested on the build machine.
 *
 *   LISTENING ──"Hey Sada"──▶ ARMING (podcast paused, question microphone getting ready)
 *       ▲                         │ input ready
 *       │                         ▼
 *       │                      ASKING (capturing the question until the user stops talking)
 *       │                         │ question finished (or nothing heard)
 *       │                         ▼
 *       │                      ANSWERING (replaying what was heard, then "say continue")
 *       │                         │ done
 *       │                         ▼
 *       └──────"Continue"──── WAIT_CONTINUE
 */
public final class PocStateMachine {
    public enum Phase { STOPPED, LISTENING, ARMING, ASKING, ANSWERING, WAIT_CONTINUE }

    public enum StepKind {
        WAKE_DETECTED,            // "Hey Sada" while the podcast plays
        PAUSE_PODCAST,            // pause playback now (exact position kept)
        PREPARE_QUESTION_INPUT,   // switch to the best microphone for the question
        START_CAPTURE,            // start recording the question
        STOP_CAPTURE,             // stop recording (user tapped)
        ANSWER,                   // replay what was heard (Stage 0 has no AI answer)
        LISTEN_FOR_CONTINUE,      // answer finished; wait for "Continue"
        CONTINUE_DETECTED,
        RESUME,                   // resume at the saved position
        CONTINUE_WHILE_PLAYING,   // "Continue" heard while playing: a false trigger by definition
        IGNORED_BUSY,             // keyword heard while preparing, asking or answering: ignored
        WAKE_WHILE_PAUSED,        // "Hey Sada" while waiting for "Continue"
        MISSED_WAKE,              // screen button: "I said Hey Sada and nothing happened" (diagnostics)
        RECOVERED,                // a step stalled (preparing, asking or answering took too long): back to a safe state
        UNKNOWN_KEYWORD,
    }

    private Phase phase = Phase.STOPPED;

    public Phase getPhase() { return phase; }
    public void start() { phase = Phase.LISTENING; }
    public void stop() { phase = Phase.STOPPED; }

    public static boolean isWake(String label) { return label.trim().toUpperCase().startsWith("HEY_SADA"); }
    public static boolean isContinue(String label) { return label.trim().toUpperCase().startsWith("CONTINUE"); }

    public List<StepKind> onKeyword(String label) {
        boolean cont = isContinue(label);
        if (!cont) return list(StepKind.UNKNOWN_KEYWORD); // "Hey Sada" no longer activates: Volume Up only
        boolean wake = false;
        switch (phase) {
            case STOPPED: return Collections.emptyList();
            case LISTENING:
                if (wake) { phase = Phase.ARMING; return list(StepKind.WAKE_DETECTED, StepKind.PAUSE_PODCAST, StepKind.PREPARE_QUESTION_INPUT); }
                return list(StepKind.CONTINUE_WHILE_PLAYING);
            case WAIT_CONTINUE:
                if (cont) { phase = Phase.LISTENING; return list(StepKind.CONTINUE_DETECTED, StepKind.RESUME); }
                return list(StepKind.WAKE_WHILE_PAUSED);
            default: return list(StepKind.IGNORED_BUSY);
        }
    }

    public List<StepKind> onInputReady() {
        if (phase != Phase.ARMING) return Collections.emptyList();
        phase = Phase.ASKING;
        return list(StepKind.START_CAPTURE);
    }

    public List<StepKind> onQuestionDone() {
        if (phase != Phase.ASKING) return Collections.emptyList();
        phase = Phase.ANSWERING;
        return list(StepKind.ANSWER);
    }

    private boolean retryOffered = false;

    /** After "I didn't hear a question": Volume Down may retry (only then). */
    public void offerRetry() { if (phase == Phase.WAIT_CONTINUE) retryOffered = true; }

    public List<StepKind> onAnswerDone() {
        retryOffered = false;
        if (phase != Phase.ANSWERING) return Collections.emptyList();
        phase = Phase.WAIT_CONTINUE;
        return list(StepKind.LISTEN_FOR_CONTINUE);
    }

    /**
     * Watchdog: if preparing, asking or answering has stalled, move to waiting for "Continue" so the detector is
     * listening again. Never leaves the user without a way to resume.
     */
    public List<StepKind> recover() {
        if (phase != Phase.ARMING && phase != Phase.ASKING && phase != Phase.ANSWERING) return Collections.emptyList();
        phase = Phase.WAIT_CONTINUE;
        return list(StepKind.RECOVERED, StepKind.LISTEN_FOR_CONTINUE);
    }

    /**
     * Activation without the voice (earbud double-tap, notification or app button): the same assistant flow as
     * "Hey Sada". While waiting for "Continue" it asks another question.
     */
    public List<StepKind> onActivate() {
        switch (phase) {
            case LISTENING: phase = Phase.ARMING; return list(StepKind.WAKE_DETECTED, StepKind.PAUSE_PODCAST, StepKind.PREPARE_QUESTION_INPUT);
            case WAIT_CONTINUE: phase = Phase.ARMING; return list(StepKind.PREPARE_QUESTION_INPUT);
            case STOPPED: return Collections.emptyList();
            default: return list(StepKind.IGNORED_BUSY);
        }
    }

    /** Play/pause (earbud single tap, notification): ordinary podcast control; ends a question early. */
    public List<StepKind> onTogglePlayback() {
        switch (phase) {
            case LISTENING: phase = Phase.WAIT_CONTINUE; return list(StepKind.PAUSE_PODCAST, StepKind.LISTEN_FOR_CONTINUE);
            case WAIT_CONTINUE: phase = Phase.LISTENING; return list(StepKind.CONTINUE_DETECTED, StepKind.RESUME);
            case ASKING: return list(StepKind.STOP_CAPTURE);
            default: return Collections.emptyList();
        }
    }

    /**
     * Volume Up double press, the primary hands-free control: while listening it opens Question Mode; while paused it
     * resumes; while a question is being recorded it ends it; while Sada is getting ready or answering it is ignored
     * (no duplicate sessions).
     */
    public List<StepKind> onVolumeDouble() {
        switch (phase) {
            case LISTENING: return onActivate();
            case WAIT_CONTINUE: phase = Phase.LISTENING; return list(StepKind.CONTINUE_DETECTED, StepKind.RESUME);
            case ASKING: return list(StepKind.STOP_CAPTURE);
            case ARMING: case ANSWERING: return list(StepKind.IGNORED_BUSY);
            default: return Collections.emptyList();
        }
    }

    /**
     * Volume Down double press = ask (again): a new question right after an answer or after "no question heard"
     * (podcast stays paused), or while the podcast plays. Never interrupts a question being recorded, never starts a
     * second question while one is being prepared or answered.
     */
    public List<StepKind> onVolumeDownDouble() {
        switch (phase) {
            case WAIT_CONTINUE:
                if (!retryOffered) return Collections.emptyList(); // only after a no-speech timeout
                retryOffered = false;
                return onActivate();
            case ARMING: case ASKING: case ANSWERING: return list(StepKind.IGNORED_BUSY);
            default: return Collections.emptyList();
        }
    }

    /** Cancel while the microphone is recording a question: back to listening; the podcast resumes. */
    public List<StepKind> onCancel() {
        if (phase != Phase.ASKING) return Collections.emptyList();
        phase = Phase.LISTENING;
        return list(StepKind.RESUME);
    }

    /** Screen button "I said it but nothing happened" (diagnostics only). */
    public List<StepKind> onMarkMiss() {
        return phase == Phase.LISTENING ? list(StepKind.MISSED_WAKE) : Collections.<StepKind>emptyList();
    }

    private static List<StepKind> list(StepKind... s) { return Arrays.asList(s); }
}
