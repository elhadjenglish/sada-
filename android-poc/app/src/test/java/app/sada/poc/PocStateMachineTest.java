package app.sada.poc;

import static app.sada.poc.PocStateMachine.Phase;
import static app.sada.poc.PocStateMachine.StepKind.*;
import static org.junit.Assert.assertEquals;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;

public class PocStateMachineTest {
    private static PocStateMachine started() { PocStateMachine m = new PocStateMachine(); m.start(); return m; }

    @Test public void fullHandsFreeCycle() {
        PocStateMachine m = started();
        assertEquals(Arrays.asList(WAKE_DETECTED, PAUSE_PODCAST, PREPARE_QUESTION_INPUT), m.onVolumeDouble());
        assertEquals(Phase.ARMING, m.getPhase());
        assertEquals(Arrays.asList(START_CAPTURE), m.onInputReady());
        assertEquals(Phase.ASKING, m.getPhase());
        assertEquals(Arrays.asList(ANSWER), m.onQuestionDone());
        assertEquals(Arrays.asList(LISTEN_FOR_CONTINUE), m.onAnswerDone());
        assertEquals(Arrays.asList(CONTINUE_DETECTED, RESUME), m.onKeyword("CONTINUE"));
        assertEquals(Phase.LISTENING, m.getPhase());
    }

    @Test public void heySadaNoLongerActivates() {
        PocStateMachine m = started();
        assertEquals(Arrays.asList(UNKNOWN_KEYWORD), m.onKeyword("HEY_SADA"));
        assertEquals(Arrays.asList(UNKNOWN_KEYWORD), m.onKeyword("HEY_SADA_ALT"));
        assertEquals(Phase.LISTENING, m.getPhase());
    }

    @Test public void continueWhilePlayingIsFalseTrigger() {
        PocStateMachine m = started();
        assertEquals(Arrays.asList(CONTINUE_WHILE_PLAYING), m.onKeyword("CONTINUE"));
        assertEquals(Phase.LISTENING, m.getPhase());
    }

    @Test public void keywordsIgnoredWhilePreparingAskingAndAnswering() {
        PocStateMachine m = started();
        m.onVolumeDouble();
        assertEquals(Arrays.asList(IGNORED_BUSY), m.onKeyword("CONTINUE"));
        m.onInputReady();
        assertEquals("a question containing 'continue' must not resume", Arrays.asList(IGNORED_BUSY), m.onKeyword("CONTINUE"));
        m.onQuestionDone();
        assertEquals(Arrays.asList(IGNORED_BUSY), m.onVolumeDouble());
        assertEquals(Phase.ANSWERING, m.getPhase());
    }

    @Test public void outOfOrderEventsDoNothing() {
        PocStateMachine m = started();
        assertEquals(Collections.emptyList(), m.onInputReady());
        assertEquals(Collections.emptyList(), m.onQuestionDone());
        assertEquals(Collections.emptyList(), m.onAnswerDone());
        assertEquals(Phase.LISTENING, m.getPhase());
    }

    @Test public void wakeWhileWaitingDoesNotResume() {
        PocStateMachine m = started();
        m.onVolumeDouble(); m.onInputReady(); m.onQuestionDone(); m.onAnswerDone();
        assertEquals(Arrays.asList(UNKNOWN_KEYWORD), m.onKeyword("HEY_SADA"));
        assertEquals(Phase.WAIT_CONTINUE, m.getPhase());
    }

    @Test public void activationWithoutVoiceIsTheSameFlow() {
        PocStateMachine m = started();
        assertEquals(Arrays.asList(WAKE_DETECTED, PAUSE_PODCAST, PREPARE_QUESTION_INPUT), m.onActivate());
        m.onInputReady(); m.onQuestionDone(); m.onAnswerDone();
        assertEquals("ask again while paused", Arrays.asList(PREPARE_QUESTION_INPUT), m.onActivate());
        assertEquals(Phase.ARMING, m.getPhase());
        assertEquals(Arrays.asList(IGNORED_BUSY), m.onActivate());
    }

    @Test public void playPauseIsOrdinaryPodcastControl() {
        PocStateMachine m = started();
        assertEquals(Arrays.asList(PAUSE_PODCAST, LISTEN_FOR_CONTINUE), m.onTogglePlayback());
        assertEquals(Phase.WAIT_CONTINUE, m.getPhase());
        assertEquals("Continue or Hey Sada still work while paused", Arrays.asList(CONTINUE_DETECTED, RESUME), m.onKeyword("CONTINUE"));
        m.onTogglePlayback();
        assertEquals(Arrays.asList(CONTINUE_DETECTED, RESUME), m.onTogglePlayback());
        assertEquals(Phase.LISTENING, m.getPhase());
        m.onActivate(); m.onInputReady();
        assertEquals("tap ends a question", Arrays.asList(STOP_CAPTURE), m.onTogglePlayback());
        assertEquals(Phase.ASKING, m.getPhase());
    }

    @Test public void missMarkOnlyWhileListening() {
        PocStateMachine m = started();
        assertEquals(Arrays.asList(MISSED_WAKE), m.onMarkMiss());
        m.onVolumeDouble();
        assertEquals(Collections.emptyList(), m.onMarkMiss());
    }

    @Test public void stoppedIgnoresEverything() {
        PocStateMachine m = new PocStateMachine();
        assertEquals(Collections.emptyList(), m.onVolumeDouble());
        assertEquals(Collections.emptyList(), m.onTogglePlayback());
        assertEquals(Collections.emptyList(), m.onActivate());
    }

    @Test public void watchdogRecoversStalledSteps() {
        for (int stage = 0; stage < 3; stage++) {
            PocStateMachine m = started();
            m.onVolumeDouble();
            if (stage >= 1) m.onInputReady();
            if (stage >= 2) m.onQuestionDone();
            assertEquals(Arrays.asList(RECOVERED, LISTEN_FOR_CONTINUE), m.recover());
            assertEquals(Phase.WAIT_CONTINUE, m.getPhase());
            assertEquals("still resumable", Arrays.asList(CONTINUE_DETECTED, RESUME), m.onKeyword("CONTINUE"));
        }
        PocStateMachine idle = started();
        assertEquals("nothing to recover while listening", Collections.emptyList(), idle.recover());
    }

    @Test public void unknownLabel() {
        assertEquals(Arrays.asList(UNKNOWN_KEYWORD), started().onKeyword("LIGHT_UP"));
    }
}
