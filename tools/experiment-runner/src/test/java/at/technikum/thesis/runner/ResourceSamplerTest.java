package at.technikum.thesis.runner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceSamplerTest {

    private static final List<String> NODES_COMMAND = List.of("kubectl", "get", "nodes", "-o", "name");

    private final RunnerClock clock = new RunnerClock();
    private final CountDownLatch release = new CountDownLatch(1);
    private Sampler sampler;

    private static final class Cluster implements CommandRunner {

        private final List<List<String>> commands = Collections.synchronizedList(new ArrayList<>());
        private final Set<RunnerClock> clocks = ConcurrentHashMap.newKeySet();
        private final Map<String, IntFunction<ChangeTrigger.Result>> nodes = new LinkedHashMap<>();
        private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        private int nodesExitCode = 0;

        Cluster node(String name, IntFunction<ChangeTrigger.Result> behaviour) {
            nodes.put(name, behaviour);
            calls.put(name, new AtomicInteger());
            return this;
        }

        int calls(String node) {
            return calls.get(node).get();
        }

        @Override
        public ChangeTrigger.Result run(List<String> argv, RunnerClock usedClock) {
            commands.add(argv);
            clocks.add(usedClock);
            if (argv.equals(NODES_COMMAND)) {
                StringBuilder names = new StringBuilder();
                nodes.keySet().forEach(name -> names.append("node/").append(name).append("\n"));
                return new ChangeTrigger.Result(String.join(" ", argv), 0, 1, nodesExitCode, names.toString().trim());
            }
            String prefix = "/api/v1/nodes/";
            String path = argv.get(3);
            String node = path.substring(prefix.length(), path.indexOf("/proxy/"));
            return nodes.get(node).apply(calls.get(node).incrementAndGet());
        }
    }

    private static ChangeTrigger.Result ok(String output) {
        return new ChangeTrigger.Result("kubectl", 0, 1, 0, output);
    }

    private static ChangeTrigger.Result failed() {
        return new ChangeTrigger.Result("kubectl", 0, 1, 1, "Error from server (ServiceUnavailable)");
    }

    private static String container(String namespace, String pod, String container, String cpu, String memory) {
        String labels = "{container=\"" + container + "\",namespace=\"" + namespace + "\",pod=\"" + pod + "\"} ";
        return "container_cpu_usage_seconds_total" + labels + cpu + " 1794749463760\n"
                + "container_memory_working_set_bytes" + labels + memory + " 1794749463760\n";
    }

    private static String onePod(String pod) {
        return container("thesis", pod, "probe", "10.5", "3.72846592e+08");
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2000);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "condition not reached within 2 s");
            Thread.sleep(5);
        }
    }

    private static List<Double> rounds(List<ResourceSample> samples) {
        return new ArrayList<>(new TreeSet<>(samples.stream().map(ResourceSample::at).toList()));
    }

    private static Set<String> nodesOfRound(List<ResourceSample> samples, double at) {
        Set<String> nodes = new TreeSet<>();
        samples.stream().filter(s -> s.at() == at).forEach(s -> nodes.add(s.node()));
        return nodes;
    }

    private ChangeTrigger.Result blockUntilReleased() {
        try {
            release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ok(onePod("slow-pod"));
    }

    private static ChangeTrigger.Result after(long millis, String output) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ok(output);
    }

    @AfterEach
    void cleanUp() {
        release.countDown();
        if (sampler != null) {
            sampler.stop();
        }
    }

    @Test
    void the_first_round_starts_at_once_and_reads_every_node_with_the_kubelet_resource_endpoint() throws Exception {
        Cluster cluster = new Cluster()
                .node("thesis-control-plane", n -> ok(onePod("p0")))
                .node("thesis-worker", n -> ok(onePod("p1")))
                .node("thesis-worker2", n -> ok(onePod("p2")))
                .node("thesis-worker3", n -> ok(onePod("p3")));
        sampler = new ResourceSampler(60, cluster);

        sampler.start(clock);
        await(() -> cluster.commands.size() == 5);
        List<ResourceSample> samples = sampler.stop();

        assertEquals(NODES_COMMAND, cluster.commands.get(0));
        assertEquals(Set.of(
                List.of("kubectl", "get", "--raw", "/api/v1/nodes/thesis-control-plane/proxy/metrics/resource"),
                List.of("kubectl", "get", "--raw", "/api/v1/nodes/thesis-worker/proxy/metrics/resource"),
                List.of("kubectl", "get", "--raw", "/api/v1/nodes/thesis-worker2/proxy/metrics/resource"),
                List.of("kubectl", "get", "--raw", "/api/v1/nodes/thesis-worker3/proxy/metrics/resource")),
                Set.copyOf(cluster.commands.subList(1, 5)));
        assertEquals(4, samples.size());
        assertEquals(1, rounds(samples).size());
        assertTrue(samples.get(0).at() >= 0 && samples.get(0).at() < 2000);
        assertEquals(Set.of(clock), cluster.clocks);
    }

    @Test
    void a_container_reading_becomes_a_resource_sample_with_cpu_seconds_working_set_and_source_timestamp()
            throws Exception {
        Cluster cluster = new Cluster().node("thesis-worker", n -> ok(onePod("probe-service-1")));
        sampler = new ResourceSampler(60, cluster);

        sampler.start(clock);
        await(() -> cluster.calls("thesis-worker") == 1);
        List<ResourceSample> samples = sampler.stop();

        assertEquals(1, samples.size());
        assertEquals(new ResourceSample(samples.get(0).at(), "thesis-worker", "thesis", "probe-service-1", "probe",
                10.5, 372846592L, 1794749463760L), samples.get(0));
    }

    @Test
    void the_node_names_are_read_once_and_every_period_starts_a_new_round() throws Exception {
        Cluster cluster = new Cluster()
                .node("a", n -> ok(onePod("pa")))
                .node("b", n -> ok(onePod("pb")))
                .node("c", n -> ok(onePod("pc")));
        sampler = new ResourceSampler(Duration.ofMillis(200), cluster);

        sampler.start(clock);
        await(() -> cluster.calls("a") >= 3 && cluster.calls("b") >= 3 && cluster.calls("c") >= 3);
        List<ResourceSample> samples = sampler.stop();

        List<Double> rounds = rounds(samples);
        assertTrue(rounds.size() >= 3);
        assertTrue(rounds.get(1) >= 199.0);
        assertTrue(rounds.get(2) >= 399.0);
        assertEquals(1, cluster.commands.stream().filter(NODES_COMMAND::equals).count());
    }

    @Test
    void every_sample_of_a_round_has_the_same_at() throws Exception {
        Cluster cluster = new Cluster()
                .node("a", n -> ok(onePod("pa") + container("platform", "infra-a", "main", "1.0", "100")))
                .node("b", n -> ok(onePod("pb")))
                .node("c", n -> ok(onePod("pc")));
        sampler = new ResourceSampler(Duration.ofMillis(200), cluster);

        sampler.start(clock);
        await(() -> cluster.calls("a") >= 3 && cluster.calls("b") >= 3 && cluster.calls("c") >= 3);
        List<ResourceSample> samples = sampler.stop();

        List<Double> rounds = rounds(samples);
        for (int round = 0; round < 3; round++) {
            double at = rounds.get(round);
            assertEquals(4, samples.stream().filter(s -> s.at() == at).count());
            assertEquals(Set.of("a", "b", "c"), nodesOfRound(samples, at));
        }
    }

    @Test
    void a_node_whose_read_fails_leaves_a_hole_in_that_round_and_the_sampler_goes_on() throws Exception {
        Cluster cluster = new Cluster()
                .node("a", n -> ok(onePod("pa")))
                .node("b", n -> n == 2 ? failed() : ok(onePod("pb")))
                .node("c", n -> ok(onePod("pc")));
        sampler = new ResourceSampler(Duration.ofMillis(200), cluster);

        sampler.start(clock);
        await(() -> cluster.calls("a") >= 3 && cluster.calls("b") >= 3 && cluster.calls("c") >= 3);
        List<ResourceSample> samples = sampler.stop();

        List<Double> rounds = rounds(samples);
        assertEquals(Set.of("a", "b", "c"), nodesOfRound(samples, rounds.get(0)));
        assertEquals(Set.of("a", "c"), nodesOfRound(samples, rounds.get(1)));
        assertEquals(Set.of("a", "b", "c"), nodesOfRound(samples, rounds.get(2)));
    }

    @Test
    void a_node_whose_command_throws_or_answers_unreadable_text_gives_no_samples_and_nothing_is_thrown()
            throws Exception {
        Cluster cluster = new Cluster()
                .node("a", n -> ok(onePod("pa")))
                .node("b", n -> {
                    throw new IllegalStateException("kubectl missing");
                })
                .node("c", n -> ok("<html>502 Bad Gateway</html>"));
        sampler = new ResourceSampler(Duration.ofMillis(200), cluster);

        sampler.start(clock);
        await(() -> cluster.calls("a") >= 3 && cluster.calls("b") >= 3 && cluster.calls("c") >= 3);
        List<ResourceSample> samples = sampler.stop();

        assertTrue(rounds(samples).size() >= 3);
        assertEquals(Set.of("a"), Set.copyOf(samples.stream().map(ResourceSample::node).toList()));
    }

    @Test
    void a_failed_node_listing_gives_no_samples_and_nothing_is_thrown() throws Exception {
        Cluster cluster = new Cluster().node("a", n -> ok(onePod("pa")));
        cluster.nodesExitCode = 1;
        sampler = new ResourceSampler(Duration.ofMillis(100), cluster);

        sampler.start(clock);
        await(() -> cluster.commands.size() >= 1);
        Thread.sleep(300);
        List<ResourceSample> samples = sampler.stop();

        assertEquals(List.of(), samples);
        assertEquals(List.of(NODES_COMMAND), cluster.commands);
    }

    @Test
    void stop_returns_the_samples_ordered_by_at_node_namespace_pod_container() throws Exception {
        String scrambled = container("thesis", "pod-b", "probe", "4.0", "40")
                + container("thesis", "pod-a", "sidecar", "3.0", "30")
                + container("thesis", "pod-a", "probe", "2.0", "20")
                + container("platform", "pod-z", "main", "1.0", "10");
        Cluster cluster = new Cluster()
                .node("n2", n -> ok(onePod("only")))
                .node("n1", n -> ok(scrambled));
        sampler = new ResourceSampler(Duration.ofMillis(200), cluster);

        sampler.start(clock);
        await(() -> cluster.calls("n1") >= 2 && cluster.calls("n2") >= 2);
        List<ResourceSample> samples = sampler.stop();

        List<Double> rounds = rounds(samples);
        List<String> expectedRound = List.of(
                "n1/platform/pod-z/main",
                "n1/thesis/pod-a/probe",
                "n1/thesis/pod-a/sidecar",
                "n1/thesis/pod-b/probe",
                "n2/thesis/only/probe");
        List<String> expected = new ArrayList<>();
        expectedRound.forEach(key -> expected.add("0:" + key));
        expectedRound.forEach(key -> expected.add("1:" + key));
        List<String> actual = samples.stream()
                .map(s -> rounds.indexOf(s.at()) + ":" + s.node() + "/" + s.namespace() + "/" + s.pod() + "/"
                        + s.container())
                .toList();
        assertEquals(expected, actual.subList(0, 10));
        assertEquals(List.of(2.0, 3.0, 4.0, 1.0),
                List.of(samples.get(1).cpuSeconds(), samples.get(2).cpuSeconds(), samples.get(3).cpuSeconds(),
                        samples.get(0).cpuSeconds()));
    }

    @Test
    void a_slow_node_does_not_delay_the_round_of_the_other_nodes_and_no_reads_queue_up_behind_it() throws Exception {
        Cluster cluster = new Cluster();
        cluster.node("fast1", n -> ok(onePod("p1")))
                .node("slow", n -> blockUntilReleased())
                .node("fast2", n -> ok(onePod("p2")));
        sampler = new ResourceSampler(Duration.ofMillis(300), cluster);

        sampler.start(clock);
        await(() -> cluster.calls("fast1") >= 3 && cluster.calls("fast2") >= 3);
        int slowCalls = cluster.calls("slow");
        long stopStarted = System.nanoTime();
        List<ResourceSample> samples = sampler.stop();
        long stopMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stopStarted);

        assertEquals(1, slowCalls);
        assertTrue(stopMillis < 1000);
        List<Double> rounds = rounds(samples);
        assertTrue(rounds.size() >= 3);
        for (int round = 0; round < 3; round++) {
            assertEquals(Set.of("fast1", "fast2"), nodesOfRound(samples, rounds.get(round)));
        }
        assertTrue(rounds.get(1) - rounds.get(0) < 1000);
    }

    @Test
    void a_read_that_takes_longer_than_the_period_contributes_no_samples_for_that_round() throws Exception {
        Cluster cluster = new Cluster()
                .node("fast", n -> ok(onePod("p1")))
                .node("late", n -> after(350, onePod("p2")));
        sampler = new ResourceSampler(Duration.ofMillis(200), cluster);

        sampler.start(clock);
        await(() -> cluster.calls("fast") >= 4);
        int lateCalls = cluster.calls("late");
        int fastCalls = cluster.calls("fast");
        List<ResourceSample> samples = sampler.stop();

        assertEquals(Set.of("fast"), Set.copyOf(samples.stream().map(ResourceSample::node).toList()));
        assertTrue(lateCalls >= 1 && lateCalls < fastCalls);
    }

    @Test
    void stop_ends_the_sampling() throws Exception {
        Cluster cluster = new Cluster().node("a", n -> ok(onePod("pa")));
        sampler = new ResourceSampler(Duration.ofMillis(100), cluster);

        sampler.start(clock);
        await(() -> cluster.calls("a") >= 2);
        List<ResourceSample> samples = sampler.stop();
        int callsAtStop = cluster.calls("a");
        Thread.sleep(350);

        assertEquals(callsAtStop, cluster.calls("a"));
        assertEquals(samples, sampler.stop());
    }

    @Test
    void the_sampler_is_a_sampler_built_from_the_protocol_period_of_5_seconds() throws Exception {
        Protocol protocol = Fixtures.protocol();
        assertEquals(5, protocol.resourceSamplingSeconds());
        Cluster cluster = new Cluster().node("a", n -> ok(onePod("pa")));
        sampler = new ResourceSampler(protocol.resourceSamplingSeconds(), cluster);

        sampler.start(clock);
        await(() -> cluster.calls("a") == 1);
        Thread.sleep(400);
        List<ResourceSample> samples = sampler.stop();

        assertEquals(1, cluster.calls("a"));
        assertEquals(1, samples.size());
        assertSame(ResourceSampler.class, sampler.getClass());
    }
}
