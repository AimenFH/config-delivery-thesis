package at.technikum.thesis.runner;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static at.technikum.thesis.runner.TestRuns.seen;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropagationTest {

    private static final double T_CS = 5_000;
    private static final double T0 = 5_100;
    private static final Interval H = new Interval(5_000, 155_000);

    private final WindowDetector detector = new WindowDetector(1000);

    @Test
    void p1_is_the_later_of_t_last_old_and_t_first_new_minus_T0() {
        List<Observation> observations = switches(6_000, 6_200, 6_500);
        WindowDetector.Window window = detector.window(observations, "v1", "v2", T_CS);

        Metric p1 = Propagation.convergence(window, T0, false, H);

        assertEquals(MetricId.P1, p1.id());
        assertEquals("P1", p1.column());
        assertEquals(6_400 - 5_100, p1.value());
        assertFalse(p1.censored());
    }

    @Test
    void p1_uses_t_first_new_when_every_pod_switches_between_two_answers() {
        List<Observation> observations = switches(6_000, 6_000, 6_000);
        WindowDetector.Window window = detector.window(observations, "v1", "v2", T_CS);

        assertEquals(6_000 - 5_100, Propagation.convergence(window, T0, false, H).value());
    }

    @Test
    void p1_is_censored_at_the_length_of_H_when_v1_is_still_served_at_the_end_of_H() {
        List<Observation> observations = switches(6_000, 6_200, Double.POSITIVE_INFINITY);
        Interval h = new Interval(5_000, 10_000);
        WindowDetector.Window window = detector.window(observations, "v1", "v2", T_CS);
        boolean stillServed = detector.stillServed(observations, "v1", window, h);

        Metric p1 = Propagation.convergence(window, T0, stillServed, H);

        assertTrue(stillServed);
        assertTrue(p1.censored());
        assertEquals(150_000, p1.value());
    }

    @Test
    void p1_is_censored_at_the_length_of_H_when_v2_is_never_observed() {
        List<Observation> observations = switches(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.POSITIVE_INFINITY);
        WindowDetector.Window window = detector.window(observations, "v1", "v2", T_CS);

        Metric p1 = Propagation.convergence(window, T0,
                detector.stillServed(observations, "v1", window, new Interval(5_000, 10_000)), H);

        assertTrue(p1.censored());
        assertEquals(150_000, p1.value());
    }

    @Test
    void p2_is_t_first_new_minus_T0() {
        WindowDetector.Window window = detector.window(switches(6_000, 6_200, 6_500), "v1", "v2", T_CS);

        Metric p2 = Propagation.firstActivation(window, T0);

        assertEquals("P2", p2.column());
        assertEquals(6_000 - 5_100, p2.value());
    }

    @Test
    void p2_is_empty_when_v2_is_never_observed() {
        WindowDetector.Window window = detector.window(
                switches(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY),
                "v1", "v2", T_CS);

        assertNull(Propagation.firstActivation(window, T0).value());
    }

    @Test
    void p3_is_T0_minus_t_cs() {
        Metric p3 = Propagation.commitDuration(T_CS, T0);

        assertEquals("P3", p3.column());
        assertEquals(100, p3.value());
    }

    @Test
    void without_a_T0_the_propagation_times_are_empty() {
        WindowDetector.Window window = detector.window(switches(6_000, 6_200, 6_500), "v1", "v2", T_CS);

        assertNull(Propagation.convergence(window, null, false, H).value());
        assertNull(Propagation.firstActivation(window, null).value());
        assertNull(Propagation.commitDuration(T_CS, null).value());
    }

    private static List<Observation> switches(double a, double b, double c) {
        List<Observation> observations = new ArrayList<>();
        double[] switchAt = {a, b, c};
        String[] pods = {"a", "b", "c"};
        for (int i = 0; i < pods.length; i++) {
            for (double t = 0; t < 10_000; t += 100) {
                observations.add(seen(t, t, pods[i], t < switchAt[i] ? "v1" : "v2"));
            }
        }
        return observations;
    }
}
