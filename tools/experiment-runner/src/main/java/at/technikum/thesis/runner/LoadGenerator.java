package at.technikum.thesis.runner;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class LoadGenerator {

    private final ProbeClient client;
    private final RunnerClock clock;
    private final int ratePerSecond;
    private final AtomicLong scheduled = new AtomicLong();
    private final ConcurrentLinkedQueue<Observation> observations = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<CompletableFuture<Void>> inFlight = new ConcurrentLinkedQueue<>();
    private ScheduledExecutorService ticker;
    private double startedAt = Double.NaN;

    public LoadGenerator(ProbeClient client, RunnerClock clock, int ratePerSecond) {
        this.client = client;
        this.clock = clock;
        this.ratePerSecond = ratePerSecond;
    }

    public synchronized void start() {
        if (ticker != null) {
            throw new IllegalStateException("load generator was already started");
        }
        long periodNanos = 1_000_000_000L / ratePerSecond;
        double periodMillis = periodNanos / 1_000_000.0;
        ticker = Executors.newSingleThreadScheduledExecutor();
        double start = clock.millisSinceStart();
        startedAt = start;
        ticker.scheduleAtFixedRate(() -> {
            double scheduledAt = clock.millisSinceStart();
            long seq = scheduled.getAndIncrement();
            inFlight.add(client.probe(seq, start + seq * periodMillis, scheduledAt).thenAccept(observations::add));
        }, 0, periodNanos, TimeUnit.NANOSECONDS);
    }

    public synchronized List<Observation> stop() throws InterruptedException {
        if (ticker == null) {
            throw new IllegalStateException("load generator was not started");
        }
        ticker.shutdownNow();
        ticker.awaitTermination(1, TimeUnit.SECONDS);

        try {
            CompletableFuture.allOf(inFlight.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }

        return observations.stream()
                .sorted((a, b) -> Long.compare(a.seq(), b.seq()))
                .toList();
    }

    public List<Observation> run(Duration duration) throws InterruptedException {
        start();
        Thread.sleep(duration.toMillis());
        return stop();
    }

    public synchronized double startedAt() {
        return startedAt;
    }

    public long scheduledCount() {
        return scheduled.get();
    }
}
