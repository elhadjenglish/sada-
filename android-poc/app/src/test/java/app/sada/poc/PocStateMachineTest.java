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
        assertEquals(Arrays.asList(WAKE_DETECTED, PAUSE_PODCAST, PREPARE_QUESTION_INPUT), m.onKeyword("HEY_SADA"));
        assertEquals(Phase.ARMING, m.getPhase());
        assertEquals(Arrays.asList(START_CAPTURE), m.onInputReady());
        assertEquals(Phase.ASKING, m.getPhase());
        assertEquals(Arrays.asList(ANSWER), m.onQuestionDone());
        assertEquals(Arrays.asList(LISTEN_FOR_CONTINUE), m.onAnswerDone());
        assertEquals(Arrays.asList(CONTINUE_DETECTED, RESUME), m.onKeyword("CONTINUE"));
        assertEquals(Phase.LISTENING, m.getPhase());
    }

    @Test public void alternativeWakeLabel() {
        assertEquals(Arrays.asList(WAKE_DETECTED, PAUSE_PODCAST, PREPARE_QUESTION_INPUT), started().onKeyword("HEY_SADA_ALT"));
    }

    @Test public void continueWhilePlayingIsFalseTrigger() {
        PocStateMachine m = started();
        assertEquals(Arrays.asList(CONTINUE_WHILE_PLAYING), m.onKeyword("CONTINUE"));
        assertEquals(Phase.LISTENING, m.getPhase());
    }

    @Test public void keywordsIgnoredWhilePreparingAskingAndAnswering() {
        PocStateMachine m = started();
        m.onKeyword("HEY_SADA");
        assertEquals(Arrays.asList(IGNORED_BUSY), m.onKeyword("CONTINUE"));
        m.onInputReady();
        assertEquals("a question containing 'continue' must not resume", Arrays.asList(IGNORED_BUSY), m.onKeyword("CONTINUE"));
        m.onQuestionDone();
        assertEquals(Arrays.asList(IGNORED_BUSY), m.onKeyword("HEY_SADA"));
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
        m.onKeyword("HEY_SADA"); m.onInputReady(); m.onQuestionDone(); m.onAnswerDone();
        assertEquals(Arrays.asList(WAKE_WHILE_PAUSED), m.onKeyword("HEY_SADA"));
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
        m.onKeyword("HEY_SADA");
        assertEquals(Collections.emptyList(), m.onMarkMiss());
    }

    @Test public void stoppedIgnoresEverything() {
        PocStateMachine m = new PocStateMachine();
        assertEquals(Collections.emptyList(), m.onKeyword("HEY_SADA"));
        assertEquals(Collections.emptyList(), m.onTogglePlayback());
        assertEquals(Collections.emptyList(), m.onActivate());
    }

    @Test public void watchdogRecoversStalledSteps() {
        for (int stage = 0; stage < 3; stage++) {
            PocStateMachine m = started();
            m.onKeyword("HEY_SADA");
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
