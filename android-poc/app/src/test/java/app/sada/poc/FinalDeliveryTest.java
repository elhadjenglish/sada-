package app.sada.poc;

import static app.sada.poc.PocStateMachine.Phase;
import static app.sada.poc.PocStateMachine.StepKind.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;

public class FinalDeliveryTest {
    static PocStateMachine answered() {
        PocStateMachine m = new PocStateMachine(); m.start();
        m.onVolumeDouble(); m.onInputReady(); m.onQuestionDone(); m.onAnswerDone();
        return m;
    }

    // ---- approved controls after an answer / after no question ----

    @Test public void afterAnAnswerVolumeDownAsksANewQuestionWithoutResuming() {
        PocStateMachine m = answered();
        assertEquals("after a real answer Volume Down does nothing", Collections.emptyList(), m.onVolumeDownDouble());
        assertEquals(Phase.WAIT_CONTINUE, m.getPhase());
    }

    @Test public void afterAnAnswerVolumeUpOrContinueResumes() {
        assertEquals(Arrays.asList(CONTINUE_DETECTED, RESUME), answered().onVolumeDouble());
        assertEquals(Arrays.asList(CONTINUE_DETECTED, RESUME), answered().onKeyword("CONTINUE"));
    }

    @Test public void afterNoQuestionVolumeDownRetriesAndThePodcastStaysPaused() {
        PocStateMachine m = new PocStateMachine(); m.start();
        m.onVolumeDouble(); m.onInputReady();
        m.onQuestionDone(); m.onAnswerDone();                 // "I didn't hear a question…"
        m.offerRetry();                                      // as SadaService does after the no-speech message
        assertEquals(Phase.WAIT_CONTINUE, m.getPhase());
        assertEquals("nothing resumes on its own", Collections.emptyList(), m.recover());
        assertEquals(Arrays.asList(PREPARE_QUESTION_INPUT), m.onVolumeDownDouble());
        m.onInputReady(); m.onQuestionDone(); m.onAnswerDone();
        assertEquals("then Up still resumes", Arrays.asList(CONTINUE_DETECTED, RESUME), m.onVolumeDouble());
    }

    @Test public void volumeDownNeverInterruptsOrDuplicatesAQuestion() {
        PocStateMachine m = new PocStateMachine(); m.start();
        m.onVolumeDouble();
        assertEquals("preparing", Arrays.asList(IGNORED_BUSY), m.onVolumeDownDouble());
        m.onInputReady();
        assertEquals("user speaking", Arrays.asList(IGNORED_BUSY), m.onVolumeDownDouble());
        assertEquals(Phase.ASKING, m.getPhase());
        m.onQuestionDone();
        assertEquals("answering", Arrays.asList(IGNORED_BUSY), m.onVolumeDownDouble());
        assertEquals(Collections.emptyList(), new PocStateMachine().onVolumeDownDouble());
    }

    @Test public void volumeDownWhileListeningAsks() {
        PocStateMachine m = new PocStateMachine(); m.start();
        assertEquals("only Volume Up activates while the podcast plays", Collections.emptyList(), m.onVolumeDownDouble());
    }

    @Test public void volumeDownGestureUsesTheSameProtectionAsVolumeUp() {
        // same detector class: long press / triple press are volume steps, not a gesture
        VolumeGesture g = new VolumeGesture();
        for (long t : new long[]{0, 400, 450, 500, 550}) g.press(t);
        VolumeGesture.Decision d = g.poll(1100);
        assertFalse(d.isDouble);
        assertEquals(5, d.volumeSteps);
    }

    @Test public void approvedWording() {
        assertEquals("To keep listening, say Continue or double-press Volume Up.", AnswerPlan.RESUME_HINT);
        assertEquals("I didn't hear a question. To try again, double-press Volume Down. To keep listening, say Continue or double-press Volume Up.",
            AnswerPlan.message(AnswerPlan.Failure.EMPTY_RECORDING));
        assertFalse("said once: no extra hint", AnswerPlan.needsResumeHint(AnswerPlan.Failure.EMPTY_RECORDING));
        assertFalse("retry is Volume Down, not Up", AnswerPlan.message(AnswerPlan.Failure.NOT_UNDERSTOOD).contains("Volume Up"));
    }

    // ---- wake pipeline health ----

    @Test public void healthTellsMicDetectorAndNoMatchApart() {
        long w = 10_000, blocks = w / 16;
        assertEquals(WakeHealth.Status.OK, WakeHealth.evaluate(true, w, blocks, -55, blocks * 256 / 1536));
        assertEquals("no audio arriving", WakeHealth.Status.MIC_FAILURE, WakeHealth.evaluate(true, w, 10, -55, 0));
        assertEquals("digital silence", WakeHealth.Status.MIC_FAILURE, WakeHealth.evaluate(true, w, blocks, -120, blocks * 256 / 1536));
        assertEquals("audio but no processing", WakeHealth.Status.DETECTOR_STALLED, WakeHealth.evaluate(true, w, blocks, -55, 0));
        assertEquals("not checked while not listening", WakeHealth.Status.NOT_CHECKED, WakeHealth.evaluate(false, w, 0, -120, 0));
    }

    @Test public void recoveryIsRateLimited() {
        assertTrue(WakeHealth.mayRecover(100_000, 0));
        assertFalse("no loop", WakeHealth.mayRecover(100_000, 70_000));
        assertTrue(WakeHealth.mayRecover(130_000, 70_000));
    }

    // ---- Cancel while the question is being recorded ----

    @Test public void cancelWhileRecordingReturnsToListeningAndResumes() {
        PocStateMachine m = new PocStateMachine(); m.start();
        m.onVolumeDouble(); m.onInputReady();
        assertEquals(Phase.ASKING, m.getPhase());
        assertEquals(Arrays.asList(RESUME), m.onCancel());
        assertEquals(Phase.LISTENING, m.getPhase());
        assertEquals("the question is gone: nothing to finish or answer", Collections.emptyList(), m.onQuestionDone());
    }

    @Test public void cancelDoesNothingOutsideRecording() {
        PocStateMachine m = new PocStateMachine(); m.start();
        assertEquals(Collections.emptyList(), m.onCancel());                // listening
        m.onVolumeDouble();
        assertEquals("preparing the microphone", Collections.emptyList(), m.onCancel());
        m.onInputReady(); m.onQuestionDone();
        assertEquals("answering", Collections.emptyList(), m.onCancel());
        assertEquals(Phase.ANSWERING, m.getPhase());
    }
}
