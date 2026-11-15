package at.technikum.thesis.runner;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static at.technikum.thesis.runner.TestRuns.seen;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoverageCheckTest {

    private static final Interval INTERVAL = new Interval(10_000, 20_000);

    private final CoverageCheck check = new CoverageCheck(3, 1000);

    @Test
    void the_gap_of_a_pod_is_the_longest_time_between_two_of_its_own_ok_answers_in_the_interval() {
        List<Observation> observations = podsBAndC();
        observations.addAll(podA(10_010, 12_010, 12_710, 12_910, 15_000));

        CoverageCheck.Result result = check.check(observations, INTERVAL);

        assertEquals(2090, pod(result, "a").maxGapMillis());
        assertEquals(500, pod(result, "b").maxGapMillis());
        assertEquals(2090, result.longestGapMillis());
    }

    @Test
    void a_gap_of_exactly_the_coverage_limit_is_not_a_problem() {
        List<Observation> observations = podsBAndC();
        observations.addAll(podA(10_010, 11_010, 12_010, 13_010));

        CoverageCheck.Result result = check.check(observations, INTERVAL);

        assertEquals(1000, pod(result, "a").maxGapMillis());
        assertTrue(result.valid(), result.problems().toString());
        assertFalse(check.hasGap(observations, INTERVAL));
    }

    @Test
    void a_gap_over_the_coverage_limit_is_a_problem() {
        List<Observation> observations = podsBAndC();
        observations.addAll(podA(10_010, 11_010, 12_011));

        CoverageCheck.Result result = check.check(observations, INTERVAL);

        assertEquals(1001, pod(result, "a").maxGapMillis());
        assertEquals(List.of("pod a was unobserved for 1001 ms (limit 1000 ms)"), result.problems());
        assertTrue(check.hasGap(observations, INTERVAL));
    }

    @Test
    void answers_with_an_intended_time_outside_the_interval_do_not_count() {
        List<Observation> observations = podsBAndC();
        observations.addAll(podA(10_010, 10_510));
        observations.add(seen(9_999, 10_005, "d", "v1"));
        observations.add(seen(20_000, 20_005, "d", "v1"));
        observations.add(seen(5_000, 5_005, "a", "v1"));

        CoverageCheck.Result result = check.check(observations, INTERVAL);

        assertEquals(List.of("a", "b", "c"), result.pods().stream().map(CoverageCheck.PodCoverage::pod).toList());
        assertEquals(500, pod(result, "a").maxGapMillis());
    }

    @Test
    void failed_requests_do_not_close_a_gap() {
        List<Observation> observations = podsBAndC();
        observations.addAll(podA(10_010, 14_010));
        observations.add(new Observation(0, 12_000, 12_000, 12_000, 12_010, 503, "a", "v1", 0, null));
        observations.add(new Observation(0, 12_500, 12_500, 12_500, 12_510, 200, "a", null, 0, null));

        assertEquals(4000, pod(check.check(observations, INTERVAL), "a").maxGapMillis());
    }

    @Test
    void fewer_answering_pods_than_replicas_is_a_problem() {
        List<Observation> observations = new ArrayList<>(podA(10_010, 10_510));
        observations.add(seen(10_000, 10_010, "b", "v1"));

        assertEquals(List.of("only 2 of 3 replicas were observed"), check.check(observations, INTERVAL).problems());
    }

    private static List<Observation> podsBAndC() {
        List<Observation> observations = new ArrayList<>();
        for (String name : List.of("b", "c")) {
            for (double at = 10_000; at < 20_000; at += 500) {
                observations.add(seen(at, at + 10, name, "v1"));
            }
        }
        return observations;
    }

    private static List<Observation> podA(double... receivedAt) {
        List<Observation> observations = new ArrayList<>();
        for (double at : receivedAt) {
            observations.add(seen(at - 10, at, "a", "v1"));
        }
        return observations;
    }

    private static CoverageCheck.PodCoverage pod(CoverageCheck.Result result, String name) {
        return result.pods().stream().filter(p -> p.pod().equals(name)).findFirst().orElseThrow();
    }
}
