package app.sada.poc;

import static app.sada.poc.PocStateMachine.Phase;
import static app.sada.poc.PocStateMachine.StepKind.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;

public class VolumeGestureTest {
    /** Feeds presses at the given times and polls every 10 ms until the burst is decided. */
    static VolumeGesture.Decision run(long... times) {
        VolumeGesture g = new VolumeGesture();
        int i = 0;
        for (long t = times[0]; t < times[times.length - 1] + 2000; t += 10) {
            while (i < times.length && times[i] <= t) g.press(times[i++]);
            VolumeGesture.Decision d = g.poll(t);
            if (d != null && i == times.length) return d;
            if (d != null) throw new AssertionError("decided before all presses at " + t);
        }
        return null;
    }

    @Test public void deliberateDoublePress() {
        VolumeGesture.Decision d = run(0, 250);
        assertTrue(d.isDouble);
        assertEquals("a Sada gesture does not change the volume", 0, d.volumeSteps);
    }

    @Test public void doublePressIsRecognisedQuickly() {
        VolumeGesture g = new VolumeGesture();
        g.press(0); g.press(250);
        assertEquals(null, g.poll(440));
        assertTrue("decided 200 ms after the second press", g.poll(450).isDouble);
    }

    @Test public void singlePressIsANormalVolumeStep() {
        VolumeGesture.Decision d = run(0);
        assertTrue(!d.isDouble);
        assertEquals(1, d.volumeSteps);
    }

    @Test public void heldKeyInAPocketIsNotAGesture() {
        // Android key repeat: first press, ~400 ms, then about every 50 ms
        VolumeGesture.Decision d = run(0, 400, 450, 500, 550, 600);
        assertTrue(!d.isDouble);
        assertEquals(6, d.volumeSteps);
    }

    @Test public void twoVeryFastHitsAreNotAGesture() {
        assertTrue(!run(0, 40).isDouble);       // bounce or a knock
    }

    @Test public void twoSlowPressesAreTwoVolumeSteps() {
        VolumeGesture g = new VolumeGesture();
        g.press(0);
        VolumeGesture.Decision a = g.poll(450);
        g.press(700);
        VolumeGesture.Decision b = g.poll(1150);
        assertTrue(!a.isDouble && a.volumeSteps == 1 && !b.isDouble && b.volumeSteps == 1);
    }

    @Test public void tripleIsNotADoubleFollowedByASingle() {
        VolumeGesture.Decision d = run(0, 200, 400);
        assertTrue(!d.isDouble);
        assertEquals(3, d.volumeSteps);
    }

    // ---- what a double press does in each state (no duplicate sessions) ----

    private static PocStateMachine started() { PocStateMachine m = new PocStateMachine(); m.start(); return m; }

    @Test public void doublePressCycle() {
        PocStateMachine m = started();
        assertEquals("listening → Question Mode", Arrays.asList(WAKE_DETECTED, PAUSE_PODCAST, PREPARE_QUESTION_INPUT), m.onVolumeDouble());
        assertEquals("getting ready → ignored", Arrays.asList(IGNORED_BUSY), m.onVolumeDouble());
        m.onInputReady();
        assertEquals("asking → end the question now", Arrays.asList(STOP_CAPTURE), m.onVolumeDouble());
        m.onQuestionDone();
        assertEquals("answering → ignored", Arrays.asList(IGNORED_BUSY), m.onVolumeDouble());
        m.onAnswerDone();
        assertEquals("paused → resume", Arrays.asList(CONTINUE_DETECTED, RESUME), m.onVolumeDouble());
        assertEquals(Phase.LISTENING, m.getPhase());
    }

    @Test public void doublePressWhileUserPausedResumes() {
        PocStateMachine m = started();
        m.onTogglePlayback(); // paused with the notification/earbud
        assertEquals(Arrays.asList(CONTINUE_DETECTED, RESUME), m.onVolumeDouble());
    }

    @Test public void stoppedIgnoresDoublePress() {
        assertEquals(Collections.emptyList(), new PocStateMachine().onVolumeDouble());
    }
}
