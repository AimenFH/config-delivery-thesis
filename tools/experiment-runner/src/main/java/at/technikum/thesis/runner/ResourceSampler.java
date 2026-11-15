package at.technikum.thesis.runner;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public final class ResourceSampler implements Sampler {

    static final Comparator<ResourceSample> ORDER = Comparator.comparingDouble(ResourceSample::at)
            .thenComparing(ResourceSample::node)
            .thenComparing(ResourceSample::namespace)
            .thenComparing(ResourceSample::pod)
            .thenComparing(ResourceSample::container);

    private static final String NODE_PREFIX = "node/";

    private final Duration period;
    private final CommandRunner runner;
    private final Object lock = new Object();
    private final List<ResourceSample> samples = new ArrayList<>();
    private final Set<String> reading = ConcurrentHashMap.newKeySet();

    private ScheduledExecutorService scheduler;
    private ExecutorService readers;
    private boolean closed;
    private volatile boolean stopped;
    private volatile RunnerClock clock;
    private volatile List<String> nodes = List.of();
    private volatile long lastRoundNanos;
    private volatile boolean roundStarted;

    public ResourceSampler(int periodSeconds, CommandRunner runner) {
        this(Duration.ofSeconds(periodSeconds), runner);
    }

    ResourceSampler(Duration period, CommandRunner runner) {
        if (period.isZero() || period.isNegative()) {
            throw new IllegalArgumentException("sampling period must be positive: " + period);
        }
        this.period = period;
        this.runner = runner;
    }

    static List<String> nodesCommand() {
        return List.of("kubectl", "get", "nodes", "-o", "name");
    }

    static List<String> readCommand(String node) {
        return List.of("kubectl", "get", "--raw", "/api/v1/nodes/" + node + "/proxy/metrics/resource");
    }

    @Override
    public synchronized void start(RunnerClock clock) {
        if (scheduler != null) {
            return;
        }
        this.clock = clock;
        ThreadFactory threads = task -> {
            Thread thread = new Thread(task, "resource-sampler");
            thread.setDaemon(true);
            return thread;
        };
        scheduler = Executors.newSingleThreadScheduledExecutor(threads);
        readers = Executors.newCachedThreadPool(threads);
        scheduler.execute(this::begin);
    }

    @Override
    public synchronized List<ResourceSample> stop() {
        stopped = true;
        if (scheduler != null) {
            long deadline = roundStarted ? lastRoundNanos + period.toNanos() : System.nanoTime();
            scheduler.shutdown();
            await(scheduler, deadline);
            readers.shutdown();
            await(readers, deadline);
        }
        synchronized (lock) {
            closed = true;
            List<ResourceSample> result = new ArrayList<>(samples);
            result.sort(ORDER);
            return result;
        }
    }

    private void begin() {
        try {
            nodes = nodeNames();
            scheduler.scheduleAtFixedRate(this::round, 0, period.toNanos(), TimeUnit.NANOSECONDS);
        } catch (RuntimeException e) {
            nodes = List.of();
        }
    }

    private List<String> nodeNames() {
        ChangeTrigger.Result result = runner.run(nodesCommand(), clock);
        List<String> names = new ArrayList<>();
        if (result == null || result.exitCode() != 0 || result.output() == null) {
            return names;
        }
        for (String line : result.output().split("\n")) {
            String name = line.trim();
            if (name.startsWith(NODE_PREFIX) && name.length() > NODE_PREFIX.length()) {
                names.add(name.substring(NODE_PREFIX.length()));
            }
        }
        return names;
    }

    private void round() {
        try {
            if (stopped) {
                return;
            }
            double at = clock.millisSinceStart();
            lastRoundNanos = System.nanoTime();
            roundStarted = true;
            for (String node : nodes) {
                if (reading.add(node)) {
                    readers.execute(() -> read(node, at));
                }
            }
        } catch (RuntimeException e) {
            reading.clear();
        }
    }

    private void read(String node, double at) {
        try {
            ChangeTrigger.Result result = runner.run(readCommand(node), clock);
            if (result == null || result.exitCode() != 0) {
                return;
            }
            List<ResourceMetricsParser.Reading> readings = ResourceMetricsParser.parse(result.output(), node);
            synchronized (lock) {
                if (closed || clock.millisSinceStart() - at >= period.toNanos() / 1_000_000.0) {
                    return;
                }
                for (ResourceMetricsParser.Reading one : readings) {
                    samples.add(new ResourceSample(at, one.node(), one.namespace(), one.pod(),
                            one.container(), one.cpuSeconds(), one.workingSetBytes(),
                            one.sourceTimestampMillis()));
                }
            }
        } catch (RuntimeException e) {
            return;
        } finally {
            reading.remove(node);
        }
    }

    private static void await(ExecutorService executor, long deadlineNanos) {
        try {
            executor.awaitTermination(Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
