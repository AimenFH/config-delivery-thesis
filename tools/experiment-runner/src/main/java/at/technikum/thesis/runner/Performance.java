package at.technikum.thesis.runner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class Performance {

    static final double BUCKET_MILLIS = 1_000;
    static final double THRESHOLD_PERCENTILE = 99;
    static final double ALLOWED_SHARE = 0.01;
    static final double WORST_SECOND_PERCENTILE = 95;

    private Performance() {
    }

    static double[] latencies(List<Observation> observations, Interval interval) {
        return Availability.answers(observations, interval).stream()
                .mapToDouble(Observation::latencyMillis)
                .sorted()
                .toArray();
    }

    static Double percentile(double[] sorted, double p) {
        return sorted.length == 0 ? null : Double.valueOf(Statistics.percentile(sorted, p));
    }

    static Double excessSlowRequests(List<Observation> observations, Interval baseline, Interval interval) {
        Double threshold = percentile(latencies(observations, baseline), THRESHOLD_PERCENTILE);
        if (threshold == null) {
            return null;
        }
        double[] latencies = latencies(observations, interval);
        long slow = 0;
        for (double latency : latencies) {
            if (latency > threshold) {
                slow++;
            }
        }
        return slow - ALLOWED_SHARE * latencies.length;
    }

    static List<Metric> percentiles(List<Observation> observations, Interval baseline, Interval interval) {
        double[] b = latencies(observations, baseline);
        double[] h = latencies(observations, interval);
        Double p99B = percentile(b, 99);
        Double p99H = percentile(h, 99);
        Double ratio = p99B == null || p99H == null || p99B == 0 ? null : Double.valueOf(p99H / p99B);
        List<Metric> metrics = new ArrayList<>();
        metrics.add(new Metric(MetricId.F2, "F2_p50_B", percentile(b, 50), false, null));
        metrics.add(new Metric(MetricId.F2, "F2_p95_B", percentile(b, 95), false, null));
        metrics.add(new Metric(MetricId.F2, "F2_p99_B", p99B, false, null));
        metrics.add(new Metric(MetricId.F2, "F2_max_B", percentile(b, 100), false, null));
        metrics.add(new Metric(MetricId.F2, "F2_p50_H", percentile(h, 50), false, null));
        metrics.add(new Metric(MetricId.F2, "F2_p95_H", percentile(h, 95), false, null));
        metrics.add(new Metric(MetricId.F2, "F2_p99_H", p99H, false, null));
        metrics.add(new Metric(MetricId.F2, "F2_max_H", percentile(h, 100), false, null));
        metrics.add(new Metric(MetricId.F2, "F2_p99_ratio", ratio, false, null));
        return metrics;
    }

    static Double worstSecond(List<Observation> observations, Interval interval) {
        int buckets = (int) Math.floor(interval.lengthMillis() / BUCKET_MILLIS);
        Map<Integer, List<Double>> latencies = new TreeMap<>();
        for (Observation answer : Availability.answers(observations, interval)) {
            int bucket = (int) Math.floor((answer.intendedAt() - interval.from()) / BUCKET_MILLIS);
            if (bucket < buckets) {
                latencies.computeIfAbsent(bucket, key -> new ArrayList<>()).add(answer.latencyMillis());
            }
        }
        Double worst = null;
        for (List<Double> bucket : latencies.values()) {
            double[] sorted = bucket.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            double p95 = Statistics.percentile(sorted, WORST_SECOND_PERCENTILE);
            worst = worst == null ? p95 : Math.max(worst, p95);
        }
        return worst;
    }
}
