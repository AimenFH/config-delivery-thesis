package at.technikum.thesis.runner;

import java.time.Instant;

public final class RunnerClock {

    private final long startEpochMillis;
    private final long startNanos;

    public RunnerClock() {
        this.startEpochMillis = System.currentTimeMillis();
        this.startNanos = System.nanoTime();
    }

    public double millisSinceStart() {
        return (System.nanoTime() - startNanos) / 1_000_000.0;
    }

    public long startEpochMillis() {
        return startEpochMillis;
    }

    public String startIso() {
        return Instant.ofEpochMilli(startEpochMillis).toString();
    }
}