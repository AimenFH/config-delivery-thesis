package at.technikum.thesis.runner;

public final class RunnerClock {

    private final long anchorEpochMillis;
    private final long anchorNanos;

    public RunnerClock() {
        this.anchorEpochMillis = System.currentTimeMillis();
        this.anchorNanos = System.nanoTime();
    }

    public double nowMillis() {
        return anchorEpochMillis + (System.nanoTime() - anchorNanos) / 1_000_000.0;
    }
}