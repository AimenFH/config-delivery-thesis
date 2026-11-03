package at.technikum.thesis.runner;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowDetectorTest {

    private static final ChangeTrigger.Result CHANGE = new ChangeTrigger.Result("patch", 5000, 5100, 0, "");
    private static final double STEP = 100;
    private static final double RUN_END = 10000;

    private final WindowDetector detector = new WindowDetector(1000);

    @Test
    void cleanSwitchWithOverlap() {
        List<Observation> observations = new ArrayList<>();
        observations.addAll(pod("a", 0, RUN_END, switchAt(6000)));
        observations.addAll(pod("b", 0, RUN_END, switchAt(6200)));
        observations.addAll(pod("c", 0, RUN_END, switchAt(6500)));

        WindowDetector.Result result = detector.detect(observations, CHANGE);

        assertTrue(result.valid(), result.problems().toString());
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
        assertProblem(result, "new version never observed");
    }

    @Test
    void podStillOldAtEndIsNotConverged() {
        List<Observation> observations = new ArrayList<>();
        observations.addAll(pod("a", 0, RUN_END, switchAt(6000)));
        observations.addAll(pod("b", 0, RUN_END, switchAt(6200)));
        observations.addAll(pod("c", 0, RUN_END, t -> "v1"));

        WindowDetector.Result result = detector.detect(observations, CHANGE);

        assertProblem(result, "pod c still served v1 at the end of the run");
    }

    @Test
    void podGoingBackToOldVersionIsARegression() {
        List<Observation> observations = new ArrayList<>();
        observations.addAll(pod("a", 0, RUN_END, t -> t >= 6000 && t < 7000 ? "v2" : "v1"));
        observations.addAll(pod("b", 0, RUN_END, switchAt(6000)));
        observations.addAll(pod("c", 0, RUN_END, switchAt(6000)));

        WindowDetector.Result result = detector.detect(observations, CHANGE);

        assertProblem(result, "pod a served v1 again after v2");
    }

    @Test
    void mixedWarmupIsInvalid() {
        List<Observation> observations = new ArrayList<>();
        observations.addAll(pod("a", 0, RUN_END, switchAt(6000)));
        observations.addAll(pod("b", 0, RUN_END, t -> t < 1000 ? "v0" : t < 6000 ? "v1" : "v2"));
        observations.addAll(pod("c", 0, RUN_END, switchAt(6000)));

        WindowDetector.Result result = detector.detect(observations, CHANGE);

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
        observations.add(new Observation(-1, 2000, 2000, 2000, 0, null, null, 0, "ConnectException"));
        observations.add(new Observation(-2, 5500, 5500, 5500, 0, null, null, 0, "ConnectException"));
        observations.add(new Observation(-3, 9500, 9500, 9500, 503, null, null, 0, null));

        WindowDetector.Result result = detector.detect(observations, CHANGE);

        assertTrue(result.valid(), result.problems().toString());
        assertEquals(2, result.failedAfterChange());
    }

    private static DoubleFunction<String> switchAt(double switchTime) {
        return t -> t < switchTime ? "v1" : "v2";
    }

    private static List<Observation> pod(String name, double from, double to, DoubleFunction<String> versionAt) {
        List<Observation> observations = new ArrayList<>();
        for (double t = from; t < to; t += STEP) {
            observations.add(new Observation(0, t, t, t, 200, name, versionAt.apply(t), 0, null));
        }
        return observations;
    }

    private static void assertProblem(WindowDetector.Result result, String expected) {
        assertTrue(result.problems().stream().anyMatch(p -> p.contains(expected)),
                "expected a problem containing '" + expected + "' but got " + result.problems());
    }
}
