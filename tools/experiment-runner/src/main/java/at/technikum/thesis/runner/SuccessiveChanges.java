package at.technikum.thesis.runner;

import java.util.List;
import java.util.OptionalDouble;

final class SuccessiveChanges {

    private SuccessiveChanges() {
    }

    static List<String> intermediateVersions(List<Payload> payloads) {
        return payloads.stream()
                .limit(Math.max(0, payloads.size() - 1))
                .map(Payload::configVersion)
                .toList();
    }

    static Metric finalConvergence(List<Observation> observations, Interval afterBaseline, String lastVersion,
                                   Double lastT0) {
        if (lastVersion == null || lastT0 == null) {
            return new Metric(MetricId.Q1, "Q1", null, false, null);
        }
        Interval tail = new Interval(
                Math.max(afterBaseline.from(), afterBaseline.to() - InvalidConfiguration.TAIL_MILLIS),
                afterBaseline.to());
        boolean stillServed = Availability.answers(observations, tail).stream()
                .anyMatch(o -> !o.configVersion().equals(lastVersion));
        if (stillServed) {
            return new Metric(MetricId.Q1, "Q1", afterBaseline.to() - lastT0, true, null);
        }
        OptionalDouble last = Availability.answers(observations, afterBaseline).stream()
                .filter(o -> !o.configVersion().equals(lastVersion))
                .mapToDouble(Observation::receivedAt)
                .max();
        double value = last.isPresent() ? Math.max(0, last.getAsDouble() - lastT0) : 0;
        return new Metric(MetricId.Q1, "Q1", value, false, null);
    }

    static Metric commitGap(List<RunRecord.StepRecord> commits) {
        if (commits.size() < 2) {
            return new Metric(MetricId.Q7, "Q7", null, false, null);
        }
        Double first = commits.get(0).t0();
        Double last = commits.get(commits.size() - 1).t0();
        Double value = first == null || last == null ? null : Double.valueOf(last - first);
        return new Metric(MetricId.Q7, "Q7", value, false, null);
    }
}
