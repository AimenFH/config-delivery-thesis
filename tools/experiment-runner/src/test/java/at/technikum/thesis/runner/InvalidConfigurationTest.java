package at.technikum.thesis.runner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static at.technikum.thesis.runner.TestRuns.A;
import static at.technikum.thesis.runner.TestRuns.B;
import static at.technikum.thesis.runner.TestRuns.answer;
import static at.technikum.thesis.runner.TestRuns.failure;
import static at.technikum.thesis.runner.TestRuns.load;
import static at.technikum.thesis.runner.TestRuns.replace;
import static at.technikum.thesis.runner.TestRuns.seen;
import static at.technikum.thesis.runner.TestRuns.without;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvalidConfigurationTest {

    private static final Interval R = new Interval(10_000, 40_000);
    private static final double FIX_T0 = 11_000;
    private static final List<String> INVALID = List.of("v2");

    @Test
    void e1_is_the_number_of_distinct_pods_that_answered_with_an_invalid_version() {
        assertEquals(2, InvalidConfiguration.exposedPods(exposure(), R, INVALID));
    }

    @Test
    void e2_is_the_number_of_successful_answers_with_an_invalid_version() {
        assertEquals(3, InvalidConfiguration.exposedRequests(exposure(), R, INVALID));
    }

    @Test
    void e1_and_e2_are_zero_when_no_pod_answers_with_an_invalid_version() {
        List<Observation> observations = List.of(seen(10_000, 10_002, "a", "v1"), seen(10_020, 10_022, "a", "v3"));

        assertEquals(0, InvalidConfiguration.exposedPods(observations, R, INVALID));
        assertEquals(0, InvalidConfiguration.exposedRequests(observations, R, INVALID));
    }

    @Test
    void r1_runs_from_T0_of_the_fix_to_the_last_answer_that_is_not_v3_when_that_is_the_later_event() {
        List<Observation> observations = replace(recovering(),
                failure(600));

        Metric r1 = recovery(observations);

        assertEquals("R1", r1.column());
        assertFalse(r1.censored());
        assertEquals(15_982 - 11_000, r1.value());
    }

    @Test
    void r1_runs_to_the_last_failed_request_when_that_is_the_later_event() {
        List<Observation> observations = replace(recovering(), failure(900));

        Metric r1 = recovery(observations);

        assertFalse(r1.censored());
        assertEquals(18_000 + 2_000 - 11_000, r1.value());
    }

    @Test
    void r1_is_at_least_zero() {
        List<Observation> observations = load(500, 2_000, (pod, at) -> "v3");

        Metric r1 = recovery(observations);

        assertFalse(r1.censored());
        assertEquals(0, r1.value());
    }

    @Test
    void r1_is_censored_when_an_answer_that_is_not_v3_is_intended_in_the_last_10_s_of_the_run() {
        Metric atTheEdge = recovery(replace(recovering(), answer(1_500, A, "v1")));
        Metric justBefore = recovery(replace(recovering(), answer(1_499, B, "v1")));

        assertTrue(atTheEdge.censored());
        assertEquals(40_000 - 11_000, atTheEdge.value());
        assertFalse(justBefore.censored());
        assertEquals(29_982 - 11_000, justBefore.value());
    }

    @Test
    void r1_is_censored_when_a_request_fails_in_the_last_10_s_of_the_run() {
        Metric atTheEdge = recovery(replace(recovering(), failure(1_500)));
        Metric justBefore = recovery(replace(recovering(), failure(1_499)));

        assertTrue(atTheEdge.censored());
        assertEquals(29_000, atTheEdge.value());
        assertFalse(justBefore.censored());
        assertEquals(29_980 + 2_000 - 11_000, justBefore.value());
    }

    @Test
    void r1_is_censored_when_a_tick_in_the_last_10_s_of_the_run_has_no_observation() {
        Metric r1 = recovery(without(recovering(), seq -> seq == 1_999));

        assertTrue(r1.censored());
        assertEquals(29_000, r1.value());
    }

    @Test
    void r1_is_empty_without_a_T0_of_the_fix() {
        List<Observation> observations = recovering();
        Availability availability = new Availability(observations, 0, 2_000, 50);

        assertNull(InvalidConfiguration.recovery(observations, availability, R, "v3", null).value());
    }

    private static Metric recovery(List<Observation> observations) {
        Availability availability = new Availability(observations, 0, 2_000, 50);
        return InvalidConfiguration.recovery(observations, availability, R, "v3", FIX_T0);
    }

    private static List<Observation> recovering() {
        return load(0, 2_000, (pod, at) -> at < 16_000 ? "v1" : "v3");
    }

    private static List<Observation> exposure() {
        return List.of(
                seen(9_980, 9_990, "x", "v2"),
                seen(10_000, 10_002, "a", "v1"),
                seen(10_020, 10_022, "a", "v2"),
                seen(10_040, 10_042, "a", "v2"),
                seen(10_060, 10_062, "b", "v2"),
                new Observation(504, 10_080, 10_080, 10_080, 10_090, 503, "c", "v2", 0, null),
                seen(10_100, 10_102, "c", "v3"),
                seen(40_000, 40_002, "d", "v2"));
    }
}
