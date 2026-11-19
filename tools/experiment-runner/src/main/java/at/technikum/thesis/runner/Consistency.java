package at.technikum.thesis.runner;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

final class Consistency {

    private Consistency() {
    }

    static List<Metric> window(WindowDetector.Window window, boolean stillServed) {
        if (stillServed) {
            double seen = window.windowMillis() == null ? 0 : window.windowMillis();
            return List.of(
                    new Metric(MetricId.C1, "C1", seen, true, null),
                    new Metric(MetricId.C1, "C1_max", null, false, null));
        }
        return List.of(
                new Metric(MetricId.C1, "C1", window.windowMillis(), false, null),
                new Metric(MetricId.C1, "C1_max", window.windowMaxMillis(), false, null));
    }

    static long reversions(List<Observation> observations, Interval interval, String oldVersion, String newVersion) {
        List<Observation> answers = Availability.answers(observations, interval);
        long reversions = 0;
        for (int i = 1; i < answers.size(); i++) {
            if (answers.get(i - 1).configVersion().equals(newVersion)
                    && answers.get(i).configVersion().equals(oldVersion)) {
                reversions++;
            }
        }
        return reversions;
    }

    static long regressions(List<Observation> observations, Interval interval, List<String> declaredVersions) {
        Map<String, List<Observation>> byPod = Availability.answers(observations, interval).stream()
                .collect(Collectors.groupingBy(Observation::pod, TreeMap::new, Collectors.toList()));
        long regressions = 0;
        for (List<Observation> pod : byPod.values()) {
            double[] firstReceivedAt = new double[declaredVersions.size()];
            Arrays.fill(firstReceivedAt, Double.POSITIVE_INFINITY);
            for (Observation answer : pod) {
                int index = declaredVersions.indexOf(answer.configVersion());
                if (index >= 0) {
                    firstReceivedAt[index] = Math.min(firstReceivedAt[index], answer.receivedAt());
                }
            }
            for (Observation answer : pod) {
                int index = declaredVersions.indexOf(answer.configVersion());
                if (index < 0) {
                    continue;
                }
                double firstLaterAt = Double.POSITIVE_INFINITY;
                for (int later = index + 1; later < firstReceivedAt.length; later++) {
                    firstLaterAt = Math.min(firstLaterAt, firstReceivedAt[later]);
                }
                if (answer.sentAt() > firstLaterAt) {
                    regressions++;
                }
            }
        }
        return regressions;
    }
}
