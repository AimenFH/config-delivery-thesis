package at.technikum.thesis.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

final class Fakes {

    static final List<String> PODS = List.of("pod-a", "pod-b", "pod-c");

    private Fakes() {
    }

    static ObjectNode resource(String name) {
        try (InputStream in = Fakes.class.getResourceAsStream("/" + name)) {
            return (ObjectNode) Fixtures.JSON.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static List<Observation> answers(int count, List<String> pods, String version, double latencyMillis) {
        List<Observation> observations = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            observations.add(new Observation(i, i, i, i, i + latencyMillis, 200, pods.get(i % pods.size()), version,
                    0, null));
        }
        return observations;
    }

    static Observation failed(long seq) {
        return new Observation(seq, seq, seq, seq, seq + 1, 0, null, null, 0, "ConnectException");
    }

    static final class VirtualTime implements Pacer {

        private final Map<RunnerClock, Double> times = new IdentityHashMap<>();
        private final List<String> log;
        Predicate<Double> interruptWaitUntil = until -> false;

        VirtualTime(List<String> log) {
            this.log = log;
        }

        @Override
        public double now(RunnerClock clock) {
            return times.getOrDefault(clock, 0.0);
        }

        @Override
        public void sleepUntil(RunnerClock clock, double millis) throws InterruptedException {
            double from = now(clock);
            log.add(String.format(Locale.ROOT, "wait %.0f until %.0f", Math.max(0, millis - from), millis));
            if (interruptWaitUntil.test(millis)) {
                throw new InterruptedException("interrupted in the test");
            }
            times.put(clock, Math.max(from, millis));
        }

        void advance(RunnerClock clock, double millis) {
            times.put(clock, now(clock) + millis);
        }
    }

    static final class Kubectl implements CommandRunner {

        private final List<String> log;
        private final VirtualTime time;
        final ObjectNode deployment = resource("invariants/deployment.json");
        final ObjectNode service = resource("invariants/service.json");
        final ObjectNode pods = resource("invariants/pods.json");
        final Map<String, Integer> replicas = new LinkedHashMap<>();
        Predicate<String> fails = command -> false;
        Predicate<String> throwsOn = command -> false;
        Consumer<String> onCommand = command -> {
        };
        double scaleMillis = 0;
        boolean setEnvApplies = true;

        Kubectl(List<String> log, VirtualTime time) {
            this.log = log;
            this.time = time;
        }

        @Override
        public ChangeTrigger.Result run(List<String> argv, RunnerClock clock) {
            String command = String.join(" ", argv);
            log.add("$ " + command);
            onCommand.accept(command);
            if (throwsOn.test(command)) {
                throw new IllegalStateException("thrown in the test: " + command);
            }
            double startedAt = time.now(clock);
            if (fails.test(command)) {
                return new ChangeTrigger.Result(command, startedAt, startedAt, 1, "failed in the test");
            }
            String output = answer(argv, command);
            if (argv.contains("scale")) {
                time.advance(clock, scaleMillis);
            }
            return new ChangeTrigger.Result(command, startedAt, time.now(clock), 0, output);
        }

        private String answer(List<String> argv, String command) {
            if (!argv.get(0).equals("kubectl") || argv.size() < 5) {
                return "";
            }
            String namespace = argv.get(2);
            if (command.equals("kubectl -n thesis get deployment probe-service -o json")) {
                return deployment.toString();
            }
            if (command.equals("kubectl -n thesis get service probe-service -o json")) {
                return service.toString();
            }
            if (command.equals("kubectl -n thesis get pods -l app=probe-service -o json")
                    || command.equals("kubectl -n thesis get pods -o json")) {
                return pods.toString();
            }
            if (argv.get(3).equals("get") && argv.get(4).equals("pods")) {
                return list(pod(namespace, "infra-pod-1", "thesis-worker2", "infra", "sha256:infra"));
            }
            if (argv.get(3).equals("get") && argv.get(4).equals("events")) {
                ObjectNode event = Fixtures.JSON.createObjectNode();
                event.putObject("metadata").put("namespace", namespace).put("name", "event-of-" + namespace);
                return list(event);
            }
            if (argv.get(3).equals("get") && argv.get(4).equals("deployment")) {
                ObjectNode infra = Fixtures.JSON.createObjectNode();
                ObjectNode spec = infra.putObject("spec");
                spec.put("replicas", replicas.getOrDefault(namespace + "/" + argv.get(5), 1));
                ObjectNode container = spec.putObject("template").putObject("spec").putArray("containers")
                        .addObject();
                container.put("name", argv.get(5));
                container.putObject("resources").putObject("requests").put("cpu", "250m").put("memory", "384Mi");
                return infra.toString();
            }
            if (argv.get(3).equals("set") && argv.get(4).equals("env") && setEnvApplies) {
                String[] assignment = argv.get(6).split("=");
                for (JsonNode variable : deployment.path("spec").path("template")
                        .path("spec").path("containers").get(0).path("env")) {
                    if (variable.path("name").asText().equals(assignment[0])) {
                        ((ObjectNode) variable).put("value", assignment[1]);
                    }
                }
                return "deployment.apps/probe-service env updated";
            }
            if (argv.get(3).equals("scale")) {
                String name = argv.get(4).substring("deployment/".length());
                replicas.put(namespace + "/" + name, Integer.parseInt(argv.get(5).substring("--replicas=".length())));
                return "deployment.apps/" + name + " scaled";
            }
            return "";
        }

        static ObjectNode pod(String namespace, String name, String node, String container, String imageId) {
            ObjectNode pod = Fixtures.JSON.createObjectNode();
            pod.putObject("metadata").put("namespace", namespace).put("name", name);
            pod.putObject("spec").put("nodeName", node);
            ObjectNode status = pod.putObject("status");
            status.put("phase", "Running").put("startTime", "2026-11-15T10:00:00Z");
            status.putArray("containerStatuses").addObject()
                    .put("name", container).put("image", container + ":1").put("imageID", imageId);
            return pod;
        }

        static String list(ObjectNode... items) {
            ObjectNode list = Fixtures.JSON.createObjectNode();
            list.put("apiVersion", "v1").put("kind", "List");
            ArrayNode array = list.putArray("items");
            for (ObjectNode item : items) {
                array.add(item);
            }
            return list.toString();
        }
    }

    static final class Adapter implements DeliveryAdapter {

        private final String name;
        private final List<ApproachDescriptor.Infrastructure> infrastructure;
        private final List<String> log;
        private final VirtualTime time;
        boolean hasTrigger = true;
        boolean hasSourceCheck = true;
        int resetExitCode = 0;
        int deployExitCode = 0;
        int triggerExitCode = 0;
        int sourceCheckExitCode = 0;
        Function<Payload, Integer> commitExitCode = payload -> 0;
        boolean commitThrows = false;
        double commitMillis = 120;
        double triggerMillis = 40;
        double sourceCheckMillis = 350;

        Adapter(String name, List<ApproachDescriptor.Infrastructure> infrastructure, List<String> log,
                VirtualTime time) {
            this.name = name;
            this.infrastructure = infrastructure;
            this.log = log;
            this.time = time;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public List<ChangeTrigger.Result> deploy(RunnerClock clock) {
            return List.of(result("deploy", clock, 0, deployExitCode));
        }

        @Override
        public ChangeTrigger.Result reset(RunnerClock clock) {
            return result("reset", clock, 0, resetExitCode);
        }

        @Override
        public ChangeTrigger.Result commit(Payload payload, RunnerClock clock) {
            if (commitThrows) {
                log.add(name + " commit throws");
                throw new IllegalStateException("thrown in the test: commit");
            }
            return result("commit " + payload.configVersion() + " " + payload.timeoutMs(), clock, commitMillis,
                    commitExitCode.apply(payload));
        }

        @Override
        public Optional<ChangeTrigger.Result> trigger(Payload payload, RunnerClock clock) {
            if (!hasTrigger) {
                return Optional.empty();
            }
            return Optional.of(result("trigger " + payload.configVersion() + " " + payload.timeoutMs(), clock,
                    triggerMillis, triggerExitCode));
        }

        @Override
        public Optional<ChangeTrigger.Result> sourceCheck(RunnerClock clock) {
            if (!hasSourceCheck) {
                return Optional.empty();
            }
            return Optional.of(result("sourceCheck", clock, sourceCheckMillis, sourceCheckExitCode));
        }

        @Override
        public List<ApproachDescriptor.Infrastructure> infrastructure() {
            return infrastructure;
        }

        @Override
        public ApproachDescriptor.Declared declared() {
            return new ApproachDescriptor.Declared(List.of(), 0, false, "none");
        }

        private ChangeTrigger.Result result(String what, RunnerClock clock, double millis, int exitCode) {
            log.add(name + " " + what);
            double startedAt = time.now(clock);
            time.advance(clock, millis);
            return new ChangeTrigger.Result(name + " " + what, startedAt, time.now(clock), exitCode,
                    "output of " + what);
        }
    }

    static final class Probe implements ServiceProbe {

        private final List<String> log;
        private final VirtualTime time;
        final Deque<List<Observation>> snapshots = new ArrayDeque<>();
        Function<Integer, List<Observation>> otherwise = count -> answers(count, PODS, "v1", 5);
        List<Observation> loadObservations = answers(3, PODS, "v1", 5);
        long scheduled = 12_000;
        double loadStartsAfterMillis = 0;
        RunnerClock loadClock;

        Probe(List<String> log, VirtualTime time) {
            this.log = log;
            this.time = time;
        }

        @Override
        public List<Observation> probe(int count, RunnerClock clock) {
            log.add("probe " + count);
            return snapshots.isEmpty() ? otherwise.apply(count) : snapshots.poll();
        }

        @Override
        public Load startLoad(RunnerClock clock) {
            log.add("load start");
            loadClock = clock;
            time.advance(clock, loadStartsAfterMillis);
            double startedAt = time.now(clock);
            return new Load() {
                @Override
                public double startedAt() {
                    return startedAt;
                }

                @Override
                public long scheduledCount() {
                    return scheduled;
                }

                @Override
                public List<Observation> stop() {
                    log.add("load stop");
                    return loadObservations;
                }
            };
        }
    }

    static final class Sampling implements Sampler {

        private final List<String> log;
        final List<ResourceSample> samples = List.of(new ResourceSample(5_000, "thesis-worker", "platform",
                "infra-pod-1", "infra", 12.75, 268_435_456L, 1794769205000L));
        RunnerClock clock;

        Sampling(List<String> log) {
            this.log = log;
        }

        @Override
        public void start(RunnerClock clock) {
            log.add("sampler start");
            this.clock = clock;
        }

        @Override
        public List<ResourceSample> stop() {
            log.add("sampler stop");
            return samples;
        }
    }

    static final class Judge implements RunJudge {

        private final List<String> log;
        final List<RunRecord> records = new ArrayList<>();
        Function<RunRecord, RunMetrics.Verdict> verdict = record -> RunMetrics.Verdict.VALID;

        Judge(List<String> log) {
            this.log = log;
        }

        @Override
        public RunMetrics evaluate(RunRecord record) {
            log.add("judge " + record.approach());
            records.add(record);
            RunMetrics.Verdict result = verdict.apply(record);
            return new RunMetrics(record.runId(), record.approach(), record.scenario(), record.block(), result,
                    result == RunMetrics.Verdict.VALID ? List.of() : List.of("invalid in the test"), Set.of(),
                    true, List.of());
        }
    }

    static final class Store implements RunStore {

        private final List<String> log;
        final List<RunRecord> records = new ArrayList<>();
        final List<RunMetrics> metrics = new ArrayList<>();
        Path directory;

        Store(List<String> log) {
            this.log = log;
        }

        @Override
        public void save(RunRecord record, RunMetrics runMetrics) {
            log.add("store " + record.approach());
            records.add(record);
            metrics.add(runMetrics);
        }
    }

    static final class World {

        final List<String> log = new ArrayList<>();
        final VirtualTime time = new VirtualTime(log);
        final Kubectl kubectl = new Kubectl(log, time);
        final Probe probe = new Probe(log, time);
        final Judge judge = new Judge(log);
        final Store store = new Store(log);
        final Map<String, Adapter> adapters = new LinkedHashMap<>();
        final List<Sampling> samplers = new ArrayList<>();
        final Path results;
        final CampaignConfig.Loaded loaded;

        World(CampaignConfig.Loaded loaded, Path results) {
            this.loaded = loaded;
            this.results = results;
            for (ApproachDescriptor descriptor : loaded.approaches()) {
                Adapter adapter = new Adapter(descriptor.name(), descriptor.infrastructure(), log, time);
                adapter.hasTrigger = descriptor.trigger() != null;
                adapter.hasSourceCheck = descriptor.sourceCheck() != null;
                adapters.put(descriptor.name(), adapter);
            }
        }

        static CampaignConfig.Loaded loaded(String scenario, int validRuns, int maxBlocks, long seed)
                throws IOException {
            return new CampaignConfig("test", "protocol/protocol-v1.json", "scenarios/" + scenario + ".json",
                    validRuns, maxBlocks, seed).load(Fixtures.ROOT);
        }

        static CampaignConfig.Loaded only(CampaignConfig.Loaded loaded, String approach) {
            return new CampaignConfig.Loaded(loaded.config(), loaded.protocol(), loaded.scenario(),
                    loaded.approaches().stream().filter(d -> d.name().equals(approach)).toList());
        }

        Campaign campaign() {
            return new Campaign(loaded, descriptor -> adapters.get(descriptor.name()), () -> {
                Sampling sampler = new Sampling(log);
                samplers.add(sampler);
                return sampler;
            }, judge, directory -> {
                store.directory = directory;
                return store;
            }, kubectl, probe, time, results);
        }

        List<String> logFrom(String entry) {
            return List.copyOf(log.subList(log.indexOf(entry), log.size()));
        }
    }
}
