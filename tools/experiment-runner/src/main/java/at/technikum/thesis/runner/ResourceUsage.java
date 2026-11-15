package at.technikum.thesis.runner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

final class ResourceUsage {

    static final double BYTES_PER_MIB = 1024.0 * 1024.0;

    record Usage(Double cpuMillicores, Double memoryMiB) {
    }

    private ResourceUsage() {
    }

    static Usage of(List<ResourceSample> samples, Interval interval, Predicate<ResourceSample> selected) {
        List<ResourceSample> inside = samples.stream().filter(s -> interval.contains(s.at())).toList();
        List<ResourceSample> chosen = inside.stream().filter(selected).toList();
        if (chosen.isEmpty()) {
            return new Usage(null, null);
        }
        double first = inside.stream().mapToDouble(ResourceSample::at).min().orElseThrow();
        double last = inside.stream().mapToDouble(ResourceSample::at).max().orElseThrow();

        Map<String, List<ResourceSample>> byContainer = new TreeMap<>();
        Map<Double, Long> workingSetAt = new TreeMap<>();
        for (ResourceSample sample : chosen) {
            byContainer.computeIfAbsent(sample.namespace() + "/" + sample.pod() + "/" + sample.container(),
                    key -> new ArrayList<>()).add(sample);
            workingSetAt.merge(sample.at(), sample.workingSetBytes(), Long::sum);
        }

        double cpuSeconds = 0;
        for (List<ResourceSample> container : byContainer.values()) {
            container.sort(Comparator.comparingDouble(ResourceSample::at));
            for (int i = 1; i < container.size(); i++) {
                double increment = container.get(i).cpuSeconds() - container.get(i - 1).cpuSeconds();
                if (increment > 0) {
                    cpuSeconds += increment;
                }
            }
        }

        Double cpu = last > first ? Double.valueOf(cpuSeconds / ((last - first) / 1000.0) * 1000.0) : null;
        long highest = workingSetAt.values().stream().mapToLong(Long::longValue).max().orElse(0);
        return new Usage(cpu, highest / BYTES_PER_MIB);
    }
}
