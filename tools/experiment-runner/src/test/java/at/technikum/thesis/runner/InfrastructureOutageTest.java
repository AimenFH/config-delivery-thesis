package at.technikum.thesis.runner;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static at.technikum.thesis.runner.TestRuns.seen;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InfrastructureOutageTest {

    private static final Interval R = new Interval(0, 10_000);

    private final WindowDetector detector = new WindowDetector(1000);

    @Test
    void u1_is_the_number_of_distinct_pods_answering_v2_before_infra_up_starts() {
        List<Observation> observations = List.of(
                seen(4_000, 4_002, "a", "v1"),
                seen(5_000, 5_002, "d", "v2"),
                seen(5_100, 5_102, "d", "v2"),
                seen(5_900, 5_999, "e", "v2"),
                seen(5_950, 6_000, "b", "v2"),
                seen(6_100, 6_102, "c", "v2"),
                new Observation(0, 5_000, 5_000, 5_000, 5_010, 503, "f", "v2", 0, null));

        assertEquals(2, InfrastructureOutage.deliveredDuringOutage(observations, "v2", 6_000));
    }

    @Test
    void u2_is_the_time_from_delete_pod_to_the_first_answer_of_a_pod_not_seen_before_with_its_version() {
        List<Observation> observations = List.of(
                seen(1_000, 1_002, "a", "v1"),
                seen(1_020, 1_022, "b", "v1"),
                seen(2_980, 2_999, "c", "v1"),
                seen(3_100, 3_102, "b", "v1"),
                seen(3_500, 3_502, "c", "v2"),
                seen(3_780, 3_800, "d", "v1"),
                seen(3_900, 3_902, "e", "v2"),
                seen(4_000, 4_002, "d", "v2"));

        Metric u2 = InfrastructureOutage.replacementPod(observations, 3_000, 10_000);

        assertEquals("U2", u2.column());
        assertFalse(u2.censored());
        assertEquals(800, u2.value());
        assertEquals("v1", u2.text());
    }

    @Test
    void u2_treats_a_pod_whose_first_answer_arrives_exactly_when_delete_pod_starts_as_new() {
        List<Observation> observations = List.of(
                seen(1_000, 1_002, "a", "v1"),
                seen(2_990, 3_000, "d", "v2"));

        Metric u2 = InfrastructureOutage.replacementPod(observations, 3_000, 10_000);

        assertEquals(0, u2.value());
        assertEquals("v2", u2.text());
    }

    @Test
    void u2_is_censored_at_the_time_from_delete_pod_to_the_run_end_when_no_new_pod_answers() {
        List<Observation> observations = List.of(
                seen(1_000, 1_002, "a", "v1"),
                seen(1_020, 1_022, "b", "v1"),
                seen(3_100, 3_102, "b", "v2"),
                new Observation(0, 3_200, 3_200, 3_200, 3_210, 503, "d", "v2", 0, null));

        Metric u2 = InfrastructureOutage.replacementPod(observations, 3_000, 10_000);

        assertTrue(u2.censored());
        assertEquals(7_000, u2.value());
        assertNull(u2.text());
    }

    @Test
    void u3_for_one_switch_equals_t_last_old_minus_t_first_new() {
        List<Observation> observations = List.of(
                seen(100, 100, "a", "v1"), seen(200, 200, "a", "v1"), seen(300, 300, "a", "v1"),
                seen(400, 400, "a", "v2"), seen(500, 500, "a", "v2"),
                seen(150, 150, "b", "v1"), seen(250, 250, "b", "v1"), seen(350, 350, "b", "v1"),
                seen(450, 450, "b", "v1"), seen(550, 550, "b", "v2"), seen(650, 650, "b", "v2"));

        WindowDetector.Window window = detector.window(observations, "v1", "v2", 0);

        assertEquals(50, InfrastructureOutage.mixedVersionMillis(observations, R));
        assertEquals(50, window.lastOldAt() - window.firstNewAt());
    }

    @Test
    void u3_is_the_total_time_in_which_at_least_two_versions_are_served_between_same_version_answers_of_a_pod() {
        List<Observation> observations = List.of(
                seen(100, 100, "a", "v1"), seen(200, 200, "a", "v1"),
                seen(300, 300, "a", "v2"), seen(400, 400, "a", "v2"),
                seen(150, 150, "b", "v1"), seen(250, 250, "b", "v1"), seen(350, 350, "b", "v1"),
                seen(450, 450, "b", "v2"),
                seen(120, 120, "c", "v2"), seen(180, 180, "c", "v2"), seen(700, 700, "c", "v1"));

        assertEquals(60 + 50, InfrastructureOutage.mixedVersionMillis(observations, R));
    }

    @Test
    void u3_is_zero_when_only_one_version_is_served_at_any_time() {
        List<Observation> observations = List.of(
                seen(100, 100, "a", "v1"), seen(200, 200, "a", "v1"),
                seen(300, 300, "a", "v2"), seen(400, 400, "a", "v2"),
                seen(100, 110, "b", "v1"), seen(200, 190, "b", "v1"),
                seen(300, 310, "b", "v2"), seen(400, 410, "b", "v2"));

        assertEquals(0, InfrastructureOutage.mixedVersionMillis(observations, R));
    }

    @Test
    void u3_looks_only_at_answers_of_the_interval_and_skips_failed_requests() {
        List<Observation> observations = new ArrayList<>(List.of(
                seen(100, 100, "a", "v1"), seen(500, 500, "a", "v1"),
                seen(200, 200, "b", "v2"), seen(400, 400, "b", "v2"),
                seen(10_000, 10_050, "b", "v2"),
                new Observation(0, 300, 300, 300, 300, 503, "b", "v1", 0, null)));

        assertEquals(200, InfrastructureOutage.mixedVersionMillis(observations, R));
    }

    @Test
    void u4_runs_from_the_end_of_infra_up_to_convergence_on_v2() {
        WindowDetector.Window window = new WindowDetector.Window(6_800.0, 7_300.0, 500.0, 700.0);

        Metric u4 = InfrastructureOutage.catchUp(window, false, 5_000.0, 10_000);

        assertEquals("U4", u4.column());
        assertFalse(u4.censored());
        assertEquals(2_300, u4.value());
    }

    @Test
    void u4_is_at_least_zero() {
        WindowDetector.Window window = new WindowDetector.Window(3_000.0, 4_000.0, 1_000.0, 1_200.0);

        assertEquals(0, InfrastructureOutage.catchUp(window, false, 5_000.0, 10_000).value());
    }

    @Test
    void u4_is_censored_at_the_time_from_the_end_of_infra_up_to_the_run_end_when_v1_is_still_served_at_the_end() {
        List<Observation> observations = new ArrayList<>();
        for (double t = 0; t < 10_000; t += 100) {
            observations.add(seen(t, t, "a", t < 6_000 ? "v1" : "v2"));
            observations.add(seen(t, t, "b", "v1"));
        }
        WindowDetector.Window window = detector.window(observations, "v1", "v2", 2_000);
        boolean stillServed = detector.stillServed(observations, "v1", window, R);

        Metric u4 = InfrastructureOutage.catchUp(window, stillServed, 5_000.0, 10_000);

        assertTrue(stillServed);
        assertTrue(u4.censored());
        assertEquals(5_000, u4.value());
    }
}
