package at.technikum.thesis.runner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static at.technikum.thesis.runner.TestRuns.seen;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ConsistencyTest {

    private static final Interval R = new Interval(1_000, 10_000);
    private static final List<String> V1_V2 = List.of("v1", "v2");

    @Test
    void c1_and_its_upper_bound_are_reported_as_two_columns() {
        List<Metric> metrics = Consistency.window(new WindowDetector.Window(6_000.0, 6_400.0, 400.0, 600.0));

        assertEquals(List.of(new Metric(MetricId.C1, "C1", 400.0, false, null),
                new Metric(MetricId.C1, "C1_max", 600.0, false, null)), metrics);
    }

    @Test
    void c2_counts_how_often_a_v2_answer_is_directly_followed_by_a_v1_answer_in_receive_order_over_all_pods() {
        List<Observation> observations = List.of(
                seen(1_000, 1_100, "a", "v1"),
                seen(1_020, 1_200, "b", "v2"),
                seen(1_040, 1_300, "a", "v1"),
                seen(1_060, 1_400, "c", "v2"),
                seen(1_080, 1_500, "b", "v2"),
                seen(1_100, 1_600, "c", "v1"),
                seen(1_120, 1_700, "a", "v2"));

        assertEquals(2, Consistency.reversions(observations, R, "v1", "v2"));
    }

    @Test
    void c2_orders_answers_by_receive_time_and_not_by_send_time() {
        List<Observation> observations = List.of(
                seen(1_000, 1_900, "a", "v1"),
                seen(1_020, 1_030, "b", "v2"),
                seen(1_040, 1_050, "b", "v2"));

        assertEquals(1, Consistency.reversions(observations, R, "v1", "v2"));
    }

    @Test
    void c2_skips_failed_requests_and_answers_outside_the_interval() {
        List<Observation> observations = List.of(
                seen(900, 950, "b", "v2"),
                seen(1_000, 1_010, "a", "v1"),
                seen(1_020, 1_030, "b", "v2"),
                new Observation(52, 1_040, 1_040, 1_040, 1_050, 503, "a", "v1", 0, null),
                seen(1_060, 1_070, "b", "v2"),
                seen(10_000, 10_010, "a", "v1"));

        assertEquals(0, Consistency.reversions(observations, R, "v1", "v2"));
    }

    @Test
    void c3_counts_v1_answers_from_a_pod_that_had_already_answered_v2() {
        List<Observation> observations = List.of(
                seen(1_000, 1_010, "a", "v1"),
                seen(1_100, 1_110, "a", "v2"),
                seen(1_200, 1_210, "a", "v1"),
                seen(1_300, 1_310, "a", "v1"),
                seen(1_400, 1_410, "a", "v2"),
                seen(1_250, 1_260, "b", "v1"),
                seen(1_350, 1_360, "b", "v2"));

        assertEquals(2, Consistency.regressions(observations, R, V1_V2));
    }

    @Test
    void c3_counts_an_answer_only_when_its_request_was_sent_after_the_first_v2_answer_of_the_pod_was_received() {
        Observation firstNew = seen(1_100, 1_200, "a", "v2");
        Observation sentBefore = seen(1_150, 1_250, "a", "v1");
        Observation sentAtTheSameTime = seen(1_200, 1_260, "a", "v1");
        Observation sentJustAfter = seen(1_200.5, 1_270, "a", "v1");

        assertEquals(0, Consistency.regressions(List.of(firstNew, sentBefore), R, V1_V2));
        assertEquals(0, Consistency.regressions(List.of(firstNew, sentAtTheSameTime), R, V1_V2));
        assertEquals(1, Consistency.regressions(List.of(firstNew, sentJustAfter), R, V1_V2));
    }

    @Test
    void c3_follows_the_order_in_which_the_scenario_declares_the_versions() {
        List<String> declared = List.of("v1", "v2", "v3");
        List<Observation> forward = List.of(
                seen(1_000, 1_010, "a", "v1"),
                seen(1_100, 1_110, "a", "v2"),
                seen(1_200, 1_210, "a", "v3"));
        List<Observation> backward = List.of(
                seen(1_000, 1_010, "a", "v3"),
                seen(1_100, 1_110, "a", "v2"),
                seen(1_200, 1_210, "a", "v1"),
                seen(1_300, 1_310, "a", "v3"));

        assertEquals(0, Consistency.regressions(forward, R, declared));
        assertEquals(2, Consistency.regressions(backward, R, declared));
    }

    @Test
    void c3_looks_only_at_answers_inside_the_interval() {
        List<Observation> observations = List.of(
                seen(900, 910, "a", "v2"),
                seen(1_000, 1_010, "a", "v1"),
                seen(1_100, 1_110, "a", "v1"));

        assertEquals(0, Consistency.regressions(observations, R, V1_V2));
    }
}
