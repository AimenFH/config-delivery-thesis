package at.technikum.thesis.runner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static at.technikum.thesis.runner.TestRuns.commit;
import static at.technikum.thesis.runner.TestRuns.seen;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SuccessiveChangesTest {

    private static final Interval R = new Interval(0, 30_000);
    private static final Payload V2 = new Payload("v2", "1000");
    private static final Payload V3 = new Payload("v3", "1000");

    @Test
    void the_intermediate_versions_are_those_of_every_payload_but_the_last() {
        assertEquals(List.of("v2"), SuccessiveChanges.intermediateVersions(List.of(V2, V3)));
        assertEquals(List.of("v2", "v3"),
                SuccessiveChanges.intermediateVersions(List.of(V2, V3, new Payload("v4", "1000"))));
        assertEquals(List.of(), SuccessiveChanges.intermediateVersions(List.of(V2)));
        assertEquals(List.of(), SuccessiveChanges.intermediateVersions(List.of()));
    }

    @Test
    void q1_runs_from_t0_of_the_last_commit_to_the_last_answer_that_does_not_carry_the_last_version() {
        List<Observation> observations = List.of(
                seen(1_000, 1_002, "a", "v1"),
                seen(2_000, 2_002, "a", "v2"),
                seen(3_000, 3_002, "a", "v3"),
                seen(3_500, 3_640, "b", "v2"),
                seen(3_600, 3_602, "c", "v1"),
                seen(4_000, 4_002, "b", "v3"),
                seen(25_000, 25_002, "c", "v3"));

        assertEquals(new Metric(MetricId.Q1, "Q1", 3_640.0 - 2_500, false, null),
                SuccessiveChanges.finalConvergence(observations, R, "v3", 2_500.0));
    }

    @Test
    void q1_is_zero_when_every_answer_after_the_baseline_carries_the_last_version_or_came_before_t0() {
        List<Observation> onlyLast = List.of(seen(1_000, 1_002, "a", "v3"), seen(25_000, 25_002, "b", "v3"));
        List<Observation> beforeT0 = List.of(seen(1_000, 1_002, "a", "v1"), seen(25_000, 25_002, "a", "v3"));

        assertEquals(0, SuccessiveChanges.finalConvergence(onlyLast, R, "v3", 2_500.0).value());
        assertEquals(0, SuccessiveChanges.finalConvergence(beforeT0, R, "v3", 2_500.0).value());
    }

    @Test
    void q1_is_censored_at_the_run_end_when_another_version_is_served_in_the_last_ten_seconds() {
        List<Observation> observations = List.of(
                seen(1_000, 1_002, "a", "v1"),
                seen(3_000, 3_002, "a", "v3"),
                seen(20_000, 20_002, "b", "v2"),
                seen(29_000, 29_002, "a", "v3"));

        assertEquals(new Metric(MetricId.Q1, "Q1", 30_000.0 - 2_500, true, null),
                SuccessiveChanges.finalConvergence(observations, R, "v3", 2_500.0));
    }

    @Test
    void q1_is_not_censored_by_another_version_that_ends_just_before_the_last_ten_seconds() {
        List<Observation> observations = List.of(
                seen(19_980, 19_990, "b", "v2"),
                seen(29_000, 29_002, "a", "v3"));

        assertEquals(new Metric(MetricId.Q1, "Q1", 19_990.0 - 2_500, false, null),
                SuccessiveChanges.finalConvergence(observations, R, "v3", 2_500.0));
    }

    @Test
    void q1_skips_failed_requests_and_answers_outside_the_interval() {
        List<Observation> observations = List.of(
                seen(3_000, 3_002, "a", "v2"),
                new Observation(0, 5_000, 5_000, 5_000, 5_010, 503, "a", "v2", 0, null),
                seen(6_000, 6_002, "a", "v3"),
                seen(30_000, 30_002, "b", "v2"));

        assertEquals(new Metric(MetricId.Q1, "Q1", 3_002.0 - 2_500, false, null),
                SuccessiveChanges.finalConvergence(observations, R, "v3", 2_500.0));
    }

    @Test
    void q1_has_no_value_when_the_last_commit_did_not_succeed() {
        List<Observation> observations = List.of(seen(3_000, 3_002, "a", "v2"));

        assertEquals(new Metric(MetricId.Q1, "Q1", null, false, null),
                SuccessiveChanges.finalConvergence(observations, R, "v3", null));
        assertEquals(new Metric(MetricId.Q1, "Q1", null, false, null),
                SuccessiveChanges.finalConvergence(observations, R, null, 2_500.0));
    }

    @Test
    void q7_is_t0_of_the_last_commit_minus_t0_of_the_first() {
        List<RunRecord.StepRecord> commits = List.of(
                commit(0, 0, V2, 90_000, 94_100),
                commit(1, 10, V3, 100_000, 103_900));

        assertEquals(new Metric(MetricId.Q7, "Q7", 103_900.0 - 94_100, false, null),
                SuccessiveChanges.commitGap(commits));
    }

    @Test
    void q7_has_no_value_with_fewer_than_two_commits_or_a_failed_commit() {
        List<RunRecord.StepRecord> one = List.of(commit(0, 0, V2, 90_000, 90_100));
        List<RunRecord.StepRecord> failed = List.of(
                commit(0, 0, V2, 90_000, 90_100),
                commit(1, 10, V3, 100_000, 100_080, 1, null));

        assertEquals(new Metric(MetricId.Q7, "Q7", null, false, null), SuccessiveChanges.commitGap(one));
        assertEquals(new Metric(MetricId.Q7, "Q7", null, false, null), SuccessiveChanges.commitGap(failed));
    }
}
