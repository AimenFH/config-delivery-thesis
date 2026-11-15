package at.technikum.thesis.runner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

public final class Summary {

    public static final String COUNT = "count";
    public static final String METRIC = "metric";
    public static final String DIFFERENCE = "difference";
    public static final String SENSITIVITY = "sensitivity";

    public static final List<String> HEADER = List.of("kind", "approach", "other", "metric", "n", "n_other",
            "censored", "min", "q1", "median", "q3", "max", "ci_low", "ci_high", "cliffs_delta");

    private static final Set<MetricId> SENSITIVITY_METRICS = Set.of(MetricId.A1, MetricId.F1);

    public record Row(
            String kind,
            String approach,
            String other,
            String metric,
            Integer n,
            Integer nOther,
            Integer censored,
            Double min,
            Double q1,
            Double median,
            Double q3,
            Double max,
            Double ciLow,
            Double ciHigh,
            Double cliffsDelta
    ) {
    }

    private final List<Row> rows;

    private Summary(List<Row> rows) {
        this.rows = rows;
    }

    public static Summary of(List<RunMetrics> runs, List<String> approaches, int resamples, long seed) {
        List<String> order = new ArrayList<>(approaches);
        Set<String> others = new TreeSet<>();
        for (RunMetrics run : runs) {
            if (!order.contains(run.approach())) {
                others.add(run.approach());
            }
        }
        order.addAll(others);

        Map<String, MetricId> columns = new LinkedHashMap<>();
        for (RunMetrics run : runs) {
            for (Metric metric : run.metrics()) {
                columns.putIfAbsent(metric.column(), metric.id());
            }
        }

        Predicate<RunMetrics> valid = run -> run.verdict() == RunMetrics.Verdict.VALID;
        List<Row> rows = new ArrayList<>();
        for (String approach : order) {
            List<RunMetrics> own = runs.stream().filter(run -> run.approach().equals(approach)).toList();
            List<RunMetrics> ownValid = own.stream().filter(valid).toList();
            rows.add(count(approach, "runs", own.size()));
            rows.add(count(approach, "valid", ownValid.size()));
            rows.add(count(approach, "invalid", own.size() - ownValid.size()));
            for (RunMetrics.Flag flag : RunMetrics.Flag.values()) {
                rows.add(count(approach, flag.name(),
                        (int) ownValid.stream().filter(run -> run.flags().contains(flag)).count()));
            }
        }
        for (String approach : order) {
            for (String column : columns.keySet()) {
                rows.add(statistics(METRIC, approach, column, values(runs, approach, column, valid),
                        resamples, seed));
            }
        }
        for (Map.Entry<String, MetricId> column : columns.entrySet()) {
            if (!column.getValue().primary()) {
                continue;
            }
            for (int first = 0; first < order.size(); first++) {
                for (int second = first + 1; second < order.size(); second++) {
                    rows.add(difference(order.get(first), order.get(second), column.getKey(),
                            values(runs, order.get(first), column.getKey(), valid),
                            values(runs, order.get(second), column.getKey(), valid), resamples, seed));
                }
            }
        }
        for (String approach : order) {
            for (Map.Entry<String, MetricId> column : columns.entrySet()) {
                if (SENSITIVITY_METRICS.contains(column.getValue())) {
                    rows.add(statistics(SENSITIVITY, approach, column.getKey(),
                            values(runs, approach, column.getKey(), RunMetrics::commitSucceeded), resamples, seed));
                }
            }
        }
        return new Summary(List.copyOf(rows));
    }

    public List<Row> rows() {
        return rows;
    }

    public List<Row> rows(String kind) {
        return rows.stream().filter(row -> row.kind().equals(kind)).toList();
    }

    public void writeCsv(Path file) throws IOException {
        List<List<String>> lines = new ArrayList<>();
        lines.add(HEADER);
        for (Row row : rows) {
            lines.add(List.of(row.kind(), row.approach(), row.other() == null ? "" : row.other(), row.metric(),
                    Csv.number(row.n()), Csv.number(row.nOther()), Csv.number(row.censored()),
                    Csv.number(row.min()), Csv.number(row.q1()), Csv.number(row.median()), Csv.number(row.q3()),
                    Csv.number(row.max()), Csv.number(row.ciLow()), Csv.number(row.ciHigh()),
                    Csv.number(row.cliffsDelta())));
        }
        Csv.write(file, lines);
    }

    static double[] values(List<RunMetrics> runs, String approach, String column, Predicate<RunMetrics> included) {
        return Statistics.sorted(runs.stream()
                .filter(run -> run.approach().equals(approach))
                .filter(included)
                .flatMap(run -> run.metrics().stream())
                .filter(metric -> metric.column().equals(column) && metric.value() != null)
                .mapToDouble(metric -> metric.censored() ? Double.POSITIVE_INFINITY : metric.value())
                .toArray());
    }

    private static Row count(String approach, String name, int count) {
        return new Row(COUNT, approach, null, name, count, null, null, null, null, null, null, null,
                null, null, null);
    }

    private static Row statistics(String kind, String approach, String column, double[] sorted,
                                  int resamples, long seed) {
        if (sorted.length == 0) {
            return new Row(kind, approach, null, column, 0, null, 0, null, null, null, null, null, null, null, null);
        }
        double[] interval = Statistics.medianInterval(sorted, resamples, Statistics.seed(seed, approach, column));
        return new Row(kind, approach, null, column, sorted.length, null, censored(sorted),
                sorted[0], Statistics.quantile(sorted, 0.25), Statistics.median(sorted),
                Statistics.quantile(sorted, 0.75), sorted[sorted.length - 1], interval[0], interval[1], null);
    }

    private static Row difference(String approach, String other, String column, double[] first, double[] second,
                                  int resamples, long seed) {
        int censored = censored(first) + censored(second);
        if (first.length == 0 || second.length == 0) {
            return new Row(DIFFERENCE, approach, other, column, first.length, second.length, censored,
                    null, null, null, null, null, null, null, null);
        }
        double[] interval = Statistics.medianDifferenceInterval(first, second, resamples,
                Statistics.seed(seed, approach, other, column));
        return new Row(DIFFERENCE, approach, other, column, first.length, second.length, censored,
                null, null, Statistics.median(first) - Statistics.median(second), null, null,
                interval[0], interval[1], Statistics.cliffsDelta(first, second));
    }

    private static int censored(double[] values) {
        int censored = 0;
        for (double value : values) {
            if (Double.isInfinite(value)) {
                censored++;
            }
        }
        return censored;
    }
}
