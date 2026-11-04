package app.sada.poc;

import static app.sada.poc.CaptureHealth.AfterLoss.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Regression tests for "earbuds disconnected after Go ahead: Sada kept recording digital silence". */
public class BluetoothDisconnectTest {
    /** Blocks until lost (or -1), feeding the given levels with the given route state. */
    static int blocksUntilLost(CaptureHealth h, double level, boolean routeOk, int max) {
        for (int i = 1; i <= max; i++) if (h.push(level, routeOk)) return i;
        return -1;
    }

    @Test public void disconnectDuringRecordingIsDetectedFromTheAudio() {
        CaptureHealth h = new CaptureHealth();
        for (int i = 0; i < 100; i++) assertFalse(h.push(-55, true));          // recording normally
        int n = blocksUntilLost(h, -140, false, 400);                           // zeros, route gone (device report: -102 dBFS, peak 0)
        assertEquals("lost after 0.4 s of digital silence", CaptureHealth.DEAD_WITH_ROUTE_LOSS, n);
        assertTrue(h.reason().contains("route lost"));
    }

    @Test public void lateRouteUpdateStillDetectedByAudioAlone() {
        CaptureHealth h = new CaptureHealth();
        assertEquals(CaptureHealth.DEAD_ALONE, blocksUntilLost(h, -140, true, 400));
    }

    @Test public void routeChangeAloneIsNotALossWhileAudioFlows() {
        CaptureHealth h = new CaptureHealth();
        assertEquals(-1, blocksUntilLost(h, -60, false, 400));
    }

    @Test public void quietRoomIsNotALoss() {
        CaptureHealth h = new CaptureHealth();
        assertEquals("ordinary quiet background is far above digital silence", -1, blocksUntilLost(h, -75, true, 1000));
    }

    @Test public void briefDropoutDoesNotTrigger() {
        CaptureHealth h = new CaptureHealth();
        for (int i = 0; i < 10; i++) h.push(-140, false); // 160 ms glitch
        for (int i = 0; i < 50; i++) h.push(-60, false);
        assertFalse(h.lost());
    }

    // ---- what happens to the question ----

    @Test public void disconnectImmediatelyAfterGoAheadRestartsOnPhone() {
        assertEquals(RESTART_ON_PHONE, CaptureHealth.afterLoss(false, 0, 0));
    }

    @Test public void disconnectWhileSpeakingRestartsOnPhone() {
        // speaking when cut: the recording is incomplete, and two microphones are never mixed
        assertEquals(RESTART_ON_PHONE, CaptureHealth.afterLoss(true, 1500, 50));
    }

    @Test public void disconnectAfterAFinishedQuestionKeepsIt() {
        // 2 s of real speech, then 0.6 s of pause before the loss: that recording is the question
        assertEquals(KEEP_AS_QUESTION, CaptureHealth.afterLoss(true, 2000, 600));
    }

    @Test public void tooLittleSpeechBeforeTheLossRestarts() {
        assertEquals(RESTART_ON_PHONE, CaptureHealth.afterLoss(true, 300, 900));
    }

    @Test public void emptyOrSilentRecordingIsRejected() {
        assertFalse("no speech", CaptureHealth.isUsable(false, 0.0, 0));
        assertFalse("digital silence counted as speech", CaptureHealth.isUsable(true, 0.0, 2000));
        assertFalse("a click", CaptureHealth.isUsable(true, 0.2, 100));
        assertTrue(CaptureHealth.isUsable(true, 0.1, 1500));
    }

    @Test public void fallbackAnnouncements() {
        assertEquals("The earbud microphone disconnected. Go ahead, I'm listening on the phone.",
            MicReadiness.prompt(MicReadiness.Outcome.FALLBACK_DISCONNECTED, false));
        assertTrue(MicReadiness.label(MicReadiness.Outcome.FALLBACK_DISCONNECTED).startsWith("phone mic"));
        assertTrue("phone fallback failing is said, not hidden", MicReadiness.noMicPrompt().contains("no microphone"));
    }

    @Test public void fallbackKeepsTheSameQuestionSessionNoDuplicate() {
        // the fallback continues the SAME question phase: no second "Question Mode" is opened
        PocStateMachine m = new PocStateMachine(); m.start();
        m.onActivate(); m.onInputReady();                         // asking on the earbud mic
        assertEquals(PocStateMachine.Phase.ASKING, m.getPhase());
        // (disconnect → phone fallback: no state change; a double press meanwhile only ends the question)
        assertEquals(java.util.Arrays.asList(PocStateMachine.StepKind.IGNORED_BUSY), m.onActivate());
        assertEquals(java.util.Arrays.asList(PocStateMachine.StepKind.ANSWER), m.onQuestionDone());
        assertEquals("a second 'question done' (e.g. from the dead earbud recorder) does nothing",
            java.util.Collections.emptyList(), m.onQuestionDone());
    }
}
