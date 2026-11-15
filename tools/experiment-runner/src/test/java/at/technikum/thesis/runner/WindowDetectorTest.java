package at.technikum.thesis.runner;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowDetectorTest {

    private static final ChangeTrigger.Result CHANGE = new ChangeTrigger.Result("patch", 5000, 5100, 0, "");
    private static final double STEP = 100;
    private static final double RUN_END = 10000;
    private static final double T_CS = 5000;

    private final WindowDetector detector = new WindowDetector(1000);

    @Test
    void cleanSwitchWithOverlap() {
        List<Observation> observations = new ArrayList<>();
        observations.addAll(pod("a", 0, RUN_END, switchAt(6000)));
        observations.addAll(pod("b", 0, RUN_END, switchAt(6200)));
        observations.addAll(pod("c", 0, RUN_END, switchAt(6500)));

        WindowDetector.Result result = detector.detect(observations, CHANGE);

        assertTrue(result.valid(), result.problems().toString());
        assertEquals(List.of(), result.flags());
        assertEquals("v1", result.oldVersion());
        assertEquals("v2", result.newVersion());
        assertEquals(6000, result.firstNewAt());
        assertEquals(6400, result.lastOldAt());
        assertEquals(400, result.windowMillis());
        assertEquals(600, result.windowMaxMillis());
        assertEquals(900, result.propagationMillis());
        assertEquals(1300, result.convergenceMillis());
    }

    @Test
    void simultaneousSwitchHasNoWindow() {
        List<Observation> observations = new ArrayList<>();
        for (String name : List.of("a", "b", "c")) {
            observations.addAll(pod(name, 0, RUN_END, switchAt(6000)));
        }

        WindowDetector.Result result = detector.detect(observations, CHANGE);

        assertTrue(result.valid(), result.problems().toString());
        assertEquals(0, result.windowMillis());
        assertEquals(100, result.windowMaxMillis());
        assertEquals(900, result.convergenceMillis());
    }

    @Test
    void newVersionNeverSeen() {
        List<Observation> observations = new ArrayList<>();
        for (String name : List.of("a", "b", "c")) {
            observations.addAll(pod(name, 0, RUN_END, t -> "v1"));
        }

        WindowDetector.Result result = detector.detect(observations, CHANGE);

        assertNull(result.windowMillis());
        assertTrue(result.valid(), result.problems().toString());
        assertEquals(List.of(RunMetrics.Flag.NOT_CONVERGED), result.flags());
    }

    @Test
    void podStillOldAtEndIsNotConverged() {
        List<Observation> observations = new ArrayList<>();
        observations.addAll(pod("a", 0, RUN_END, switchAt(6000)));
        observations.addAll(pod("b", 0, RUN_END, switchAt(6200)));
        observations.addAll(pod("c", 0, RUN_END, t -> "v1"));

        WindowDetector.Result result = detector.detect(observations, CHANGE);

        assertTrue(result.valid(), result.problems().toString());
        assertEquals(List.of(RunMetrics.Flag.NOT_CONVERGED), result.flags());
    }

    @Test
    void podGoingBackToOldVersionIsARegression() {
        List<Observation> observations = new ArrayList<>();
        observations.addAll(pod("a", 0, RUN_END, t -> t >= 6000 && t < 7000 ? "v2" : "v1"));
        observations.addAll(pod("b", 0, RUN_END, switchAt(6000)));
        observations.addAll(pod("c", 0, RUN_END, switchAt(6000)));

        WindowDetector.Result result = detector.detect(observations, CHANGE);

        assertTrue(result.valid(), result.problems().toString());
        assertEquals(List.of(RunMetrics.Flag.NOT_CONVERGED, RunMetrics.Flag.REGRESSION), result.flags());
    }

    @Test
    void mixedWarmupIsInvalid() {
        List<Observation> observations = new ArrayList<>();
        observations.addAll(pod("a", 0, RUN_END, switchAt(6000)));
        observations.addAll(pod("b", 0, RUN_END, t -> t < 1000 ? "v0" : t < 6000 ? "v1" : "v2"));
        observations.addAll(pod("c", 0, RUN_END, switchAt(6000)));

        WindowDetector.Result result = detector.detect(observations, CHANGE);

        assertFalse(result.valid());
        assertProblem(result, "warm-up showed more than one version");
    }

    @Test
    void rollingRestartReplacesPods() {
        List<Observation> observations = new ArrayList<>();
        observations.addAll(pod("old-1", 0, 6000, t -> "v1"));
        observations.addAll(pod("old-2", 0, 7000, t -> "v1"));
        observations.addAll(pod("old-3", 0, 8000, t -> "v1"));
        observations.addAll(pod("new-1", 5800, RUN_END, t -> "v2"));
        observations.addAll(pod("new-2", 6800, RUN_END, t -> "v2"));
        observations.addAll(pod("new-3", 7800, RUN_END, t -> "v2"));

        WindowDetector.Result result = detector.detect(observations, CHANGE);

        assertTrue(result.valid(), result.problems().toString());
        assertEquals(5800, result.firstNewAt());
        assertEquals(7900, result.lastOldAt());
        assertEquals(2100, result.windowMillis());
        assertEquals(3900, result.windowMaxMillis());
    }

    @Test
    void failedRequestsAfterChangeAreCounted() {
        List<Observation> observations = new ArrayList<>();
        for (String name : List.of("a", "b", "c")) {
            observations.addAll(pod(name, 0, RUN_END, switchAt(6000)));
        }
        observations.add(new Observation(-1, 2000, 2000, 2000, 2000, 0, null, null, 0, "ConnectException"));
        observations.add(new Observation(-2, 5500, 5500, 5500, 5500, 0, null, null, 0, "ConnectException"));
        observations.add(new Observation(-3, 9500, 9500, 9500, 9500, 503, null, null, 0, null));

        WindowDetector.Result result = detector.detect(observations, CHANGE);

        assertTrue(result.valid(), result.problems().toString());
        assertEquals(2, result.failedAfterChange());
    }

    @Test
    void t_first_new_is_the_first_v2_answer_and_t_last_old_the_last_v1_answer_by_receive_time() {
        List<Observation> observations = new ArrayList<>();
        observations.addAll(pod("a", 0, RUN_END, switchAt(6000)));
        observations.addAll(pod("b", 0, RUN_END, switchAt(6200)));
        observations.addAll(pod("c", 0, RUN_END, switchAt(6500)));

        WindowDetector.Window window = detector.window(observations, "v1", "v2", T_CS);

        assertEquals(6000, window.firstNewAt());
        assertEquals(6400, window.lastOldAt());
        assertEquals(6400, window.convergedAt());
    }

    @Test
    void c1_is_t_last_old_minus_t_first_new() {
        List<Observation> observations = new ArrayList<>();
        observations.addAll(pod("a", 0, RUN_END, switchAt(6000)));
        observations.addAll(pod("b", 0, RUN_END, switchAt(6200)));
        observations.addAll(pod("c", 0, RUN_END, switchAt(6500)));

        assertEquals(400, detector.window(observations, "v1", "v2", T_CS).windowMillis());
    }

    @Test
    void c1_is_zero_when_the_last_v1_answer_comes_before_the_first_v2_answer() {
        List<Observation> observations = new ArrayList<>();
        for (String name : List.of("a", "b", "c")) {
            observations.addAll(pod(name, 0, RUN_END, switchAt(6000)));
        }

        WindowDetector.Window window = detector.window(observations, "v1", "v2", T_CS);

        assertEquals(5900, window.lastOldAt());
        assertEquals(6000, window.firstNewAt());
        assertEquals(0, window.windowMillis());
        assertEquals(6000, window.convergedAt());
    }

    @Test
    void c1_upper_bound_adds_the_unobserved_time_before_the_first_v2_and_after_the_last_v1_answer_on_their_pods() {
        List<Observation> observations = new ArrayList<>();
        observations.addAll(pod("a", 0, RUN_END, switchAt(6000)));
        observations.addAll(pod("b", 0, RUN_END, switchAt(6200)));
        observations.addAll(pod("c", 0, RUN_END, switchAt(6500)));

        assertEquals(400 + 100 + 100, detector.window(observations, "v1", "v2", T_CS).windowMaxMillis());
    }

    @Test
    void c1_upper_bound_uses_the_coverage_limit_for_a_pod_without_an_earlier_or_later_answer() {
        List<Observation> observations = new ArrayList<>();
        observations.addAll(pod("old-1", 0, 6000, t -> "v1"));
        observations.addAll(pod("old-2", 0, 7000, t -> "v1"));
        observations.addAll(pod("old-3", 0, 8000, t -> "v1"));
        observations.addAll(pod("new-1", 6300, RUN_END, t -> "v2"));
        observations.addAll(pod("new-2", 6800, RUN_END, t -> "v2"));
        observations.addAll(pod("new-3", 7800, RUN_END, t -> "v2"));

        WindowDetector.Window window = detector.window(observations, "v1", "v2", T_CS);

        assertEquals(6300, window.firstNewAt());
        assertEquals(7900, window.lastOldAt());
        assertEquals(1600, window.windowMillis());
        assertEquals(1600 + 1000 + 1000, window.windowMaxMillis());
    }

    @Test
    void only_answers_received_at_or_after_t_cs_count_for_t_first_new_and_t_last_old() {
        List<Observation> observations = new ArrayList<>();
        for (String name : List.of("a", "b", "c")) {
            observations.addAll(pod(name, 0, RUN_END, switchAt(6000)));
        }
        observations.add(new Observation(0, 4950, 4950, 4950, 4999.9, 200, "a", "v2", 0, null));

        assertEquals(6000, detector.window(observations, "v1", "v2", T_CS).firstNewAt());

        observations.add(new Observation(0, 4950, 4950, 4950, 5000, 200, "a", "v2", 0, null));

        assertEquals(5000, detector.window(observations, "v1", "v2", T_CS).firstNewAt());
    }

    @Test
    void t_last_old_is_searched_over_the_whole_run() {
        List<Observation> observations = new ArrayList<>();
        observations.addAll(pod("a", 0, RUN_END, switchAt(6000)));
        observations.addAll(pod("b", 0, RUN_END, switchAt(6000)));
        observations.addAll(pod("c", 0, RUN_END, t -> t < 6000 || t == 9900 ? "v1" : "v2"));

        WindowDetector.Window window = detector.window(observations, "v1", "v2", T_CS);

        assertEquals(9900, window.lastOldAt());
        assertEquals(3900, window.windowMillis());
    }

    @Test
    void a_new_version_that_is_never_observed_gives_no_window_and_v1_counts_as_still_served() {
        List<Observation> observations = new ArrayList<>();
        for (String name : List.of("a", "b", "c")) {
            observations.addAll(pod(name, 0, RUN_END, t -> "v1"));
        }

        WindowDetector.Window window = detector.window(observations, "v1", "v2", T_CS);

        assertNull(window.firstNewAt());
        assertNull(window.windowMillis());
        assertNull(window.windowMaxMillis());
        assertNull(window.convergedAt());
        assertTrue(detector.stillServed(observations, "v1", window, new Interval(T_CS, RUN_END)));
    }

    @Test
    void v1_is_still_served_when_an_answer_intended_in_the_last_coverage_limit_of_the_interval_carries_v1() {
        Interval h = new Interval(T_CS, RUN_END);

        assertTrue(stillServedWithOldAnswerAt(9000, h));
        assertTrue(stillServedWithOldAnswerAt(9999, h));
    }

    @Test
    void v1_is_not_still_served_when_its_last_answer_lies_before_the_last_coverage_limit_or_after_the_interval() {
        Interval h = new Interval(T_CS, RUN_END);

        assertFalse(stillServedWithOldAnswerAt(8999, h));
        assertFalse(stillServedWithOldAnswerAt(10000, h));
    }

    @Test
    void still_served_goes_by_the_intended_time_and_not_by_the_receive_time() {
        List<Observation> observations = new ArrayList<>();
        for (String name : List.of("a", "b", "c")) {
            observations.addAll(pod(name, 0, RUN_END, switchAt(6000)));
        }
        observations.add(new Observation(0, 8990, 8990, 8990, 9400, 200, "c", "v1", 0, null));
        WindowDetector.Window window = detector.window(observations, "v1", "v2", T_CS);

        assertFalse(detector.stillServed(observations, "v1", window, new Interval(T_CS, RUN_END)));
    }

    @Test
    void a_failed_request_never_counts_as_an_answer() {
        List<Observation> observations = new ArrayList<>();
        for (String name : List.of("a", "b", "c")) {
            observations.addAll(pod(name, 0, RUN_END, switchAt(6000)));
        }
        observations.add(new Observation(0, 9500, 9500, 9500, 9500, 503, "c", "v1", 0, null));
        observations.add(new Observation(0, 5500, 5500, 5500, 5500, 0, "c", "v2", 0, "ConnectException"));
        WindowDetector.Window window = detector.window(observations, "v1", "v2", T_CS);

        assertEquals(6000, window.firstNewAt());
        assertEquals(5900, window.lastOldAt());
        assertFalse(detector.stillServed(observations, "v1", window, new Interval(T_CS, RUN_END)));
    }

    private boolean stillServedWithOldAnswerAt(double intendedAt, Interval interval) {
        List<Observation> observations = new ArrayList<>();
        for (String name : List.of("a", "b", "c")) {
            observations.addAll(pod(name, 0, 8900, switchAt(6000)));
        }
        observations.add(new Observation(0, intendedAt, intendedAt, intendedAt, intendedAt + 1, 200, "c", "v1", 0, null));
        WindowDetector.Window window = detector.window(observations, "v1", "v2", T_CS);
        return detector.stillServed(observations, "v1", window, interval);
    }

    private static DoubleFunction<String> switchAt(double switchTime) {
        return t -> t < switchTime ? "v1" : "v2";
    }

    private static List<Observation> pod(String name, double from, double to, DoubleFunction<String> versionAt) {
        List<Observation> observations = new ArrayList<>();
        for (double t = from; t < to; t += STEP) {
            observations.add(new Observation(0, t, t, t, t, 200, name, versionAt.apply(t), 0, null));
        }
        return observations;
    }

    private static void assertProblem(WindowDetector.Result result, String expected) {
        assertTrue(result.problems().stream().anyMatch(p -> p.contains(expected)),
                "expected a problem containing '" + expected + "' but got " + result.problems());
    }
}
