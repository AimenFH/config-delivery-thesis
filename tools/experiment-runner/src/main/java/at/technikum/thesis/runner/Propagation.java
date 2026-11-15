package at.technikum.thesis.runner;

final class Propagation {

    private Propagation() {
    }

    static Metric convergence(WindowDetector.Window window, Double t0, boolean stillServed, Interval observation) {
        if (stillServed) {
            return new Metric(MetricId.P1, "P1", observation.lengthMillis(), true, null);
        }
        Double convergedAt = window.convergedAt();
        Double value = convergedAt == null || t0 == null ? null : Double.valueOf(convergedAt - t0);
        return new Metric(MetricId.P1, "P1", value, false, null);
    }

    static Metric firstActivation(WindowDetector.Window window, Double t0) {
        Double value = window.firstNewAt() == null || t0 == null ? null : Double.valueOf(window.firstNewAt() - t0);
        return new Metric(MetricId.P2, "P2", value, false, null);
    }

    static Metric commitDuration(Double tCs, Double t0) {
        Double value = tCs == null || t0 == null ? null : Double.valueOf(t0 - tCs);
        return new Metric(MetricId.P3, "P3", value, false, null);
    }
}
