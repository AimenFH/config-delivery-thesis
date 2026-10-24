package at.technikum.thesis.runner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class CoverageCheck {

    public record PodCoverage(String pod, int count, double share, double maxGapMillis) {
    }

    public record Result(List<PodCoverage> pods, List<String> problems) {
        public boolean valid() {
            return problems.isEmpty();
        }
    }

    private final int expectedReplicas;
    private final double maxAllowedGapMillis;

    public CoverageCheck(int expectedReplicas, double maxAllowedGapMillis) {
        this.expectedReplicas = expectedReplicas;
        this.maxAllowedGapMillis = maxAllowedGapMillis;
    }

    public Result check(List<Observation> observations) {
        Map<String, List<Double>> seenAt = new TreeMap<>();
        for (Observation o : observations) {
            if (o.ok() && o.pod() != null) {
                seenAt.computeIfAbsent(o.pod(), pod -> new ArrayList<>()).add(o.receivedAt());
            }
        }

        int total = seenAt.values().stream().mapToInt(List::size).sum();
        List<PodCoverage> pods = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        for (Map.Entry<String, List<Double>> entry : seenAt.entrySet()) {
            List<Double> times = entry.getValue();
            times.sort(Double::compare);
            double maxGap = 0;
            for (int i = 1; i < times.size(); i++) {
                maxGap = Math.max(maxGap, times.get(i) - times.get(i - 1));
            }
            pods.add(new PodCoverage(entry.getKey(), times.size(), (double) times.size() / total, maxGap));
            if (maxGap > maxAllowedGapMillis) {
                problems.add(String.format("pod %s was unobserved for %.0f ms (limit %.0f ms)",
                        entry.getKey(), maxGap, maxAllowedGapMillis));
            }
        }

        if (seenAt.size() < expectedReplicas) {
            problems.add(String.format("only %d of %d replicas were observed", seenAt.size(), expectedReplicas));
        }

        return new Result(pods, problems);
    }
}