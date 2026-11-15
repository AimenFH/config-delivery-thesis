package at.technikum.thesis.runner;

import java.util.List;

public record Phases(double firstStepStartedAt, Interval warmup, Interval baseline, Interval observation) {

    public enum Phase {
        WARMUP, BASELINE, OBSERVATION, AFTER
    }

    public static Phases of(Protocol protocol, double firstStepStartedAt) {
        double baselineFrom = firstStepStartedAt - protocol.baselineSeconds() * 1000.0;
        double observationTo = firstStepStartedAt + protocol.observationSeconds() * 1000.0;
        return new Phases(firstStepStartedAt,
                new Interval(0, baselineFrom),
                new Interval(baselineFrom, firstStepStartedAt),
                new Interval(firstStepStartedAt, observationTo));
    }

    public Phase phaseOf(Observation request) {
        double intendedAt = request.intendedAt();
        if (observation.contains(intendedAt)) {
            return Phase.OBSERVATION;
        }
        if (baseline.contains(intendedAt)) {
            return Phase.BASELINE;
        }
        return intendedAt < baseline.from() ? Phase.WARMUP : Phase.AFTER;
    }

    public static List<Observation> in(Interval interval, List<Observation> observations) {
        return observations.stream().filter(o -> interval.contains(o.intendedAt())).toList();
    }
}
