package at.technikum.thesis.runner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

final class InfrastructureOutage {

    private record Edge(double at, String version, int change) {
    }

    private record Overlap(double mixedMillis, int mostVersions) {
    }

    private InfrastructureOutage() {
    }

    static long deliveredDuringOutage(List<Observation> observations, String newVersion, double infraUpStartedAt) {
        return observations.stream()
                .filter(o -> !Availability.failed(o))
                .filter(o -> o.configVersion().equals(newVersion) && o.receivedAt() < infraUpStartedAt)
                .map(Observation::pod)
                .distinct()
                .count();
    }

    static Metric replacementPod(List<Observation> observations, double deletePodStartedAt, double runEndAt) {
        List<Observation> answers = observations.stream()
                .filter(o -> !Availability.failed(o))
                .sorted(Comparator.comparingDouble(Observation::receivedAt).thenComparingLong(Observation::seq))
                .toList();
        Set<String> seenBefore = answers.stream()
                .filter(o -> o.receivedAt() < deletePodStartedAt)
                .map(Observation::pod)
                .collect(Collectors.toSet());
        Optional<Observation> first = answers.stream()
                .filter(o -> o.receivedAt() >= deletePodStartedAt && !seenBefore.contains(o.pod()))
                .findFirst();
        if (first.isEmpty()) {
            return new Metric(MetricId.U2, "U2", runEndAt - deletePodStartedAt, true, null);
        }
        return new Metric(MetricId.U2, "U2", first.get().receivedAt() - deletePodStartedAt, false,
                first.get().configVersion());
    }

    static double mixedVersionMillis(List<Observation> observations, Interval interval) {
        return overlap(observations, interval).mixedMillis();
    }

    static int mostVersionsAtOnce(List<Observation> observations, Interval interval) {
        return overlap(observations, interval).mostVersions();
    }

    private static Overlap overlap(List<Observation> observations, Interval interval) {
        Map<String, List<Observation>> byPod = Availability.answers(observations, interval).stream()
                .collect(Collectors.groupingBy(Observation::pod, TreeMap::new, Collectors.toList()));
        List<Edge> edges = new ArrayList<>();
        for (List<Observation> pod : byPod.values()) {
            for (int i = 1; i < pod.size(); i++) {
                Observation previous = pod.get(i - 1);
                Observation current = pod.get(i);
                if (previous.configVersion().equals(current.configVersion())) {
                    edges.add(new Edge(previous.receivedAt(), current.configVersion(), 1));
                    edges.add(new Edge(current.receivedAt(), current.configVersion(), -1));
                }
            }
        }
        edges.sort(Comparator.comparingDouble(Edge::at));
        Map<String, Integer> serving = new HashMap<>();
        int versions = 0;
        int most = 0;
        double total = 0;
        double previousAt = 0;
        for (Edge edge : edges) {
            if (edge.at() > previousAt) {
                most = Math.max(most, versions);
            }
            if (versions >= 2) {
                total += edge.at() - previousAt;
            }
            int before = serving.getOrDefault(edge.version(), 0);
            int after = before + edge.change();
            serving.put(edge.version(), after);
            if (before == 0 && after > 0) {
                versions++;
            } else if (before > 0 && after == 0) {
                versions--;
            }
            previousAt = edge.at();
        }
        return new Overlap(total, most);
    }

    static Metric catchUp(WindowDetector.Window window, boolean stillServed, Double infraUpFinishedAt,
                          double runEndAt) {
        if (infraUpFinishedAt == null) {
            return new Metric(MetricId.U4, "U4", null, false, null);
        }
        if (stillServed) {
            return new Metric(MetricId.U4, "U4", runEndAt - infraUpFinishedAt, true, null);
        }
        return new Metric(MetricId.U4, "U4", Math.max(0, window.convergedAt() - infraUpFinishedAt), false, null);
    }
}
