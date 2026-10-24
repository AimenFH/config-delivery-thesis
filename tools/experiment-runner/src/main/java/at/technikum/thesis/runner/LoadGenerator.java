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

    public LoadGenerator(ProbeClient client, RunnerClock clock, int ratePerSecond) {
        this.client = client;
        this.clock = clock;
        this.ratePerSecond = ratePerSecond;
    }

    public List<Observation> run(Duration duration) throws InterruptedException {
        ConcurrentLinkedQueue<Observation> observations = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<CompletableFuture<Void>> inFlight = new ConcurrentLinkedQueue<>();
        long periodNanos = 1_000_000_000L / ratePerSecond;

        ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();
        ticker.scheduleAtFixedRate(() -> {
            double scheduledAt = clock.nowMillis();
            inFlight.add(client.probe(scheduled.getAndIncrement(), scheduledAt).thenAccept(observations::add));
        }, 0, periodNanos, TimeUnit.NANOSECONDS);

        Thread.sleep(duration.toMillis());
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

    public long scheduledCount() {
        return scheduled.get();
    }
}