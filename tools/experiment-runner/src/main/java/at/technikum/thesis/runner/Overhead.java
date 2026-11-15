package at.technikum.thesis.runner;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

final class Overhead {

    static final String SEPARATOR = "; ";

    private Overhead() {
    }

    static ResourceUsage.Usage infrastructure(List<ResourceSample> samples, Interval interval,
                                              List<ApproachDescriptor.Infrastructure> infrastructure) {
        return ResourceUsage.of(samples, interval, sample -> infrastructure.stream()
                .anyMatch(entry -> belongsTo(sample, entry.namespace(), entry.deployment())));
    }

    static ResourceUsage.Usage workload(List<ResourceSample> samples, Interval interval,
                                        String namespace, String deployment) {
        return ResourceUsage.of(samples, interval, sample -> belongsTo(sample, namespace, deployment));
    }

    static long podsReplaced(List<Observation> observations, Interval baseline, Interval observation,
                             double endMillis) {
        Set<String> before = pods(observations, baseline);
        Interval end = new Interval(Math.max(observation.from(), observation.to() - endMillis), observation.to());
        Set<String> atTheEnd = pods(observations, end);
        atTheEnd.removeAll(before);
        return atTheEnd.size();
    }

    static List<Metric> declared(ApproachDescriptor descriptor, RunRecord.ClusterState cluster) {
        ApproachDescriptor.Declared declared = descriptor.declared();
        List<Metric> metrics = new ArrayList<>();
        metrics.add(new Metric(MetricId.O5, "O5_extraComponents", (double) declared.extraComponents().size(),
                false, String.join(SEPARATOR, declared.extraComponents())));
        metrics.add(new Metric(MetricId.O5, "O5_operatorActions", (double) declared.operatorActionsPerChange(),
                false, null));
        metrics.add(new Metric(MetricId.O5, "O5_clusterWideRbac", declared.clusterWideRbac() ? 1.0 : 0.0,
                false, null));
        metrics.add(new Metric(MetricId.O5, "O5_serviceCoupling", null, false, declared.serviceCoupling()));
        metrics.add(new Metric(MetricId.O5, "O5_requestedCpu", null, false,
                requested(descriptor, cluster, RunRecord.InfraState::cpuRequest)));
        metrics.add(new Metric(MetricId.O5, "O5_requestedMemory", null, false,
                requested(descriptor, cluster, RunRecord.InfraState::memoryRequest)));
        return metrics;
    }

    private static String requested(ApproachDescriptor descriptor, RunRecord.ClusterState cluster,
                                    Function<RunRecord.InfraState, String> request) {
        List<RunRecord.InfraState> states = cluster == null || cluster.infrastructure() == null
                ? List.of() : cluster.infrastructure();
        return descriptor.infrastructure().stream()
                .map(entry -> entry.deployment() + "=" + states.stream()
                        .filter(state -> entry.namespace().equals(state.namespace())
                                && entry.deployment().equals(state.deployment()))
                        .findFirst()
                        .map(state -> request.apply(state) == null ? "none" : request.apply(state))
                        .orElse("unknown"))
                .collect(Collectors.joining(SEPARATOR));
    }

    private static Set<String> pods(List<Observation> observations, Interval interval) {
        return Availability.answers(observations, interval).stream()
                .map(Observation::pod)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static boolean belongsTo(ResourceSample sample, String namespace, String deployment) {
        return namespace.equals(sample.namespace())
                && sample.pod() != null && sample.pod().startsWith(deployment + "-");
    }
}
