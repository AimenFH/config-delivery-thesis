package at.technikum.thesis.runner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

public final class WindowDetector {

    public record Result(
            String oldVersion,
            String newVersion,
            double changeStartedAt,
            double changeFinishedAt,
            Double firstNewAt,
            Double lastOldAt,
            Double windowMillis,
            Double windowMaxMillis,
            Double propagationMillis,
            Double convergenceMillis,
            long failedAfterChange,
            List<String> problems
    ) {
        public boolean valid() {
            return problems.isEmpty();
        }
    }

    private final double maxGapMillis;

    public WindowDetector(double maxGapMillis) {
        this.maxGapMillis = maxGapMillis;
    }

    public Result detect(List<Observation> observations, ChangeTrigger.Result change) {
        double changeStart = change.startedAt();
        double changeEnd = change.finishedAt();
        List<String> problems = new ArrayList<>();

        List<Observation> ok = observations.stream()
                .filter(o -> o.ok() && o.pod() != null && o.configVersion() != null)
                .sorted(Comparator.comparingDouble(Observation::receivedAt))
                .toList();

        Set<String> warmupVersions = ok.stream()
                .filter(o -> o.receivedAt() < changeStart)
                .map(Observation::configVersion)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (warmupVersions.isEmpty()) {
            problems.add("no successful observation before the change");
        } else if (warmupVersions.size() > 1) {
            problems.add("warm-up showed more than one version: " + warmupVersions);
        }
        String oldVersion = warmupVersions.isEmpty() ? null : warmupVersions.iterator().next();

        String newVersion = ok.stream()
                .filter(o -> o.receivedAt() >= changeStart)
                .map(Observation::configVersion)
                .filter(v -> !v.equals(oldVersion))
                .findFirst()
                .orElse(null);

        Set<String> unexpected = ok.stream()
                .map(Observation::configVersion)
                .filter(v -> !v.equals(oldVersion) && !v.equals(newVersion))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!unexpected.isEmpty()) {
            problems.add("unexpected versions observed: " + unexpected);
        }

        double runEnd = observations.stream().mapToDouble(Observation::receivedAt).max().orElse(changeEnd);
        long failed = observations.stream()
                .filter(o -> !o.ok() && o.receivedAt() >= changeStart)
                .count();

        if (oldVersion == null || newVersion == null) {
            if (newVersion == null) {
                problems.add("new version never observed; the run may be too short");
            }
            return new Result(oldVersion, newVersion, changeStart, changeEnd,
                    null, null, null, null, null, null, failed, problems);
        }

        Map<String, List<Observation>> byPod = ok.stream()
                .collect(Collectors.groupingBy(Observation::pod, TreeMap::new, Collectors.toList()));

        Observation firstNew = ok.stream()
                .filter(o -> o.configVersion().equals(newVersion))
                .findFirst().orElseThrow();
        Observation lastOld = ok.stream()
                .filter(o -> o.configVersion().equals(oldVersion))
                .reduce((a, b) -> b).orElseThrow();

        double startUncertainty = firstNew.receivedAt() - byPod.get(firstNew.pod()).stream()
                .filter(o -> o.receivedAt() < firstNew.receivedAt() && o.configVersion().equals(oldVersion))
                .mapToDouble(Observation::receivedAt)
                .max().orElse(Math.max(changeStart, firstNew.receivedAt() - maxGapMillis));
        double endUncertainty = byPod.get(lastOld.pod()).stream()
                .filter(o -> o.receivedAt() > lastOld.receivedAt())
                .mapToDouble(Observation::receivedAt)
                .min().orElse(lastOld.receivedAt() + maxGapMillis) - lastOld.receivedAt();

        double rawWindow = lastOld.receivedAt() - firstNew.receivedAt();
        double convergedAt = Math.max(lastOld.receivedAt(), firstNew.receivedAt());

        for (Map.Entry<String, List<Observation>> entry : byPod.entrySet()) {
            List<Observation> pod = entry.getValue();
            Observation last = pod.get(pod.size() - 1);
            boolean stillServing = runEnd - last.receivedAt() <= maxGapMillis;
            if (stillServing && last.configVersion().equals(oldVersion)) {
                problems.add("pod " + entry.getKey() + " still served " + oldVersion + " at the end of the run");
            }
            double firstNewOnPod = pod.stream()
                    .filter(o -> o.configVersion().equals(newVersion))
                    .mapToDouble(Observation::receivedAt)
                    .min().orElse(Double.POSITIVE_INFINITY);
            long regressions = pod.stream()
                    .filter(o -> o.configVersion().equals(oldVersion) && o.sentAt() > firstNewOnPod)
                    .count();
            if (regressions > 0) {
                problems.add("pod " + entry.getKey() + " served " + oldVersion + " again after "
                        + newVersion + " (" + regressions + " responses)");
            }
        }

        return new Result(oldVersion, newVersion, changeStart, changeEnd,
                firstNew.receivedAt(), lastOld.receivedAt(),
                Math.max(0, rawWindow),
                Math.max(0, rawWindow + startUncertainty + endUncertainty),
                firstNew.receivedAt() - changeEnd,
                convergedAt - changeEnd,
                failed, problems);
    }
}
