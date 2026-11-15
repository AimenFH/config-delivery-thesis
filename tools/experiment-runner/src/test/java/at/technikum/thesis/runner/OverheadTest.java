package at.technikum.thesis.runner;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static at.technikum.thesis.runner.TestRuns.seen;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OverheadTest {

    private static final long MIB = 1024 * 1024;
    private static final Interval B_AND_H = new Interval(30_000, 240_000);

    @Test
    void o1_and_o2_cover_the_pods_of_the_infrastructure_deployments_of_the_approach() throws Exception {
        ApproachDescriptor wayC = ApproachDescriptor.read(Fixtures.ROOT.resolve("approaches/way-c/approach.json"));

        ResourceUsage.Usage usage = Overhead.infrastructure(clusterSamples(), B_AND_H, wayC.infrastructure());

        assertEquals((2.0 + 1.0) / 100 * 1000, usage.cpuMillicores(), 1e-9);
        assertEquals(300 + 150, usage.memoryMiB());
    }

    @Test
    void o1_and_o2_of_another_approach_use_its_own_infrastructure_pods() throws Exception {
        ApproachDescriptor wayB = ApproachDescriptor.read(Fixtures.ROOT.resolve("approaches/way-b/approach.json"));

        ResourceUsage.Usage usage = Overhead.infrastructure(clusterSamples(), B_AND_H, wayB.infrastructure());

        assertEquals(0.5 / 100 * 1000, usage.cpuMillicores(), 1e-9);
        assertEquals(90, usage.memoryMiB());
    }

    @Test
    void o3_is_the_same_two_figures_for_the_probe_service_pods() {
        ResourceUsage.Usage usage = Overhead.workload(clusterSamples(), B_AND_H, "thesis", "probe-service");

        assertEquals((4.0 + 6.0) / 100 * 1000, usage.cpuMillicores(), 1e-9);
        assertEquals(255 + 255, usage.memoryMiB());
    }

    @Test
    void a_pod_is_selected_by_its_namespace_and_by_a_name_that_starts_with_the_deployment_name_and_a_dash() {
        List<ResourceSample> samples = List.of(
                sample(40_000, "thesis", "probe-service-extra-abc", 1.0, 10),
                sample(140_000, "thesis", "probe-service-extra-abc", 2.0, 10),
                sample(40_000, "platform", "probe-service-abc", 1.0, 10),
                sample(140_000, "platform", "probe-service-abc", 2.0, 10),
                sample(40_000, "thesis", "probe-servicex-abc", 1.0, 10),
                sample(140_000, "thesis", "probe-servicex-abc", 2.0, 10));

        ResourceUsage.Usage usage = Overhead.workload(samples, B_AND_H, "thesis", "probe-service");

        assertEquals(10, usage.cpuMillicores(), 1e-9);
        assertEquals(10, usage.memoryMiB());
        assertNull(Overhead.workload(samples, B_AND_H, "thesis", "service").cpuMillicores());
    }

    @Test
    void o4_counts_pods_answering_in_the_last_second_of_H_that_did_not_answer_in_B() {
        Interval b = new Interval(30_000, 90_000);
        Interval h = new Interval(90_000, 240_000);
        List<Observation> observations = new ArrayList<>(List.of(
                seen(40_000, 40_002, "old-1", "v1"),
                seen(40_020, 40_022, "old-2", "v1"),
                seen(40_040, 40_042, "old-3", "v1"),
                seen(239_000, 239_002, "old-1", "v2"),
                seen(239_500, 239_502, "new-1", "v2"),
                seen(239_980, 239_982, "new-2", "v2")));

        assertEquals(2, Overhead.podsReplaced(observations, b, h, 1000));
    }

    @Test
    void o4_ignores_pods_seen_only_before_the_last_second_of_H_or_after_H_and_failed_requests() {
        Interval b = new Interval(30_000, 90_000);
        Interval h = new Interval(90_000, 240_000);
        List<Observation> observations = List.of(
                seen(40_000, 40_002, "old-1", "v1"),
                seen(238_999, 239_100, "short-lived", "v2"),
                seen(240_000, 240_002, "late", "v2"),
                new Observation(0, 239_500, 239_500, 239_500, 239_600, 503, "failing", "v2", 0, null),
                seen(239_000, 239_002, "old-1", "v2"));

        assertEquals(0, Overhead.podsReplaced(observations, b, h, 1000));
    }

    @Test
    void o5_reports_the_declared_facts_and_the_requests_of_the_infrastructure_as_columns() throws Exception {
        ApproachDescriptor wayC = ApproachDescriptor.read(Fixtures.ROOT.resolve("approaches/way-c/approach.json"));
        RunRecord.ClusterState cluster = new RunRecord.ClusterState(List.of(), List.of(), List.of(
                new RunRecord.InfraState("platform", "reloader-reloader", 1, "10m", "64Mi"),
                new RunRecord.InfraState("platform", "rabbitmq", 1, null, "512Mi"),
                new RunRecord.InfraState("platform", "config-server", 1, "100m", "256Mi")), null);

        List<Metric> metrics = Overhead.declared(wayC, cluster);

        assertEquals(List.of(
                new Metric(MetricId.O5, "O5_extraComponents", 2.0, false, "Config Server; RabbitMQ"),
                new Metric(MetricId.O5, "O5_operatorActions", 2.0, false, null),
                new Metric(MetricId.O5, "O5_clusterWideRbac", 0.0, false, null),
                new Metric(MetricId.O5, "O5_serviceCoupling", null, false,
                        "config client and bus libraries, startup dependency"),
                new Metric(MetricId.O5, "O5_requestedCpu", null, false, "config-server=100m; rabbitmq=none"),
                new Metric(MetricId.O5, "O5_requestedMemory", null, false, "config-server=256Mi; rabbitmq=512Mi")),
                metrics);
    }

    @Test
    void o5_marks_cluster_wide_rbac_with_one_and_an_infrastructure_missing_from_the_cluster_state_as_unknown()
            throws Exception {
        ApproachDescriptor wayA = ApproachDescriptor.read(Fixtures.ROOT.resolve("approaches/way-a/approach.json"));

        List<Metric> metrics = Overhead.declared(wayA, null);

        assertEquals(new Metric(MetricId.O5, "O5_extraComponents", 1.0, false, "Stakater Reloader"), metrics.get(0));
        assertEquals(1.0, metrics.get(1).value());
        assertEquals(1.0, metrics.get(2).value());
        assertEquals("reloader-reloader=unknown", metrics.get(4).text());
    }

    private static List<ResourceSample> clusterSamples() {
        return List.of(
                sample(40_000, "platform", "config-server-6b7-aaa", 10.0, 300),
                sample(140_000, "platform", "config-server-6b7-aaa", 12.0, 280),
                sample(40_000, "platform", "rabbitmq-0", 50.0, 150),
                sample(140_000, "platform", "rabbitmq-0", 51.0, 150),
                sample(40_000, "platform", "config-watcher-55d-bbb", 7.0, 90),
                sample(140_000, "platform", "config-watcher-55d-bbb", 7.5, 80),
                sample(40_000, "platform", "reloader-reloader-9c8-ccc", 3.0, 40),
                sample(140_000, "platform", "reloader-reloader-9c8-ccc", 3.25, 40),
                sample(40_000, "thesis", "probe-service-5f4-ddd", 20.0, 250),
                sample(140_000, "thesis", "probe-service-5f4-ddd", 24.0, 255),
                sample(40_000, "thesis", "probe-service-5f4-eee", 30.0, 0),
                sample(140_000, "thesis", "probe-service-5f4-eee", 36.0, 255),
                sample(40_000, "kube-system", "config-server-zzz", 1.0, 999),
                sample(140_000, "kube-system", "config-server-zzz", 9.0, 999));
    }

    private static ResourceSample sample(double at, String namespace, String pod, double cpuSeconds, long mib) {
        return new ResourceSample(at, "node-1", namespace, pod, "main", cpuSeconds, mib * MIB, 0);
    }
}
