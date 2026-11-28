package at.technikum.thesis.runner;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FingerprintTest {

    private static final String IMAGE_ID =
            "docker.io/library/import-2026-11-07@sha256:8c1c747b8a86f64648490b4d8e611978129033afc9bce40f5565aba89a2e3932";
    private static final String GIT_COMMIT = "git rev-parse HEAD";
    private static final String GIT_STATUS = "git status --porcelain";
    private static final String VERSION = "kubectl version -o json";
    private static final String NODES = "kubectl get nodes -o json";
    private static final String WORKLOAD_PODS = "kubectl -n thesis get pods -l app=probe-service -o json";
    private static final String PLATFORM_PODS = "kubectl -n platform get pods -o json";
    private static final String JAVA = "java -version";
    private static final String OS = "uname -sr";
    private static final String MACHINE = "uname -m";
    private static final String DOCKER = "docker info --format {{.NCPU}} {{.MemTotal}}";

    private final Map<String, String> outputs = new LinkedHashMap<>();
    private final List<String> failing = new ArrayList<>();
    private final List<String> throwing = new ArrayList<>();
    private final List<String> commands = new ArrayList<>();
    private final RunnerClock clock = new RunnerClock();
    private final CommandRunner runner = (argv, usedClock) -> {
        String command = String.join(" ", argv);
        commands.add(command);
        if (throwing.contains(command)) {
            throw new IllegalStateException("thrown in the test");
        }
        if (failing.contains(command) || !outputs.containsKey(command)) {
            return new ChangeTrigger.Result(command, 0, 1, 1, "failed in the test");
        }
        return new ChangeTrigger.Result(command, 0, 1, 0, outputs.get(command));
    };

    FingerprintTest() {
        outputs.put(GIT_COMMIT, "ecb21b3aa0c4f1d2b3a4c5d6e7f8091a2b3c4d5e");
        outputs.put(GIT_STATUS, "");
        outputs.put(VERSION, "{\"clientVersion\":{\"gitVersion\":\"v1.36.1\"},"
                + "\"serverVersion\":{\"gitVersion\":\"v1.37.0\"}}");
        outputs.put(NODES, "{\"items\":[{},{},{},{}]}");
        outputs.put(WORKLOAD_PODS, Fakes.resource("invariants/pods.json").toString());
        outputs.put(PLATFORM_PODS, Fakes.Kubectl.list(
                Fakes.Kubectl.pod("platform", "rabbitmq-1", "thesis-worker2", "rabbitmq", "sha256:rabbit")));
        outputs.put(JAVA, "openjdk version \"21.0.4\" 2024-07-16");
        outputs.put(OS, "Darwin 27.0.0");
        outputs.put(MACHINE, "arm64");
        outputs.put(DOCKER, "10 8319504384");
    }

    @Test
    void every_field_of_the_plan_is_read_from_its_command() throws Exception {
        Fingerprint fingerprint = collect();

        assertEquals("ecb21b3aa0c4f1d2b3a4c5d6e7f8091a2b3c4d5e", fingerprint.gitCommit());
        assertEquals(false, fingerprint.gitDirty());
        assertEquals("v1.36.1", fingerprint.kubernetesClientVersion());
        assertEquals("v1.37.0", fingerprint.kubernetesServerVersion());
        assertEquals(4, fingerprint.nodeCount());
        assertEquals(List.of(
                new Fingerprint.Image("thesis", "probe-service-f664c8f55-7hfd6", "probe",
                        "docker.io/library/probe-service:0.3.0", IMAGE_ID),
                new Fingerprint.Image("thesis", "probe-service-f664c8f55-jfsxk", "probe",
                        "docker.io/library/probe-service:0.3.0", IMAGE_ID),
                new Fingerprint.Image("thesis", "probe-service-f664c8f55-ldcdv", "probe",
                        "docker.io/library/probe-service:0.3.0", IMAGE_ID)), fingerprint.workloadImages());
        assertEquals(List.of(new Fingerprint.Image("platform", "rabbitmq-1", "rabbitmq", "rabbitmq:1",
                "sha256:rabbit")), fingerprint.infrastructureImages());
        assertEquals("openjdk version \"21.0.4\" 2024-07-16", fingerprint.javaVersion());
        assertEquals("Darwin 27.0.0", fingerprint.os());
        assertEquals("arm64", fingerprint.machine());
        assertEquals(10, fingerprint.dockerCpus());
        assertEquals(8_319_504_384L, fingerprint.dockerMemoryBytes());
    }

    @Test
    void uncommitted_changes_set_the_dirty_flag() throws Exception {
        outputs.put(GIT_STATUS, "M tools/experiment-runner/pom.xml");

        assertEquals(true, collect().gitDirty());
    }

    @Test
    void a_failing_git_command_gives_null_and_the_other_fields_stay() throws Exception {
        failing.add(GIT_COMMIT);
        failing.add(GIT_STATUS);

        Fingerprint fingerprint = collect();

        assertNull(fingerprint.gitCommit());
        assertNull(fingerprint.gitDirty());
        assertEquals("v1.37.0", fingerprint.kubernetesServerVersion());
        assertEquals(10, fingerprint.dockerCpus());
    }

    @Test
    void a_failing_version_command_gives_null_for_client_and_server() throws Exception {
        failing.add(VERSION);

        Fingerprint fingerprint = collect();

        assertNull(fingerprint.kubernetesClientVersion());
        assertNull(fingerprint.kubernetesServerVersion());
        assertEquals(4, fingerprint.nodeCount());
    }

    @Test
    void a_failing_node_list_gives_null_for_the_node_count() throws Exception {
        failing.add(NODES);

        assertNull(collect().nodeCount());
    }

    @Test
    void a_failing_pod_list_gives_null_for_its_image_ids_only() throws Exception {
        failing.add(WORKLOAD_PODS);

        Fingerprint fingerprint = collect();

        assertNull(fingerprint.workloadImages());
        assertEquals(1, fingerprint.infrastructureImages().size());

        failing.clear();
        failing.add(PLATFORM_PODS);
        fingerprint = collect();

        assertEquals(3, fingerprint.workloadImages().size());
        assertNull(fingerprint.infrastructureImages());
    }

    @Test
    void failing_java_uname_and_docker_commands_give_null() throws Exception {
        failing.addAll(List.of(JAVA, OS, MACHINE, DOCKER));

        Fingerprint fingerprint = collect();

        assertNull(fingerprint.javaVersion());
        assertNull(fingerprint.os());
        assertNull(fingerprint.machine());
        assertNull(fingerprint.dockerCpus());
        assertNull(fingerprint.dockerMemoryBytes());
        assertEquals("ecb21b3aa0c4f1d2b3a4c5d6e7f8091a2b3c4d5e", fingerprint.gitCommit());
    }

    @Test
    void output_that_cannot_be_read_gives_null() throws Exception {
        outputs.put(VERSION, "error: the server is not reachable");
        outputs.put(DOCKER, "Cannot connect to the Docker daemon");

        Fingerprint fingerprint = collect();

        assertNull(fingerprint.kubernetesClientVersion());
        assertNull(fingerprint.dockerCpus());
        assertNull(fingerprint.dockerMemoryBytes());
    }

    @Test
    void a_command_that_cannot_be_started_gives_null_and_does_not_stop_the_collection() throws Exception {
        throwing.add(DOCKER);
        throwing.add(GIT_COMMIT);

        Fingerprint fingerprint = collect();

        assertNull(fingerprint.gitCommit());
        assertNull(fingerprint.dockerCpus());
        assertEquals("arm64", fingerprint.machine());
    }

    @Test
    void all_fields_are_null_when_every_command_fails() throws Exception {
        outputs.clear();

        assertEquals(new Fingerprint(null, null, null, null, null, null, null, null, null, null, null, null),
                collect());
    }

    @Test
    void the_pods_of_every_infrastructure_namespace_of_any_approach_are_listed() throws Exception {
        ApproachDescriptor other = new ApproachDescriptor("other", "Other", List.of("m"),
                List.of("r", "{initialVersion}", "{initialTimeoutMs}"), List.of("c", "{version}", "{timeoutMs}"),
                null, null, List.of(new ApproachDescriptor.Infrastructure("delivery", "operator")),
                new ApproachDescriptor.Declared(List.of(), 0, false, "none"));
        outputs.put("kubectl -n delivery get pods -o json", Fakes.Kubectl.list(
                Fakes.Kubectl.pod("delivery", "operator-1", "thesis-worker", "operator", "sha256:operator")));
        List<ApproachDescriptor> approaches = new ArrayList<>(Fakes.World.loaded("s1-valid-change", 1, 1, 1)
                .approaches());
        approaches.add(other);

        Fingerprint fingerprint = Fingerprint.collect(Fixtures.protocol(), approaches, runner, clock);

        assertEquals(List.of("rabbitmq-1", "operator-1"),
                fingerprint.infrastructureImages().stream().map(Fingerprint.Image::pod).toList());
        assertEquals(1, commands.stream().filter(PLATFORM_PODS::equals).count());
    }

    @Test
    void the_file_holds_every_field_also_the_empty_ones(@TempDir Path dir) throws Exception {
        failing.add(DOCKER);
        Path file = dir.resolve("fingerprint.json");

        collect().write(file);

        JsonNode tree = Fixtures.JSON.readTree(file.toFile());
        assertEquals(12, tree.size());
        assertEquals("ecb21b3aa0c4f1d2b3a4c5d6e7f8091a2b3c4d5e", tree.path("gitCommit").asText());
        assertEquals(false, tree.path("gitDirty").asBoolean(true));
        assertEquals("v1.36.1", tree.path("kubernetesClientVersion").asText());
        assertEquals("v1.37.0", tree.path("kubernetesServerVersion").asText());
        assertEquals(4, tree.path("nodeCount").asInt());
        assertEquals(IMAGE_ID, tree.path("workloadImages").get(0).path("imageId").asText());
        assertEquals("sha256:rabbit", tree.path("infrastructureImages").get(0).path("imageId").asText());
        assertEquals("arm64", tree.path("machine").asText());
        assertTrue(tree.path("dockerCpus").isNull());
        assertTrue(tree.path("dockerMemoryBytes").isNull());
        assertEquals(collect(), Fixtures.JSON.readValue(file.toFile(), Fingerprint.class));
    }

    private Fingerprint collect() throws Exception {
        return Fingerprint.collect(Fixtures.protocol(), Fakes.World.loaded("s1-valid-change", 1, 1, 1).approaches(),
                runner, clock);
    }
}
