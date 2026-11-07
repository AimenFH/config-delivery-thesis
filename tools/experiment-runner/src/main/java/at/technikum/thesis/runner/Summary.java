package at.technikum.thesis.runner;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

public final class Summary {

    public record Entry(String way, RunResult result) {
    }

    public record Count(String way, int runs, int valid) {
    }

    public record Stat(String way, String metric, int n, double min, double median, double max) {
    }

    private static final Map<String, Function<WindowDetector.Result, Number>> METRICS = new LinkedHashMap<>();

    static {
        METRICS.put("propagationMillis", WindowDetector.Result::propagationMillis);
        METRICS.put("windowMillis", WindowDetector.Result::windowMillis);
        METRICS.put("windowMaxMillis", WindowDetector.Result::windowMaxMillis);
        METRICS.put("convergenceMillis", WindowDetector.Result::convergenceMillis);
        METRICS.put("failedAfterChange", WindowDetector.Result::failedAfterChange);
    }

    private final List<Count> counts;
    private final List<Stat> stats;

    private Summary(List<Count> counts, List<Stat> stats) {
        this.counts = counts;
        this.stats = stats;
    }

    public static Summary of(List<Entry> entries) {
        Map<String, List<RunResult>> byWay = new LinkedHashMap<>();
        for (Entry entry : entries) {
            byWay.computeIfAbsent(entry.way(), way -> new ArrayList<>()).add(entry.result());
        }

        List<Count> counts = new ArrayList<>();
        List<Stat> stats = new ArrayList<>();
        for (Map.Entry<String, List<RunResult>> way : byWay.entrySet()) {
            List<RunResult> runs = way.getValue();
            List<WindowDetector.Result> windows = runs.stream()
                    .filter(RunResult::valid)
                    .map(RunResult::window)
                    .filter(w -> w != null && w.windowMillis() != null)
                    .toList();
            counts.add(new Count(way.getKey(), runs.size(), (int) runs.stream().filter(RunResult::valid).count()));
            for (Map.Entry<String, Function<WindowDetector.Result, Number>> metric : METRICS.entrySet()) {
                double[] values = windows.stream()
                        .mapToDouble(w -> metric.getValue().apply(w).doubleValue())
                        .sorted()
                        .toArray();
                if (values.length > 0) {
                    stats.add(new Stat(way.getKey(), metric.getKey(), values.length,
                            values[0], median(values), values[values.length - 1]));
                }
            }
        }
        return new Summary(counts, stats);
    }

    public List<Count> counts() {
        return counts;
    }

    public List<Stat> stats() {
        return stats;
    }

    public boolean allValid() {
        return counts.stream().allMatch(c -> c.valid() == c.runs());
    }

    public boolean reached(int validRunsPerWay) {
        return !counts.isEmpty() && counts.stream().allMatch(c -> c.valid() >= validRunsPerWay);
    }

    public void print(PrintStream out) {
        out.println("Campaign summary (valid runs only)");
        for (Count count : counts) {
            out.printf("  %s: %d runs, %d valid%n", count.way(), count.runs(), count.valid());
            out.printf("    %-20s %3s %12s %12s %12s%n", "metric", "n", "min", "median", "max");
            for (Stat stat : stats) {
                if (stat.way().equals(count.way())) {
                    out.printf(Locale.ROOT, "    %-20s %3d %12.1f %12.1f %12.1f%n",
                            stat.metric(), stat.n(), stat.min(), stat.median(), stat.max());
                }
            }
        }
    }

    public void writeCsv(Path file) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("way,metric,n,min,median,max");
        for (Stat stat : stats) {
            lines.add(String.format(Locale.ROOT, "%s,%s,%d,%.1f,%.1f,%.1f",
                    stat.way(), stat.metric(), stat.n(), stat.min(), stat.median(), stat.max()));
        }
        Files.write(file, lines);
    }

    static double median(double[] sorted) {
        int middle = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
    }
}
