package app.sada.poc;

import static app.sada.poc.PocStateMachine.Phase;
import static app.sada.poc.PocStateMachine.StepKind.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Regression tests for "double press of Volume Up does not resume while the screen is locked".
 * Android only routes locked-screen volume keys to an ACTIVE media session, so the session state Sada reports is
 * the precondition (SessionState); the gesture → state machine → resume path is the same for both key routes.
 */
public class LockedScreenResumeTest {
    /** The key route a press arrives by. Android decides which one is used; Sada's handling must be identical. */
    enum Route { FOREGROUND_ACTIVITY, LOCKED_MEDIA_SESSION }

    /** Mirrors SadaService: presses → VolumeGesture → (if double) PocStateMachine.onVolumeDouble(). */
    static final class Harness {
        final PocStateMachine m = new PocStateMachine();
        final VolumeGesture g = new VolumeGesture();
        boolean podcastPlaying = true;
        final List<Object> log = new ArrayList<>();

        Harness() { m.start(); }

        /** True if a press via this route actually reaches Sada in the current state. */
        boolean delivered(Route r) {
            if (r == Route.FOREGROUND_ACTIVITY) return true; // the Activity reads keys directly
            return SessionState.receivesVolumeKeys(SessionState.forPhase(m.getPhase(), podcastPlaying));
        }

        /** A double press at time t via route r; returns the steps run, or null if Android did not deliver it. */
        List<PocStateMachine.StepKind> doublePress(Route r, long t) {
            if (!delivered(r)) return null;
            g.press(t); g.press(t + 250);
            VolumeGesture.Decision d = g.poll(t + 450);
            assertTrue("recognised as a double press", d != null && d.isDouble);
            List<PocStateMachine.StepKind> steps = m.onVolumeDouble();
            if (steps.contains(PAUSE_PODCAST)) podcastPlaying = false;
            if (steps.contains(RESUME)) podcastPlaying = true;
            return steps;
        }

        /** Question asked and answered: Sada now waits for "Continue". */
        void finishQuestion() { m.onInputReady(); m.onQuestionDone(); m.onAnswerDone(); }
    }

    @Test public void sessionReportsActiveWhileWaitingForContinue() {
        assertEquals(SessionState.Reported.WAITING, SessionState.forPhase(Phase.WAIT_CONTINUE, false));
        assertTrue("Android must keep sending locked-screen volume keys to Sada",
            SessionState.receivesVolumeKeys(SessionState.forPhase(Phase.WAIT_CONTINUE, false)));
    }

    @Test public void lockedMediaSessionDoublePressInWaitContinueResumes() {
        Harness h = new Harness();
        assertEquals(Arrays.asList(WAKE_DETECTED, PAUSE_PODCAST, PREPARE_QUESTION_INPUT), h.doublePress(Route.LOCKED_MEDIA_SESSION, 0));
        h.finishQuestion();
        assertEquals(Phase.WAIT_CONTINUE, h.m.getPhase());
        List<PocStateMachine.StepKind> steps = h.doublePress(Route.LOCKED_MEDIA_SESSION, 5000);
        assertTrue("the press must be delivered while locked (this failed before the fix)", steps != null);
        assertEquals(Arrays.asList(CONTINUE_DETECTED, RESUME), steps);
        assertEquals(Phase.LISTENING, h.m.getPhase());
        assertTrue(h.podcastPlaying);
    }

    @Test public void lockedMediaSessionDoublePressInListeningOpensQuestionMode() {
        Harness h = new Harness();
        assertEquals(SessionState.Reported.PLAYING, SessionState.forPhase(Phase.LISTENING, true));
        assertEquals(Arrays.asList(WAKE_DETECTED, PAUSE_PODCAST, PREPARE_QUESTION_INPUT), h.doublePress(Route.LOCKED_MEDIA_SESSION, 0));
        assertEquals(Phase.ARMING, h.m.getPhase());
    }

    @Test public void foregroundDoublePressInWaitContinueResumes() {
        Harness h = new Harness();
        h.doublePress(Route.FOREGROUND_ACTIVITY, 0);
        h.finishQuestion();
        assertEquals(Arrays.asList(CONTINUE_DETECTED, RESUME), h.doublePress(Route.FOREGROUND_ACTIVITY, 5000));
        assertEquals(Phase.LISTENING, h.m.getPhase());
    }

    @Test public void foregroundDoublePressInListeningOpensQuestionMode() {
        Harness h = new Harness();
        assertEquals(Arrays.asList(WAKE_DETECTED, PAUSE_PODCAST, PREPARE_QUESTION_INPUT), h.doublePress(Route.FOREGROUND_ACTIVITY, 0));
    }

    @Test public void voiceContinueInWaitContinueStillResumes() {
        Harness h = new Harness();
        h.doublePress(Route.LOCKED_MEDIA_SESSION, 0);
        h.finishQuestion();
        assertEquals(Arrays.asList(CONTINUE_DETECTED, RESUME), h.m.onKeyword("CONTINUE"));
        assertEquals(Phase.LISTENING, h.m.getPhase());
    }

    @Test public void lockedDoublePressWhileAskingFinishesTheQuestion() {
        Harness h = new Harness();
        h.doublePress(Route.LOCKED_MEDIA_SESSION, 0);
        h.m.onInputReady();
        assertEquals(Arrays.asList(STOP_CAPTURE), h.doublePress(Route.LOCKED_MEDIA_SESSION, 3000));
    }

    @Test public void whileAnsweringKeysStayWithAndroidAndPressesAreIgnored() {
        Harness h = new Harness();
        h.doublePress(Route.FOREGROUND_ACTIVITY, 0);
        h.m.onInputReady(); h.m.onQuestionDone();
        assertFalse("locked volume keys adjust the spoken answer's volume, not Sada", h.delivered(Route.LOCKED_MEDIA_SESSION));
        assertEquals("on screen, a double press while answering is ignored", Arrays.asList(IGNORED_BUSY), h.doublePress(Route.FOREGROUND_ACTIVITY, 3000));
        assertEquals(Phase.ANSWERING, h.m.getPhase());
    }

    @Test public void repeatedDoublePressesNeverOpenTwoQuestionSessions() {
        Harness h = new Harness();
        h.doublePress(Route.LOCKED_MEDIA_SESSION, 0);
        assertEquals(Arrays.asList(IGNORED_BUSY), h.m.onVolumeDouble()); // second gesture while getting ready
        assertEquals(Phase.ARMING, h.m.getPhase());
    }

    @Test public void resumeTwiceIsHarmless() {
        Harness h = new Harness();
        h.doublePress(Route.LOCKED_MEDIA_SESSION, 0);
        h.finishQuestion();
        h.doublePress(Route.LOCKED_MEDIA_SESSION, 5000);           // resumes
        List<PocStateMachine.StepKind> again = h.doublePress(Route.LOCKED_MEDIA_SESSION, 8000);
        assertEquals("a second double press while playing opens a new question, it does not resume twice",
            Arrays.asList(WAKE_DETECTED, PAUSE_PODCAST, PREPARE_QUESTION_INPUT), again);
    }

    @Test public void pausedByPhoneCallDoesNotClaimVolumeKeys() {
        // LISTENING but not playing (audio focus lost): the session must not keep the keys from the call / other app
        assertEquals(SessionState.Reported.PAUSED, SessionState.forPhase(Phase.LISTENING, false));
    }
}
