package at.technikum.thesis.runner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhasesTest {

    @Test
    void firstStepAtNinetySecondsGivesTheTimelineOfThePlan() throws Exception {
        Phases phases = Phases.of(Fixtures.protocol(), 90_000);

        assertEquals(90_000, phases.firstStepStartedAt());
        assertEquals(new Interval(0, 30_000), phases.warmup());
        assertEquals(new Interval(30_000, 90_000), phases.baseline());
        assertEquals(new Interval(90_000, 240_000), phases.observation());
        assertEquals(60_000, phases.baseline().lengthMillis());
        assertEquals(150_000, phases.observation().lengthMillis());
    }

    @Test
    void aLateFirstStepMovesBaselineAndObservationWithIt() throws Exception {
        Phases phases = Phases.of(Fixtures.protocol(), 90_412.5);

        assertEquals(new Interval(0, 30_412.5), phases.warmup());
        assertEquals(new Interval(30_412.5, 90_412.5), phases.baseline());
        assertEquals(new Interval(90_412.5, 240_412.5), phases.observation());
    }

    @Test
    void intervalsAreHalfOpen() {
        Interval interval = new Interval(30_000, 90_000);

        assertTrue(interval.contains(30_000));
        assertTrue(interval.contains(89_999.9));
        assertFalse(interval.contains(90_000));
        assertFalse(interval.contains(29_999.9));
    }

    @Test
    void aRequestBelongsToAPhaseByItsIntendedSendTime() throws Exception {
        Phases phases = Phases.of(Fixtures.protocol(), 90_000);

        assertEquals(Phases.Phase.BASELINE, phases.phaseOf(Fixtures.observation(1, 89_999.9, 90_050)));
        assertEquals(Phases.Phase.OBSERVATION, phases.phaseOf(Fixtures.observation(2, 90_000, 90_001)));
        assertEquals(Phases.Phase.WARMUP, phases.phaseOf(Fixtures.observation(3, 29_999.9, 30_020)));
        assertEquals(Phases.Phase.WARMUP, phases.phaseOf(Fixtures.observation(4, 0, 700)));
        assertEquals(Phases.Phase.BASELINE, phases.phaseOf(Fixtures.observation(5, 30_000, 30_001)));
        assertEquals(Phases.Phase.OBSERVATION, phases.phaseOf(Fixtures.observation(6, 239_999.9, 241_999)));
        assertEquals(Phases.Phase.AFTER, phases.phaseOf(Fixtures.observation(7, 240_000, 240_001)));
    }

    @Test
    void selectingByIntervalIgnoresTheReceiveTime() throws Exception {
        Phases phases = Phases.of(Fixtures.protocol(), 90_000);
        Observation lateAnswer = Fixtures.observation(1, 89_999.9, 90_050);
        Observation first = Fixtures.observation(2, 90_000, 90_001);
        Observation last = Fixtures.observation(3, 239_980, 241_000);
        Observation after = Fixtures.observation(4, 240_000, 240_001);
        List<Observation> all = List.of(lateAnswer, first, last, after);

        assertEquals(List.of(lateAnswer), Phases.in(phases.baseline(), all));
        assertEquals(List.of(first, last), Phases.in(phases.observation(), all));
        assertEquals(List.of(), Phases.in(phases.warmup(), all));
    }

    @Test
    void latencyIsReceiveTimeMinusIntendedSendTime() {
        Observation observation = new Observation(7, 1_000, 1_030, 1_031, 1_045, 200, "pod-a", "v1", 0, null);

        assertEquals(45, observation.latencyMillis());
    }
}
