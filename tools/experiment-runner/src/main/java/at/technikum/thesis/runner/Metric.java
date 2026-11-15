package at.technikum.thesis.runner;

public record Metric(MetricId id, String column, Double value, boolean censored, String text) {
}
