package at.technikum.thesis.runner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class Availability {

    static final double BUCKET_MILLIS = 2_000;

    private final Map<Long, Observation> bySeq = new HashMap<>();
    private final List<Observation> observations;
    private final double loadStartedAt;
    private final double periodMillis;
    private final long scheduled;

    Availability(List<Observation> observations, double loadStartedAt, long scheduled, int ratePerSecond) {
        this.observations = observations;
        this.loadStartedAt = loadStartedAt;
        this.periodMillis = 1000.0 / ratePerSecond;
        this.scheduled = scheduled;
        for (Observation observation : observations) {
            bySeq.putIfAbsent(observation.seq(), observation);
        }
    }

    static boolean failed(Observation observation) {
        return observation == null || !observation.ok()
                || observation.pod() == null || observation.configVersion() == null;
    }

    static List<Observation> answers(List<Observation> observations, Interval interval) {
        return observations.stream()
                .filter(o -> !failed(o) && interval.contains(o.intendedAt()))
                .sorted(Comparator.comparingDouble(Observation::receivedAt).thenComparingLong(Observation::seq))
                .toList();
    }

    double periodMillis() {
        return periodMillis;
    }

    double intendedAt(long seq) {
        return loadStartedAt + seq * periodMillis;
    }

    long scheduledIn(Interval interval) {
        return ticks(interval).size();
    }

    long missingIn(Interval interval) {
        return ticks(interval).stream().filter(seq -> !bySeq.containsKey(seq)).count();
    }

    long failedIn(Interval interval) {
        return ticks(interval).stream().filter(seq -> failed(bySeq.get(seq))).count();
    }

    Double failedRatio(Interval interval) {
        long scheduledInInterval = scheduledIn(interval);
        return scheduledInInterval == 0 ? null : (double) failedIn(interval) / scheduledInInterval;
    }

    double longestOutageMillis(Interval interval) {
        long longest = 0;
        long current = 0;
        for (long seq : ticks(interval)) {
            current = failed(bySeq.get(seq)) ? current + 1 : 0;
            longest = Math.max(longest, current);
        }
        return longest * periodMillis;
    }

    Integer lowestServingPods(Interval interval) {
        int buckets = (int) Math.floor(interval.lengthMillis() / BUCKET_MILLIS);
        if (buckets < 1) {
            return null;
        }
        List<Set<String>> pods = new ArrayList<>();
        for (int i = 0; i < buckets; i++) {
            pods.add(new HashSet<>());
        }
        for (Observation answer : answers(observations, interval)) {
            int bucket = (int) Math.floor((answer.intendedAt() - interval.from()) / BUCKET_MILLIS);
            if (bucket < buckets) {
                pods.get(bucket).add(answer.pod());
            }
        }
        return pods.stream().mapToInt(Set::size).min().orElse(0);
    }

    private List<Long> ticks(Interval interval) {
        List<Long> ticks = new ArrayList<>();
        for (long seq = 0; seq < scheduled; seq++) {
            if (interval.contains(intendedAt(seq))) {
                ticks.add(seq);
            }
        }
        return ticks;
    }
}
