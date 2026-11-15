package at.technikum.thesis.runner;

public record ResourceSample(
        double at,
        String node,
        String namespace,
        String pod,
        String container,
        double cpuSeconds,
        long workingSetBytes,
        long sourceTimestampMillis
) {
}
