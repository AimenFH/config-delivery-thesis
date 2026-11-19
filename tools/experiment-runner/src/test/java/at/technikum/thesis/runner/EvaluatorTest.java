package at.technikum.thesis.runner;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static at.technikum.thesis.runner.RunMetrics.Flag.FAILED_REQUESTS;
import static at.technikum.thesis.runner.RunMetrics.Flag.LOST_UPDATE;
import static at.technikum.thesis.runner.RunMetrics.Flag.LOW_RESOLUTION;
import static at.technikum.thesis.runner.RunMetrics.Flag.NOT_CONVERGED;
import static at.technikum.thesis.runner.RunMetrics.Flag.NOT_RECOVERED;
import static at.technikum.thesis.runner.RunMetrics.Flag.REGRESSION;
import static at.technikum.thesis.runner.RunMetrics.Flag.TRIGGER_FAILED;
import static at.technikum.thesis.runner.RunMetrics.Flag.UNCONFIGURED;
import static at.technikum.thesis.runner.RunMetrics.Verdict.INVALID;
import static at.technikum.thesis.runner.RunMetrics.Verdict.VALID;
import static at.technikum.thesis.runner.TestRuns.A;
import static at.technikum.thesis.runner.TestRuns.B;
import static at.technikum.thesis.runner.TestRuns.C;
import static at.technikum.thesis.runner.TestRuns.answer;
import static at.technikum.thesis.runner.TestRuns.cleanS1;
import static at.technikum.thesis.runner.TestRuns.commit;
import static at.technikum.thesis.runner.TestRuns.failure;
import static at.technikum.thesis.runner.TestRuns.late;
import static at.technikum.thesis.runner.TestRuns.load;
import static at.technikum.thesis.runner.TestRuns.replace;
import static at.technikum.thesis.runner.TestRuns.reroute;
import static at.technikum.thesis.runner.TestRuns.samples;
import static at.technikum.thesis.runner.TestRuns.step;
import static at.technikum.thesis.runner.TestRuns.without;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluatorTest {

    private static final String D = "probe-service-d";
    private static final Payload V2 = new Payload("v2", "1000");

    private static Scenario s1Scenario;
    private static Scenario s2Scenario;
    private static Scenario s3Scenario;
    private static Evaluator s1;
    private static Evaluator s2;
    private static Evaluator s3;

    @BeforeAll
    static void readTheContracts() throws Exception {
        Protocol protocol = Fixtures.protocol();
        List<ApproachDescriptor> approaches = new ArrayList<>();
        for (String approach : protocol.approaches()) {
            approaches.add(ApproachDescriptor.read(Fixtures.ROOT.resolve(approach)));
        }
        s1Scenario = Scenario.read(Fixtures.ROOT.resolve("scenarios/s1-valid-change.json"));
        s1 = new Evaluator(protocol, s1Scenario, approaches);
        s2Scenario = Scenario.read(Fixtures.ROOT.resolve("scenarios/s2-invalid-config.json"));
        s2 = new Evaluator(protocol, s2Scenario, approaches);
        s3Scenario = Scenario.read(Fixtures.ROOT.resolve("scenarios/s3-infra-outage.json"));
        s3 = new Evaluator(protocol, s3Scenario, approaches);
    }

    @Test
    void s1_reports_the_five_criteria_P1_to_O5_with_fixed_columns_in_the_order_of_the_plan() {
        RunMetrics metrics = s1.evaluate(TestRuns.s1(cleanS1()));

        assertEquals(List.of("P1", "P2", "P3", "C1", "C1_max", "C2", "C3", "A1", "A2", "A3", "F1",
                        "F2_p50_B", "F2_p95_B", "F2_p99_B", "F2_max_B", "F2_p50_H", "F2_p95_H", "F2_p99_H", "F2_max_H",
                        "F2_p99_ratio", "F3", "O1", "O2", "O3_cpu", "O3_memory", "O4",
                        "O5_extraComponents", "O5_operatorActions", "O5_clusterWideRbac", "O5_serviceCoupling",
                        "O5_requestedCpu", "O5_requestedMemory"),
                metrics.metrics().stream().map(Metric::column).toList());
        assertEquals(metrics.metrics().stream().map(Metric::column).toList(), Evaluator.columns(s1Scenario));
        for (Metric metric : metrics.metrics()) {
            assertTrue(metric.column().startsWith(metric.id().name()), metric.column());
        }
    }

    @Test
    void a_clean_s1_run_is_valid_without_flags_and_carries_its_identity() {
        RunMetrics metrics = s1.evaluate(TestRuns.s1(cleanS1()));

        assertEquals(VALID, metrics.verdict());
        assertEquals(List.of(), metrics.invalidReasons());
        assertEquals(Set.of(), metrics.flags());
        assertTrue(metrics.commitSucceeded());
        assertEquals("run-1", metrics.runId());
        assertEquals("way-a", metrics.approach());
        assertEquals("s1-valid-change", metrics.scenario());
        assertEquals(1, metrics.block());
    }

    @Test
    void the_five_criteria_of_a_staggered_switch_match_the_hand_calculation() {
        RunMetrics metrics = s1.evaluate(TestRuns.s1(staggeredSwitch()));

        assertEquals(VALID, metrics.verdict(), metrics.invalidReasons().toString());
        assertEquals(Set.of(), metrics.flags());
        assertEquals(95_442 - 90_100, value(metrics, "P1"));
        assertEquals(95_042 - 90_100, value(metrics, "P2"));
        assertEquals(100, value(metrics, "P3"));
        assertEquals(95_442 - 95_042, value(metrics, "C1"));
        assertEquals(400 + 60 + 60, value(metrics, "C1_max"));
        assertEquals(3 + 4, value(metrics, "C2"));
        assertEquals(0, value(metrics, "C3"));
        assertEquals(0, value(metrics, "A1"));
        assertEquals(0, value(metrics, "A2"));
        assertEquals(3, value(metrics, "A3"));
        assertEquals(0 - 0.01 * 7_500, value(metrics, "F1"));
        for (String column : List.of("F2_p50_B", "F2_p95_B", "F2_p99_B", "F2_max_B",
                "F2_p50_H", "F2_p95_H", "F2_p99_H", "F2_max_H", "F3")) {
            assertEquals(2, value(metrics, column), column);
        }
        assertEquals(1, value(metrics, "F2_p99_ratio"));
        assertEquals(10, value(metrics, "O1"), 1e-6);
        assertEquals(64, value(metrics, "O2"));
        assertEquals(150, value(metrics, "O3_cpu"), 1e-6);
        assertEquals(3 * 256, value(metrics, "O3_memory"));
        assertEquals(0, value(metrics, "O4"));
        assertEquals(new Metric(MetricId.O5, "O5_extraComponents", 1.0, false, "Stakater Reloader"),
                metric(metrics, "O5_extraComponents"));
        assertEquals(1, value(metrics, "O5_operatorActions"));
        assertEquals(1, value(metrics, "O5_clusterWideRbac"));
        assertEquals("none, values read at process start", metric(metrics, "O5_serviceCoupling").text());
        assertEquals("reloader-reloader=10m", metric(metrics, "O5_requestedCpu").text());
        assertEquals("reloader-reloader=64Mi", metric(metrics, "O5_requestedMemory").text());
    }

    @Test
    void o1_and_o2_cover_B_and_H_and_nothing_before_or_after() {
        List<ResourceSample> resources = new ArrayList<>();
        double cpuSeconds = 50;
        for (double at = 0; at <= 240_000; at += 5_000) {
            long workingSet = at == 10_000 ? 500 : at == 40_000 ? 100 : at == 240_000 ? 900 : 64;
            resources.add(new ResourceSample(at, TestRuns.NODE, "platform", TestRuns.INFRA_POD, "reloader",
                    cpuSeconds, workingSet * TestRuns.MIB, 0));
            cpuSeconds += at < 30_000 ? 4 : at < 90_000 ? 0.1 : at < 235_000 ? 0.025 : 7;
        }

        RunMetrics metrics = s1.evaluate(TestRuns.s1(cleanS1(), resources));

        assertEquals(VALID, metrics.verdict(), metrics.invalidReasons().toString());
        assertEquals((12 * 0.1 + 29 * 0.025) / 205 * 1000, value(metrics, "O1"), 1e-6);
        assertEquals(100, value(metrics, "O2"));
        assertNull(value(metrics, "O3_cpu"));
    }

    @Test
    void o4_counts_the_pods_of_a_rolling_restart() {
        List<Observation> observations = reroute(reroute(cleanS1(), 4_750, 12_000, A, "probe-service-x"),
                4_750, 12_000, B, "probe-service-y");

        RunMetrics metrics = s1.evaluate(TestRuns.s1(observations));

        assertEquals(VALID, metrics.verdict(), metrics.invalidReasons().toString());
        assertEquals(2, value(metrics, "O4"));
    }

    @Test
    void check_1_a_pod_gap_of_exactly_the_coverage_limit_in_B_leaves_the_run_valid() {
        RunMetrics metrics = s1.evaluate(TestRuns.s1(gapOfPodAFrom(2_001, 22)));

        assertEquals(VALID, metrics.verdict(), metrics.invalidReasons().toString());
        assertEquals(Set.of(), metrics.flags());
    }

    @Test
    void check_1_a_pod_gap_over_the_coverage_limit_in_B_makes_the_run_invalid() {
        RunMetrics metrics = s1.evaluate(TestRuns.s1(gapOfPodAFrom(2_001, 21)));

        assertEquals(INVALID, metrics.verdict());
        assertEquals(List.of("baseline: pod probe-service-a was unobserved for 1001 ms (limit 1000 ms)"),
                metrics.invalidReasons());
    }

    @Test
    void check_1_fewer_than_three_pods_answering_in_B_makes_the_run_invalid() {
        RunMetrics metrics = s1.evaluate(TestRuns.s1(reroute(cleanS1(), 1_500, 4_500, C, B)));

        assertEquals(INVALID, metrics.verdict());
        assertEquals(List.of("baseline: only 2 of 3 replicas were observed"), metrics.invalidReasons());
    }

    @Test
    void check_1_three_pods_answering_in_B_are_enough() {
        RunMetrics metrics = s1.evaluate(TestRuns.s1(reroute(cleanS1(), 1_503, 4_500, C, B)));

        assertEquals(VALID, metrics.verdict(), metrics.invalidReasons().toString());
    }

    @Test
    void check_2_a_failed_request_in_the_last_tick_of_B_makes_the_run_invalid() {
        RunMetrics metrics = s1.evaluate(TestRuns.s1(replace(cleanS1(), failure(4_499))));

        assertEquals(INVALID, metrics.verdict());
        assertEquals(List.of("baseline: 1 failed requests"), metrics.invalidReasons());
    }

    @Test
    void check_2_a_tick_of_B_without_an_observation_counts_as_a_failed_request() {
        RunMetrics metrics = s1.evaluate(TestRuns.s1(without(cleanS1(), seq -> seq == 3_000)));

        assertEquals(INVALID, metrics.verdict());
        assertEquals(List.of("baseline: 1 failed requests"), metrics.invalidReasons());
    }

    @Test
    void check_2_a_failed_request_in_the_first_tick_of_H_leaves_the_run_valid_with_the_flag_FAILED_REQUESTS() {
        RunMetrics metrics = s1.evaluate(TestRuns.s1(replace(cleanS1(), failure(4_500))));

        assertEquals(VALID, metrics.verdict(), metrics.invalidReasons().toString());
        assertEquals(Set.of(FAILED_REQUESTS), metrics.flags());
    }

    @Test
    void check_2_more_than_one_version_in_B_makes_the_run_invalid() {
        RunMetrics metrics = s1.evaluate(TestRuns.s1(replace(cleanS1(), answer(4_499, C, "v2"))));

        assertEquals(INVALID, metrics.verdict());
        assertEquals(List.of("baseline: more than one version [v1, v2]"), metrics.invalidReasons());
    }

    @Test
    void check_2_a_second_version_in_the_first_tick_of_H_leaves_the_run_valid() {
        RunMetrics metrics = s1.evaluate(TestRuns.s1(replace(cleanS1(), answer(4_500, A, "v2"))));

        assertEquals(VALID, metrics.verdict(), metrics.invalidReasons().toString());
    }

    @Test
    void check_3_a_commit_with_an_exit_code_other_than_0_makes_the_run_invalid() {
        RunRecord record = TestRuns.s1("run-1", "way-a",
                List.of(commit(0, 0, V2, 90_000, 90_100, 1, null)), cleanS1(), samples(0, 240_000));

        RunMetrics metrics = s1.evaluate(record);

        assertEquals(INVALID, metrics.verdict());
        assertEquals(List.of("commit of v2 failed with exit code 1"), metrics.invalidReasons());
        assertFalse(metrics.commitSucceeded());
        assertNull(value(metrics, "P1"));
        assertNull(value(metrics, "P3"));
    }

    @Test
    void check_3_a_trigger_with_an_exit_code_other_than_0_makes_the_run_invalid() {
        RunRecord record = TestRuns.s1("run-1", "way-c", List.of(commit(0, 0, V2, 90_000, 90_100, 0,
                new ChangeTrigger.Result("busrefresh", 90_100, 90_300, 7, "curl: (7)"))), cleanS1(), samples(0, 240_000));

        RunMetrics metrics = s1.evaluate(record);

        assertEquals(INVALID, metrics.verdict());
        assertEquals(List.of("trigger after the commit of v2 failed with exit code 7"), metrics.invalidReasons());
        assertTrue(metrics.commitSucceeded());
    }

    @Test
    void check_3_a_failed_trigger_while_the_infrastructure_is_down_leaves_the_run_valid_with_the_flag_TRIGGER_FAILED() {
        RunMetrics metrics = s3.evaluate(s3Run(s3Observations(true, true), failedTrigger()));

        assertEquals(VALID, metrics.verdict(), metrics.invalidReasons().toString());
        assertEquals(Set.of(TRIGGER_FAILED), metrics.flags());
        assertTrue(metrics.commitSucceeded());
        assertEquals(1, value(metrics, "U1"));
        assertEquals(215_982 - 212_000, value(metrics, "U4"));
    }

    @Test
    void check_3_a_trigger_with_exit_code_0_while_the_infrastructure_is_down_sets_no_flag() {
        RunMetrics metrics = s3.evaluate(s3Run(s3Observations(true, true),
                new ChangeTrigger.Result("busrefresh", 120_100, 120_300, 0, "")));

        assertEquals(VALID, metrics.verdict(), metrics.invalidReasons().toString());
        assertEquals(Set.of(), metrics.flags());
    }

    @Test
    void check_3_a_failed_commit_while_the_infrastructure_is_down_still_makes_the_run_invalid() {
        RunRecord record = TestRuns.run("run-s3", "way-c", "s3-infra-outage", 1, 1, 1, List.of(
                        step(0, 0, Scenario.Action.INFRA_DOWN, 90_000, 90_500),
                        commit(1, 30, V2, 120_000, 120_100, 1, null),
                        step(2, 60, Scenario.Action.DELETE_POD, 150_000, 150_300),
                        step(3, 120, Scenario.Action.INFRA_UP, 210_000, 212_000)),
                s3Observations(true, true), samples(0, 360_000), 360_000);

        RunMetrics metrics = s3.evaluate(record);

        assertEquals(INVALID, metrics.verdict());
        assertEquals(List.of("commit of v2 failed with exit code 1"), metrics.invalidReasons());
    }

    @Test
    void check_3_a_failed_trigger_after_the_infrastructure_is_back_makes_the_run_invalid() {
        RunRecord record = TestRuns.run("run-s3", "way-c", "s3-infra-outage", 1, 1, 1, List.of(
                        step(0, 0, Scenario.Action.INFRA_DOWN, 90_000, 90_500),
                        step(1, 30, Scenario.Action.INFRA_UP, 120_000, 122_000),
                        commit(2, 60, V2, 150_000, 150_100, 0, failedTrigger())),
                load(0, 18_000, TestRuns.allSwitchAt(156_000, "v1", "v2")), samples(0, 360_000), 360_000);

        RunMetrics metrics = s3.evaluate(record);

        assertEquals(INVALID, metrics.verdict());
        assertEquals(List.of("trigger after the commit of v2 failed with exit code 124"), metrics.invalidReasons());
        assertEquals(Set.of(), metrics.flags());
    }

    @Test
    void check_3_a_commit_and_a_trigger_with_exit_code_0_leave_the_run_valid() {
        RunRecord record = TestRuns.s1("run-1", "way-a", List.of(commit(0, 0, V2, 90_000, 90_100, 0,
                new ChangeTrigger.Result("busrefresh", 90_100, 90_300, 0, ""))), cleanS1(), samples(0, 240_000));

        RunMetrics metrics = s1.evaluate(record);

        assertEquals(VALID, metrics.verdict(), metrics.invalidReasons().toString());
        assertTrue(metrics.commitSucceeded());
    }

    @Test
    void check_3_a_commit_of_the_scenario_that_did_not_run_makes_the_run_invalid() {
        RunRecord record = TestRuns.run("run-1", "way-a", "s2-invalid-config", 1, 1, 1,
                List.of(commit(0, 0, new Payload("v2", "abc"), 90_000, 90_100)),
                load(0, 18_000, (pod, at) -> "v1"), samples(0, 360_000), 360_000);

        RunMetrics metrics = s2.evaluate(record);

        assertEquals(INVALID, metrics.verdict());
        assertEquals(List.of("commit 2 of the scenario did not run"), metrics.invalidReasons());
        assertFalse(metrics.commitSucceeded());
    }

    @Test
    void check_4_a_schedule_lag_of_exactly_the_limit_leaves_the_run_valid() {
        RunMetrics metrics = s1.evaluate(TestRuns.s1(replace(cleanS1(), late(6_000, A, "v2", 100))));

        assertEquals(VALID, metrics.verdict(), metrics.invalidReasons().toString());
    }

    @Test
    void check_4_a_schedule_lag_over_the_limit_in_M_makes_the_run_invalid() {
        RunMetrics metrics = s1.evaluate(TestRuns.s1(replace(cleanS1(), late(6_000, A, "v2", 100.5))));

        assertEquals(INVALID, metrics.verdict());
        assertEquals(List.of("schedule lag over 100 ms for 1 requests, highest 100.5 ms"), metrics.invalidReasons());
    }

    @Test
    void check_4_covers_M_from_the_first_tick_of_B_and_not_the_warm_up() {
        RunMetrics lastOfWarmup = s1.evaluate(TestRuns.s1(replace(cleanS1(), late(1_499, C, "v1", 500))));
        RunMetrics firstOfBaseline = s1.evaluate(TestRuns.s1(replace(cleanS1(), late(1_500, A, "v1", 500))));

        assertEquals(VALID, lastOfWarmup.verdict(), lastOfWarmup.invalidReasons().toString());
        assertEquals(INVALID, firstOfBaseline.verdict());
    }

    @Test
    void check_5_exactly_one_percent_of_the_scheduled_requests_in_M_without_observation_leaves_the_run_valid() {
        RunMetrics metrics = s1.evaluate(TestRuns.s1(withoutEveryTenthTickFrom6000(105)));

        assertEquals(VALID, metrics.verdict(), metrics.invalidReasons().toString());
        assertEquals(Set.of(FAILED_REQUESTS), metrics.flags());
        assertEquals(105.0 / 7_500, value(metrics, "A1"));
    }

    @Test
    void check_5_more_than_one_percent_of_the_scheduled_requests_in_M_without_observation_makes_the_run_invalid() {
        RunMetrics metrics = s1.evaluate(TestRuns.s1(withoutEveryTenthTickFrom6000(106)));

        assertEquals(INVALID, metrics.verdict());
        assertEquals(List.of("106 of 10500 scheduled requests have no observation (limit ratio 0.01)"),
                metrics.invalidReasons());
    }

    @Test
    void check_5_does_not_count_unsent_requests_of_the_warm_up() {
        RunMetrics metrics = s1.evaluate(TestRuns.s1(without(cleanS1(), seq -> seq < 1_500)));

        assertEquals(VALID, metrics.verdict(), metrics.invalidReasons().toString());
        assertEquals(Set.of(), metrics.flags());
    }

    @Test
    void check_6_an_answer_in_M_with_a_version_that_no_payload_declares_makes_the_run_invalid() {
        RunMetrics metrics = s1.evaluate(TestRuns.s1(replace(cleanS1(), answer(6_000, A, "v9"))));

        assertEquals(INVALID, metrics.verdict());
        assertEquals(List.of("versions that no payload of the scenario declares [v9]"), metrics.invalidReasons());
    }

    @Test
    void check_6_looks_at_M_only_so_an_undeclared_version_in_the_last_tick_of_the_warm_up_leaves_the_run_valid() {
        RunMetrics warmup = s1.evaluate(TestRuns.s1(replace(cleanS1(), answer(1_499, C, "v9"))));
        RunMetrics baseline = s1.evaluate(TestRuns.s1(replace(cleanS1(), answer(1_500, A, "v9"))));

        assertEquals(VALID, warmup.verdict(), warmup.invalidReasons().toString());
        assertEquals(INVALID, baseline.verdict());
        assertTrue(baseline.invalidReasons().contains("versions that no payload of the scenario declares [v9]"));
    }

    @Test
    void check_6_the_unconfigured_version_after_the_baseline_leaves_the_run_valid_with_the_flag_UNCONFIGURED() {
        RunMetrics metrics = s1.evaluate(TestRuns.s1(replace(cleanS1(), answer(6_000, A, "v0"))));

        assertEquals(VALID, metrics.verdict(), metrics.invalidReasons().toString());
        assertEquals(Set.of(UNCONFIGURED), metrics.flags());
    }

    @Test
    void check_6_the_unconfigured_version_in_the_first_tick_after_the_baseline_is_accepted() {
        RunMetrics metrics = s1.evaluate(TestRuns.s1(replace(cleanS1(), answer(4_500, A, "v0"))));

        assertEquals(VALID, metrics.verdict(), metrics.invalidReasons().toString());
        assertEquals(Set.of(UNCONFIGURED), metrics.flags());
    }

    @Test
    void check_6_the_unconfigured_version_in_the_last_tick_of_B_makes_the_run_invalid() {
        RunMetrics metrics = s1.evaluate(TestRuns.s1(replace(cleanS1(), answer(4_499, C, "v0"))));

        assertEquals(INVALID, metrics.verdict());
        assertEquals(List.of("baseline: more than one version [v0, v1]",
                "versions that no payload of the scenario declares [v0]"), metrics.invalidReasons());
        assertEquals(Set.of(), metrics.flags());
    }

    @Test
    void check_6_another_undeclared_version_beside_the_unconfigured_one_still_makes_the_run_invalid() {
        RunMetrics metrics = s1.evaluate(TestRuns.s1(
                replace(cleanS1(), answer(6_000, A, "v0"), answer(6_003, A, "v9"))));

        assertEquals(INVALID, metrics.verdict());
        assertEquals(List.of("versions that no payload of the scenario declares [v9]"), metrics.invalidReasons());
    }

    @Test
    void check_6_a_campaign_saved_without_an_unconfigured_version_keeps_the_strict_rule() throws Exception {
        Protocol old = Fixtures.protocolWith(tree -> tree.remove("unconfiguredVersion"));
        List<ApproachDescriptor> approaches = new ArrayList<>();
        for (String approach : old.approaches()) {
            approaches.add(ApproachDescriptor.read(Fixtures.ROOT.resolve(approach)));
        }

        RunMetrics metrics = new Evaluator(old, s1Scenario, approaches)
                .evaluate(TestRuns.s1(replace(cleanS1(), answer(6_000, A, "v0"))));

        assertEquals(INVALID, metrics.verdict());
        assertEquals(List.of("versions that no payload of the scenario declares [v0]"), metrics.invalidReasons());
        assertEquals(Set.of(), metrics.flags());
    }

    @Test
    void check_6_accepts_every_version_the_scenario_declares() {
        RunMetrics metrics = s2.evaluate(s2Run(s2Observations(true)));

        assertEquals(VALID, metrics.verdict(), metrics.invalidReasons().toString());
    }

    @Test
    void check_7_a_resource_sampling_gap_of_exactly_the_limit_leaves_the_run_valid() {
        List<ResourceSample> resources = samples(0, 240_000).stream()
                .filter(s -> s.at() != 100_000 && s.at() != 105_000).toList();

        RunMetrics metrics = s1.evaluate(TestRuns.s1(cleanS1(), resources));

        assertEquals(VALID, metrics.verdict(), metrics.invalidReasons().toString());
    }

    @Test
    void check_7_a_resource_sampling_gap_over_the_limit_makes_the_run_invalid() {
        List<ResourceSample> resources = samples(0, 240_000).stream()
                .filter(s -> s.at() != 100_000 && s.at() != 105_000)
                .map(s -> s.at() == 110_000 ? at(s, 110_001) : s).toList();

        RunMetrics metrics = s1.evaluate(TestRuns.s1(cleanS1(), resources));

        assertEquals(INVALID, metrics.verdict());
        assertEquals(List.of("resource sampling: node thesis-worker has a gap of 15001 ms (limit 15 s)"),
                metrics.invalidReasons());
    }

    @Test
    void check_7_the_first_sample_may_lie_exactly_the_limit_after_the_start_of_M_but_not_later() {
        RunMetrics onTheLimit = s1.evaluate(TestRuns.s1(cleanS1(), samples(45_000, 240_000)));
        RunMetrics late = s1.evaluate(TestRuns.s1(cleanS1(), samples(45_001, 240_000)));

        assertEquals(VALID, onTheLimit.verdict(), onTheLimit.invalidReasons().toString());
        assertEquals(INVALID, late.verdict());
        assertEquals(List.of("resource sampling: node thesis-worker has no sample within 15 s of the start of the "
                + "measured run"), late.invalidReasons());
    }

    @Test
    void check_7_the_last_sample_may_lie_exactly_the_limit_before_the_end_of_M_but_not_earlier() {
        RunMetrics onTheLimit = s1.evaluate(TestRuns.s1(cleanS1(), samples(0, 225_000)));
        RunMetrics early = s1.evaluate(TestRuns.s1(cleanS1(), samples(-1, 225_000)));

        assertEquals(VALID, onTheLimit.verdict(), onTheLimit.invalidReasons().toString());
        assertEquals(INVALID, early.verdict());
        assertEquals(List.of("resource sampling: node thesis-worker has no sample within 15 s of the end of the "
                + "measured run"), early.invalidReasons());
    }

    @Test
    void check_7_a_sample_just_outside_M_counts_as_the_nearest_sample_of_its_edge() {
        List<ResourceSample> resources = new ArrayList<>(samples(40_000, 240_000));
        resources.addAll(samples(20_000, 20_000));

        RunMetrics metrics = s1.evaluate(TestRuns.s1(cleanS1(), resources));

        assertEquals(VALID, metrics.verdict(), metrics.invalidReasons().toString());
    }

    @Test
    void check_7_a_run_without_resource_samples_is_invalid() {
        RunMetrics metrics = s1.evaluate(TestRuns.s1(cleanS1(), List.of()));

        assertEquals(INVALID, metrics.verdict());
        assertEquals(List.of("no resource samples"), metrics.invalidReasons());
        assertNull(value(metrics, "O1"));
    }

    @Test
    void check_7_is_applied_to_every_node() {
        List<ResourceSample> resources = new ArrayList<>(samples(0, 240_000));
        resources.add(new ResourceSample(0, "thesis-worker2", "platform", "rabbitmq-0", "rabbitmq", 1, 1, 0));
        resources.add(new ResourceSample(100_000, "thesis-worker2", "platform", "rabbitmq-0", "rabbitmq", 2, 1, 0));
        resources.add(new ResourceSample(240_000, "thesis-worker2", "platform", "rabbitmq-0", "rabbitmq", 3, 1, 0));

        RunMetrics metrics = s1.evaluate(TestRuns.s1(cleanS1(), resources));

        assertEquals(INVALID, metrics.verdict());
        assertEquals(List.of("resource sampling: node thesis-worker2 has no sample within 15 s of the start of the "
                + "measured run"), metrics.invalidReasons());
    }

    @Test
    void check_7_a_node_of_the_pod_list_after_the_run_without_any_sample_makes_the_run_invalid() {
        RunRecord record = withPods(TestRuns.s1(cleanS1()), List.of(pod(A, "thesis-worker")),
                List.of(pod(A, "thesis-worker"), pod(D, "thesis-worker2")));

        RunMetrics metrics = s1.evaluate(record);

        assertEquals(INVALID, metrics.verdict());
        assertEquals(List.of("resource sampling: node thesis-worker2 runs pods of the pod list and has no sample"),
                metrics.invalidReasons());
    }

    @Test
    void check_7_a_node_of_the_pod_list_before_the_run_without_any_sample_makes_the_run_invalid() {
        RunRecord record = withPods(TestRuns.s1(cleanS1()),
                List.of(pod(A, "thesis-worker"), pod(D, "thesis-worker3")), List.of(pod(A, "thesis-worker")));

        RunMetrics metrics = s1.evaluate(record);

        assertEquals(INVALID, metrics.verdict());
        assertEquals(List.of("resource sampling: node thesis-worker3 runs pods of the pod list and has no sample"),
                metrics.invalidReasons());
    }

    @Test
    void check_7_a_node_of_the_pod_list_with_a_sample_every_5_s_leaves_the_run_valid() {
        List<ResourceSample> resources = new ArrayList<>(samples(0, 240_000));
        for (double at = 0; at <= 240_000; at += 5_000) {
            resources.add(new ResourceSample(at, "thesis-worker2", "thesis", D, "probe", 1, 1, 0));
        }
        RunRecord record = withPods(TestRuns.s1(cleanS1(), resources), List.of(pod(A, "thesis-worker")),
                List.of(pod(A, "thesis-worker"), pod(D, "thesis-worker2")));

        RunMetrics metrics = s1.evaluate(record);

        assertEquals(VALID, metrics.verdict(), metrics.invalidReasons().toString());
    }

    @Test
    void check_7_with_empty_pod_lists_only_the_nodes_of_the_samples_are_checked() {
        List<ResourceSample> gap = samples(0, 240_000).stream()
                .filter(s -> s.at() != 100_000 && s.at() != 105_000)
                .map(s -> s.at() == 110_000 ? at(s, 110_001) : s).toList();

        RunMetrics clean = s1.evaluate(withPods(TestRuns.s1(cleanS1()), List.of(), List.of()));
        RunMetrics withGap = s1.evaluate(withPods(TestRuns.s1(cleanS1(), gap), List.of(), List.of()));
        RunMetrics withoutSamples = s1.evaluate(withPods(TestRuns.s1(cleanS1(), List.of()), List.of(), List.of()));

        assertEquals(VALID, clean.verdict(), clean.invalidReasons().toString());
        assertEquals(List.of("resource sampling: node thesis-worker has a gap of 15001 ms (limit 15 s)"),
                withGap.invalidReasons());
        assertEquals(List.of("no resource samples"), withoutSamples.invalidReasons());
    }

    @Test
    void check_7_a_pod_without_a_node_names_no_node_to_check() {
        RunRecord record = withPods(TestRuns.s1(cleanS1()), List.of(pod(A, "thesis-worker")),
                List.of(pod(A, "thesis-worker"), pod(D, null)));

        RunMetrics metrics = s1.evaluate(record);

        assertEquals(VALID, metrics.verdict(), metrics.invalidReasons().toString());
    }

    private static RunRecord.PodInfo pod(String name, String node) {
        return new RunRecord.PodInfo("thesis", name, node, "sha256:1234", "2026-11-15T20:00:00Z");
    }

    private static RunRecord withPods(RunRecord record, List<RunRecord.PodInfo> before,
                                      List<RunRecord.PodInfo> after) {
        return new RunRecord(record.runId(), record.campaign(), record.approach(), record.scenario(),
                record.protocolVersion(), record.block(), record.positionInBlock(), record.attempt(),
                record.runStartedAt(), record.runStartedAtEpochMillis(), record.gate(), record.loadStartedAt(),
                record.runEndAt(), record.scheduled(), record.sourceCheck(), record.steps(), record.observations(),
                record.resources(), new RunRecord.ClusterState(before, after, record.cluster().infrastructure(),
                record.cluster().events()), record.errors());
    }

    @Test
    void a_run_without_convergence_is_valid_with_the_flag_NOT_CONVERGED_and_a_censored_p1() {
        List<Observation> observations = load(0, 12_000,
                (pod, at) -> pod.equals(C) || at < 95_000 ? "v1" : "v2");

        RunMetrics metrics = s1.evaluate(TestRuns.s1(observations));

        assertEquals(VALID, metrics.verdict(), metrics.invalidReasons().toString());
        assertEquals(Set.of(NOT_CONVERGED), metrics.flags());
        assertEquals(new Metric(MetricId.P1, "P1", 150_000.0, true, null), metric(metrics, "P1"));
        assertEquals(new Metric(MetricId.P2, "P2", 95_002.0 - 90_100, false, null), metric(metrics, "P2"));
        assertEquals(new Metric(MetricId.C1, "C1", 239_982.0 - 95_002, true, null), metric(metrics, "C1"));
        assertNull(value(metrics, "C1_max"));
    }

    @Test
    void a_run_in_which_the_new_version_never_appears_is_valid_with_the_flag_NOT_CONVERGED() {
        RunMetrics metrics = s1.evaluate(TestRuns.s1(load(0, 12_000, (pod, at) -> "v1")));

        assertEquals(VALID, metrics.verdict(), metrics.invalidReasons().toString());
        assertEquals(Set.of(NOT_CONVERGED), metrics.flags());
        assertEquals(new Metric(MetricId.P1, "P1", 150_000.0, true, null), metric(metrics, "P1"));
        assertEquals(new Metric(MetricId.P2, "P2", 150_000.0, true, null), metric(metrics, "P2"));
        assertEquals(new Metric(MetricId.C1, "C1", 0.0, true, null), metric(metrics, "C1"));
        assertNull(value(metrics, "C1_max"));
    }

    @Test
    void the_runs_csv_row_of_a_run_without_the_new_version_has_a_censored_p1_p2_and_c1() {
        RunRecord record = TestRuns.s1(load(0, 12_000, (pod, at) -> "v1"));

        List<String> row = Evaluator.row(record, s1.evaluate(record), List.of("P1", "P2", "C1", "C1_max"));

        assertEquals(List.of(">150000", ">150000", ">0", ""), row.subList(9, row.size()));
    }

    @Test
    void a_pod_that_answers_v1_after_v2_sets_the_flag_REGRESSION_and_the_run_stays_valid() {
        RunMetrics metrics = s1.evaluate(TestRuns.s1(replace(cleanS1(), answer(6_000, A, "v1"))));

        assertEquals(VALID, metrics.verdict(), metrics.invalidReasons().toString());
        assertEquals(Set.of(REGRESSION), metrics.flags());
        assertEquals(1, value(metrics, "C3"));
        assertEquals(1, value(metrics, "C2"));
        assertEquals(120_002 - 90_100, value(metrics, "P1"));
    }

    @Test
    void a_failed_request_in_H_sets_the_flag_FAILED_REQUESTS_and_the_run_stays_valid() {
        RunMetrics metrics = s1.evaluate(TestRuns.s1(replace(cleanS1(), failure(6_000), failure(6_001))));

        assertEquals(VALID, metrics.verdict(), metrics.invalidReasons().toString());
        assertEquals(Set.of(FAILED_REQUESTS), metrics.flags());
        assertEquals(2.0 / 7_500, value(metrics, "A1"));
        assertEquals(40, value(metrics, "A2"));
    }

    @Test
    void FAILED_REQUESTS_is_set_exactly_when_a1_is_above_0_so_a_failed_request_after_H_sets_no_flag() {
        List<Observation> observations = replace(load(0, 12_100, TestRuns.allSwitchAt(95_000, "v1", "v2")),
                failure(12_050));
        RunRecord record = TestRuns.run("run-1", "way-a", "s1-valid-change", 1, 1, 1,
                List.of(commit(0, 0, V2, 90_000, 90_100)), observations, samples(0, 245_000), 242_000);

        RunMetrics metrics = s1.evaluate(record);

        assertEquals(VALID, metrics.verdict(), metrics.invalidReasons().toString());
        assertEquals(0, value(metrics, "A1"));
        assertEquals(0, value(metrics, "A2"));
        assertEquals(Set.of(), metrics.flags());
    }

    @Test
    void FAILED_REQUESTS_is_set_for_a_failed_request_in_the_last_tick_of_H_where_a1_is_above_0() {
        List<Observation> observations = replace(load(0, 12_100, TestRuns.allSwitchAt(95_000, "v1", "v2")),
                failure(11_999));
        RunRecord record = TestRuns.run("run-1", "way-a", "s1-valid-change", 1, 1, 1,
                List.of(commit(0, 0, V2, 90_000, 90_100)), observations, samples(0, 245_000), 242_000);

        RunMetrics metrics = s1.evaluate(record);

        assertEquals(VALID, metrics.verdict(), metrics.invalidReasons().toString());
        assertEquals(1.0 / 7_500, value(metrics, "A1"));
        assertEquals(Set.of(FAILED_REQUESTS), metrics.flags());
    }

    @Test
    void a_coverage_gap_over_the_limit_inside_H_sets_the_flag_LOW_RESOLUTION_and_the_run_stays_valid() {
        RunMetrics metrics = s1.evaluate(TestRuns.s1(gapOfPodAFrom(6_000, 21)));

        assertEquals(VALID, metrics.verdict(), metrics.invalidReasons().toString());
        assertEquals(Set.of(LOW_RESOLUTION), metrics.flags());
    }

    @Test
    void a_coverage_gap_of_exactly_the_limit_inside_H_sets_no_flag() {
        RunMetrics metrics = s1.evaluate(TestRuns.s1(gapOfPodAFrom(6_000, 22)));

        assertEquals(VALID, metrics.verdict(), metrics.invalidReasons().toString());
        assertEquals(Set.of(), metrics.flags());
    }

    @Test
    void LOW_RESOLUTION_a_coverage_gap_around_the_switch_widens_c1_max_and_a1_and_f2_still_count_in_full() {
        List<Observation> observations = reroute(staggeredSwitch(), 4_773, 4_823, C, B);

        RunMetrics metrics = s1.evaluate(TestRuns.s1(observations));

        assertEquals(VALID, metrics.verdict(), metrics.invalidReasons().toString());
        assertEquals(Set.of(LOW_RESOLUTION), metrics.flags());
        assertEquals(95_442 - 95_042, value(metrics, "C1"));
        assertEquals(400 + 60 + (96_462 - 95_442), value(metrics, "C1_max"));
        assertTrue(value(metrics, "C1_max") > 520);
        assertEquals(95_442 - 90_100, value(metrics, "P1"));
        assertEquals(0, value(metrics, "A1"));
        assertEquals(0, value(metrics, "A2"));
        assertEquals(3, value(metrics, "A3"));
        for (String column : List.of("F2_p50_B", "F2_p95_B", "F2_p99_B", "F2_max_B",
                "F2_p50_H", "F2_p95_H", "F2_p99_H", "F2_max_H")) {
            assertEquals(2, value(metrics, column), column);
        }
        assertEquals(1, value(metrics, "F2_p99_ratio"));
    }

    @Test
    void several_outcome_flags_together_still_leave_the_run_valid() {
        List<Observation> observations = replace(
                load(0, 12_000, (pod, at) -> pod.equals(C) || at < 95_000 ? "v1" : "v2"),
                answer(6_000, A, "v1"), failure(6_001));

        RunMetrics metrics = s1.evaluate(TestRuns.s1(observations));

        assertEquals(VALID, metrics.verdict(), metrics.invalidReasons().toString());
        assertEquals(EnumSet.of(NOT_CONVERGED, REGRESSION, FAILED_REQUESTS), metrics.flags());
    }

    @Test
    void an_invalid_run_lists_every_failed_check() {
        List<Observation> observations = replace(cleanS1(), failure(3_000), answer(6_000, A, "v9"));
        RunRecord record = TestRuns.s1("run-1", "way-a",
                List.of(commit(0, 0, V2, 90_000, 90_100, 2, null)), observations, List.of());

        RunMetrics metrics = s1.evaluate(record);

        assertEquals(INVALID, metrics.verdict());
        assertEquals(List.of("baseline: 1 failed requests", "commit of v2 failed with exit code 2",
                        "versions that no payload of the scenario declares [v9]", "no resource samples"),
                metrics.invalidReasons());
    }

    @Test
    void a_run_without_any_step_is_invalid_and_has_empty_metrics() {
        RunRecord record = TestRuns.s1("run-1", "way-a", List.of(), cleanS1(), samples(0, 240_000));

        RunMetrics metrics = s1.evaluate(record);

        assertEquals(INVALID, metrics.verdict());
        assertEquals(List.of("no step was started, so the baseline is undefined: 0 of 3 replicas were observed in it",
                "commit 1 of the scenario did not run"), metrics.invalidReasons());
        assertFalse(metrics.commitSucceeded());
        assertEquals(32, metrics.metrics().size());
        assertTrue(metrics.metrics().stream().allMatch(m -> m.value() == null && m.text() == null));
    }

    @Test
    void a_run_of_an_approach_without_descriptor_cannot_be_evaluated() {
        RunRecord record = TestRuns.s1("run-1", "way-z",
                List.of(commit(0, 0, V2, 90_000, 90_100)), cleanS1(), samples(0, 240_000));

        assertThrows(IllegalArgumentException.class, () -> s1.evaluate(record));
    }

    @Test
    void s2_reports_E1_to_R1_and_the_hand_calculated_values_of_a_bad_commit_and_its_fix() {
        RunMetrics metrics = s2.evaluate(s2Run(s2Observations(true)));

        assertEquals(List.of("E1", "E2", "E3_ratio", "E3_outage", "E4", "R1"),
                metrics.metrics().stream().map(Metric::column).toList());
        assertEquals(VALID, metrics.verdict(), metrics.invalidReasons().toString());
        assertEquals(EnumSet.of(FAILED_REQUESTS, LOW_RESOLUTION), metrics.flags());
        assertEquals(1, value(metrics, "E1"));
        assertEquals(33, value(metrics, "E2"));
        assertEquals(5.0 / 6_000, value(metrics, "E3_ratio"));
        assertEquals(100, value(metrics, "E3_outage"));
        assertEquals(2, value(metrics, "E4"));
        assertEquals(new Metric(MetricId.R1, "R1", 215_982.0 - 210_080, false, null), metric(metrics, "R1"));
    }

    @Test
    void s2_e3_counts_a_failed_request_exactly_at_the_start_of_the_bad_commit() {
        RunMetrics metrics = s2.evaluate(s2Run(replace(s2WithoutExposure(), failure(4_500))));

        assertEquals(VALID, metrics.verdict(), metrics.invalidReasons().toString());
        assertEquals(1.0 / 6_000, value(metrics, "E3_ratio"));
        assertEquals(20, value(metrics, "E3_outage"));
    }

    @Test
    void s2_e3_does_not_count_a_failed_request_exactly_at_the_start_of_the_fix_commit() {
        RunMetrics metrics = s2.evaluate(s2Run(replace(s2WithoutExposure(), failure(10_500))));

        assertEquals(VALID, metrics.verdict(), metrics.invalidReasons().toString());
        assertEquals(0, value(metrics, "E3_ratio"));
        assertEquals(0, value(metrics, "E3_outage"));
        assertTrue(metrics.flags().contains(FAILED_REQUESTS));
    }

    @Test
    void s2_e4_sees_a_2_s_bucket_with_fewer_pods_that_lies_after_the_first_150_s_of_the_run_after_the_baseline() {
        RunMetrics all = s2.evaluate(s2Run(s2WithoutExposure()));
        RunMetrics fewer = s2.evaluate(s2Run(reroute(s2WithoutExposure(), 15_000, 15_100, C, B)));

        assertEquals(VALID, fewer.verdict(), fewer.invalidReasons().toString());
        assertEquals(3, value(all, "E4"));
        assertEquals(2, value(fewer, "E4"));
    }

    @Test
    void s2_a_pod_that_never_gets_the_fix_sets_the_flag_NOT_RECOVERED_and_censors_r1_at_the_run_end() {
        RunMetrics metrics = s2.evaluate(s2Run(s2Observations(false)));

        assertEquals(VALID, metrics.verdict(), metrics.invalidReasons().toString());
        assertEquals(EnumSet.of(FAILED_REQUESTS, LOW_RESOLUTION, NOT_RECOVERED), metrics.flags());
        assertEquals(new Metric(MetricId.R1, "R1", 360_000.0 - 210_080, true, null), metric(metrics, "R1"));
    }

    @Test
    void s2_does_not_set_NOT_CONVERGED_because_p1_is_not_reported() {
        RunMetrics metrics = s2.evaluate(s2Run(s2Observations(false)));

        assertFalse(metrics.flags().contains(NOT_CONVERGED));
    }

    @Test
    void s3_reports_U1_to_U5_and_the_hand_calculated_values_of_an_outage_with_a_replaced_pod() {
        RunMetrics metrics = s3.evaluate(s3Run(s3Observations(true, true)));

        assertEquals(List.of("U1", "U2", "U3", "U4", "U5"), metrics.metrics().stream().map(Metric::column).toList());
        assertEquals(VALID, metrics.verdict(), metrics.invalidReasons().toString());
        assertEquals(Set.of(), metrics.flags());
        assertEquals(1, value(metrics, "U1"));
        assertEquals(new Metric(MetricId.U2, "U2", 156_002.0 - 150_000, false, "v2"), metric(metrics, "U2"));
        assertEquals(215_982 - 156_002, value(metrics, "U3"));
        assertEquals(new Metric(MetricId.U4, "U4", 215_982.0 - 212_000, false, null), metric(metrics, "U4"));
        assertEquals(0, value(metrics, "U5"));
    }

    @Test
    void s3_v1_still_served_at_the_run_end_sets_the_flag_LOST_UPDATE_and_censors_u4() {
        RunMetrics metrics = s3.evaluate(s3Run(s3Observations(true, false)));

        assertEquals(VALID, metrics.verdict(), metrics.invalidReasons().toString());
        assertEquals(Set.of(LOST_UPDATE), metrics.flags());
        assertEquals(new Metric(MetricId.U4, "U4", 360_000.0 - 212_000, true, null), metric(metrics, "U4"));
        assertEquals(359_942 - 156_002, value(metrics, "U3"));
        assertEquals(1, value(metrics, "U1"));
    }

    @Test
    void s3_without_a_replacement_pod_u2_is_censored_at_the_time_from_delete_pod_to_the_run_end() {
        RunMetrics metrics = s3.evaluate(s3Run(s3Observations(false, true)));

        assertEquals(VALID, metrics.verdict(), metrics.invalidReasons().toString());
        assertEquals(new Metric(MetricId.U2, "U2", 360_000.0 - 150_000, true, null), metric(metrics, "U2"));
        assertEquals(0, value(metrics, "U1"));
        assertEquals(215_982 - 215_002, value(metrics, "U3"));
        assertEquals(215_982 - 212_000, value(metrics, "U4"));
    }

    @Test
    void s3_u5_is_the_failed_request_ratio_over_the_whole_run_after_the_baseline() {
        List<Observation> observations = replace(s3Observations(true, true),
                failure(4_499 + 1), failure(9_000), failure(17_999));

        RunMetrics metrics = s3.evaluate(s3Run(observations));

        assertEquals(VALID, metrics.verdict(), metrics.invalidReasons().toString());
        assertEquals(3.0 / 13_500, value(metrics, "U5"));
        assertTrue(metrics.flags().contains(FAILED_REQUESTS));
    }

    @Test
    void the_runs_csv_row_of_an_s3_run_has_one_cell_per_metric_column_and_the_version_of_the_new_pod_beside_u2() {
        RunRecord record = s3Run(s3Observations(true, true));
        List<String> columns = Evaluator.columns(s3Scenario);

        assertEquals(List.of("protocolVersion", "run_id", "approach", "block", "position", "attempt", "verdict",
                "reasons", "flags", "U1", "U2", "U2_text", "U3", "U4", "U5"), Evaluator.header(columns));
        assertEquals(List.of("1", "run-s3", "way-c", "1", "1", "1", "VALID", "", "",
                "1", "6002", "v2", "59980", "3982", "0"), Evaluator.row(record, s3.evaluate(record), columns));
    }

    @Test
    void the_runs_csv_row_writes_a_censored_value_as_a_greater_than_sign_followed_by_the_number() {
        RunRecord lostUpdate = s3Run(s3Observations(false, false));
        RunRecord notRecovered = s2Run(s2Observations(false));

        assertEquals(List.of("1", "run-s3", "way-c", "1", "1", "1", "VALID", "", "LOST_UPDATE",
                        "0", ">210000", "", "0", ">148000", "0"),
                Evaluator.row(lostUpdate, s3.evaluate(lostUpdate), Evaluator.columns(s3Scenario)));
        assertEquals(List.of("1", "run-s2", "way-b", "1", "1", "1", "VALID", "",
                        "FAILED_REQUESTS LOW_RESOLUTION NOT_RECOVERED",
                        "1", "33", "0.0008333333333333334", "100", "2", ">149920"),
                Evaluator.row(notRecovered, s2.evaluate(notRecovered), Evaluator.columns(s2Scenario)));
    }

    private static List<Observation> staggeredSwitch() {
        return load(0, 12_000, (pod, at) -> {
            double switchAt = pod.equals(A) ? 95_000 : pod.equals(B) ? 95_200 : 95_500;
            return at < switchAt ? "v1" : "v2";
        });
    }

    private static List<Observation> gapOfPodAFrom(long seq, double latency) {
        List<Observation> observations = reroute(cleanS1(), seq + 3, seq + 51, A, B);
        Observation before = observations.stream().filter(o -> o.seq() == seq).findFirst().orElseThrow();
        return replace(observations, answer(seq, A, before.configVersion(), latency));
    }

    private static List<Observation> withoutEveryTenthTickFrom6000(int count) {
        return without(cleanS1(), seq -> seq >= 6_000 && seq < 6_000 + 10L * count && (seq - 6_000) % 10 == 0);
    }

    private static ResourceSample at(ResourceSample s, double at) {
        return new ResourceSample(at, s.node(), s.namespace(), s.pod(), s.container(), s.cpuSeconds(),
                s.workingSetBytes(), s.sourceTimestampMillis());
    }

    private static List<Observation> s2Observations(boolean podCGetsTheFix) {
        List<Observation> observations = load(0, 18_000, (pod, at) -> {
            if (pod.equals(A)) {
                return at >= 100_000 && at < 102_000 ? "v2" : at < 215_000 ? "v1" : "v3";
            }
            if (pod.equals(C) && !podCGetsTheFix) {
                return "v1";
            }
            return at < 216_000 ? "v1" : "v3";
        });
        observations = reroute(observations, 5_100, 10_750, A, B);
        return replace(observations, failure(5_200), failure(5_201), failure(5_202), failure(5_203), failure(5_204));
    }

    private static List<Observation> s2WithoutExposure() {
        return load(0, 18_000, TestRuns.allSwitchAt(215_000, "v1", "v3"));
    }

    private static RunRecord s2Run(List<Observation> observations) {
        return TestRuns.run("run-s2", "way-b", "s2-invalid-config", 1, 1, 1, List.of(
                        commit(0, 0, new Payload("v2", "abc"), 90_000, 90_100),
                        commit(1, 120, new Payload("v3", "1000"), 210_000, 210_080)),
                observations, samples(0, 360_000), 360_000);
    }

    private static List<Observation> s3Observations(boolean replacementPodAnswers, boolean updateArrives) {
        List<Observation> observations = load(0, 18_000, (pod, at) -> {
            if (pod.equals(A)) {
                if (replacementPodAnswers) {
                    return at < 156_000 ? "v1" : "v2";
                }
                return !updateArrives || at < 215_000 ? "v1" : "v2";
            }
            if (!updateArrives) {
                return "v1";
            }
            return at < (pod.equals(B) ? 215_000 : 216_000) ? "v1" : "v2";
        });
        if (!replacementPodAnswers) {
            return reroute(observations, 7_520, 18_000, A, B);
        }
        return reroute(reroute(observations, 7_520, 7_800, A, B), 7_800, 18_000, A, D);
    }

    private static ChangeTrigger.Result failedTrigger() {
        return new ChangeTrigger.Result("busrefresh", 120_100, 135_100, ChangeTrigger.TIMED_OUT,
                "timed out after 15000 ms");
    }

    private static RunRecord s3Run(List<Observation> observations) {
        return s3Run(observations, null);
    }

    private static RunRecord s3Run(List<Observation> observations, ChangeTrigger.Result trigger) {
        return TestRuns.run("run-s3", "way-c", "s3-infra-outage", 1, 1, 1, List.of(
                        step(0, 0, Scenario.Action.INFRA_DOWN, 90_000, 90_500),
                        commit(1, 30, V2, 120_000, 120_100, 0, trigger),
                        step(2, 60, Scenario.Action.DELETE_POD, 150_000, 150_300),
                        step(3, 120, Scenario.Action.INFRA_UP, 210_000, 212_000)),
                observations, samples(0, 360_000), 360_000);
    }

    private static Metric metric(RunMetrics metrics, String column) {
        return metrics.metrics().stream().filter(m -> m.column().equals(column)).findFirst().orElseThrow();
    }

    private static Double value(RunMetrics metrics, String column) {
        return metric(metrics, column).value();
    }
}
