package at.technikum.thesis.runner;

import java.util.Collection;
import java.util.List;
import java.util.OptionalDouble;

final class InvalidConfiguration {

    static final double TAIL_MILLIS = 10_000;

    private InvalidConfiguration() {
    }

    static long exposedPods(List<Observation> observations, Interval interval, Collection<String> invalidVersions) {
        return Availability.answers(observations, interval).stream()
                .filter(o -> invalidVersions.contains(o.configVersion()))
                .map(Observation::pod)
                .distinct()
                .count();
    }

    static long exposedRequests(List<Observation> observations, Interval interval,
                                Collection<String> invalidVersions) {
        return Availability.answers(observations, interval).stream()
                .filter(o -> invalidVersions.contains(o.configVersion()))
                .count();
    }

    static Metric recovery(List<Observation> observations, Availability availability, Interval afterBaseline,
                           String fixVersion, Double fixT0) {
        if (fixVersion == null || fixT0 == null) {
            return new Metric(MetricId.R1, "R1", null, false, null);
        }
        Interval tail = new Interval(Math.max(afterBaseline.from(), afterBaseline.to() - TAIL_MILLIS),
                afterBaseline.to());
        boolean notRecovered = availability.failedIn(tail) > 0
                || Availability.answers(observations, tail).stream()
                .anyMatch(o -> !o.configVersion().equals(fixVersion));
        if (notRecovered) {
            return new Metric(MetricId.R1, "R1", afterBaseline.to() - fixT0, true, null);
        }
        OptionalDouble last = observations.stream()
                .filter(o -> afterBaseline.contains(o.intendedAt()))
                .filter(o -> Availability.failed(o) || !o.configVersion().equals(fixVersion))
                .mapToDouble(Observation::receivedAt)
                .max();
        double value = last.isPresent() ? Math.max(0, last.getAsDouble() - fixT0) : 0;
        return new Metric(MetricId.R1, "R1", value, false, null);
    }
}
