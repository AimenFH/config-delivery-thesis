package at.technikum.thesis.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApproachDescriptorTest {

    private static final Path WAY_A = Fixtures.ROOT.resolve("approaches/way-a/approach.json");
    private static final Path WAY_B = Fixtures.ROOT.resolve("approaches/way-b/approach.json");
    private static final Path WAY_C = Fixtures.ROOT.resolve("approaches/way-c/approach.json");

    @Test
    void wayAPatchesBothKeysOfItsConfigMapInOneCall() throws Exception {
        ApproachDescriptor descriptor = ApproachDescriptor.read(WAY_A);

        assertEquals("way-a", descriptor.name());
        assertEquals(List.of("deploy/way-a-envvar"), descriptor.manifests());
        assertEquals(List.of("kubectl", "-n", "thesis", "patch", "configmap", "demo-config", "--type", "merge", "-p"),
                descriptor.commit().subList(0, 9));
        assertEquals(10, descriptor.commit().size());
        assertPatch(descriptor.commit().get(9), "configVersion", "{version}", "timeoutMs", "{timeoutMs}");
        assertEquals(descriptor.commit().subList(0, 9), descriptor.reset().subList(0, 9));
        assertPatch(descriptor.reset().get(9), "configVersion", "{initialVersion}", "timeoutMs", "{initialTimeoutMs}");
        assertNull(descriptor.trigger());
        assertNull(descriptor.sourceCheck());
        assertEquals(List.of(new ApproachDescriptor.Infrastructure("platform", "reloader-reloader")),
                descriptor.infrastructure());
        assertEquals(new ApproachDescriptor.Declared(List.of("Stakater Reloader"), 1, true,
                "none, values read at process start"), descriptor.declared());
    }

    @Test
    void wayBPatchesBothKeysOfItsConfigMapInOneCall() throws Exception {
        ApproachDescriptor descriptor = ApproachDescriptor.read(WAY_B);

        assertEquals("way-b", descriptor.name());
        assertEquals(List.of("deploy/way-b-volume"), descriptor.manifests());
        assertEquals(List.of("kubectl", "-n", "thesis", "patch", "configmap", "demo-config", "--type", "merge", "-p"),
                descriptor.commit().subList(0, 9));
        assertEquals(10, descriptor.commit().size());
        assertPatch(descriptor.commit().get(9), "demo.configVersion", "{version}", "demo.timeoutMs", "{timeoutMs}");
        assertEquals(descriptor.commit().subList(0, 9), descriptor.reset().subList(0, 9));
        assertPatch(descriptor.reset().get(9),
                "demo.configVersion", "{initialVersion}", "demo.timeoutMs", "{initialTimeoutMs}");
        assertNull(descriptor.trigger());
        assertNull(descriptor.sourceCheck());
        assertEquals(List.of(new ApproachDescriptor.Infrastructure("platform", "config-watcher")),
                descriptor.infrastructure());
        assertEquals(new ApproachDescriptor.Declared(List.of("Spring Cloud Kubernetes Configuration Watcher"), 1, true,
                "actuator refresh endpoint, refresh-scoped beans"), descriptor.declared());
    }

    @Test
    void wayCIsTheExampleOfThePlanWithASourceCheck() throws Exception {
        ApproachDescriptor descriptor = ApproachDescriptor.read(WAY_C);

        assertEquals("way-c", descriptor.name());
        assertEquals("Config Server and Bus refresh", descriptor.title());
        assertEquals(List.of("deploy/way-c-configserver"), descriptor.manifests());
        assertEquals(List.of("bash", "tools/way-c-commit.sh", "{initialVersion}", "{initialTimeoutMs}"),
                descriptor.reset());
        assertEquals(List.of("bash", "tools/way-c-commit.sh", "{version}", "{timeoutMs}"), descriptor.commit());
        assertEquals(List.of("kubectl", "-n", "platform", "exec", "deploy/config-server", "--",
                "curl", "-sf", "-X", "POST", "localhost:8888/actuator/busrefresh"), descriptor.trigger());
        assertEquals(List.of("bash", "-c",
                "git -C \"${CONFIG_REPO:-$HOME/config-repo}\" ls-remote -q origin HEAD"), descriptor.sourceCheck());
        assertEquals(List.of(new ApproachDescriptor.Infrastructure("platform", "config-server"),
                new ApproachDescriptor.Infrastructure("platform", "rabbitmq")), descriptor.infrastructure());
        assertEquals(new ApproachDescriptor.Declared(List.of("Config Server", "RabbitMQ"), 2, false,
                "config client and bus libraries, startup dependency"), descriptor.declared());
    }

    @Test
    void theManifestFoldersAndTheCommitScriptExist() throws Exception {
        for (Path file : List.of(WAY_A, WAY_B, WAY_C)) {
            for (String manifests : ApproachDescriptor.read(file).manifests()) {
                assertEquals(true, Files.isDirectory(Fixtures.ROOT.resolve(manifests)), manifests);
            }
        }
        assertEquals(true, Files.isRegularFile(Fixtures.ROOT.resolve(ApproachDescriptor.read(WAY_C).commit().get(1))));
    }

    @Test
    void theSchemaHasNoPlaceForTimingRateReplicasOrValidity() {
        Set<String> fields = Arrays.stream(ApproachDescriptor.class.getRecordComponents())
                .map(component -> component.getName()).collect(Collectors.toSet());

        assertEquals(Set.of("name", "title", "manifests", "reset", "commit", "trigger", "sourceCheck",
                "infrastructure", "declared"), fields);
    }

    @Test
    void rejectsADescriptorThatTriesToSetTheRate(@TempDir Path dir) throws Exception {
        assertThrows(UnrecognizedPropertyException.class, () -> ApproachDescriptor.read(with(dir, "ratePerSecond", 10)));
    }

    @Test
    void rejectsADescriptorThatTriesToSetADuration(@TempDir Path dir) throws Exception {
        assertThrows(UnrecognizedPropertyException.class, () -> ApproachDescriptor.read(with(dir, "durationSeconds", 30)));
        assertThrows(UnrecognizedPropertyException.class, () -> ApproachDescriptor.read(with(dir, "settleSeconds", 5)));
        assertThrows(UnrecognizedPropertyException.class, () -> ApproachDescriptor.read(with(dir, "replicas", 1)));
    }

    @Test
    void rejectsAnUnknownFieldInsideDeclaredAndInfrastructure(@TempDir Path dir) throws Exception {
        ObjectNode tree = (ObjectNode) Fixtures.JSON.readTree(WAY_C.toFile());
        ((ObjectNode) tree.get("declared")).put("observationSeconds", 10);
        Path declared = dir.resolve("declared.json");
        Files.writeString(declared, tree.toString());
        tree = (ObjectNode) Fixtures.JSON.readTree(WAY_C.toFile());
        ((ObjectNode) tree.get("infrastructure").get(0)).put("replicas", 2);
        Path infrastructure = dir.resolve("infrastructure.json");
        Files.writeString(infrastructure, tree.toString());

        assertThrows(UnrecognizedPropertyException.class, () -> ApproachDescriptor.read(declared));
        assertThrows(UnrecognizedPropertyException.class, () -> ApproachDescriptor.read(infrastructure));
    }

    @Test
    void reportsEveryMissingPart() {
        ApproachDescriptor descriptor = new ApproachDescriptor(null, null, null, null, null, null, null, null, null);

        assertEquals(List.of("name is missing", "manifests is empty", "reset is missing", "commit is missing",
                "infrastructure is empty", "declared is missing"), descriptor.problems());
    }

    @Test
    void commitAndResetMustCarryBothPlaceholders() throws Exception {
        ApproachDescriptor descriptor = Fixtures.changed(WAY_C, ApproachDescriptor.class, tree -> {
            tree.putArray("commit").add("bash").add("x.sh").add("{version}");
            tree.putArray("reset").add("bash").add("x.sh").add("v1").add("{initialTimeoutMs}");
        });

        assertEquals(List.of("reset must contain {initialVersion}", "commit must contain {timeoutMs}"),
                descriptor.problems());
    }

    @Test
    void infrastructureEntriesNeedNamespaceAndDeployment() throws Exception {
        ApproachDescriptor descriptor = Fixtures.changed(WAY_C, ApproachDescriptor.class,
                tree -> ((ObjectNode) tree.get("infrastructure").get(1)).remove("namespace"));

        assertEquals(List.of("every infrastructure entry needs namespace and deployment"), descriptor.problems());
    }

    @Test
    void readFailsOnAnInvalidDescriptor(@TempDir Path dir) throws Exception {
        ObjectNode tree = (ObjectNode) Fixtures.JSON.readTree(WAY_A.toFile());
        tree.remove("infrastructure");
        Path file = dir.resolve("approach.json");
        Files.writeString(file, tree.toString());

        assertThrows(IllegalArgumentException.class, () -> ApproachDescriptor.read(file));
    }

    private static Path with(Path dir, String field, int value) throws Exception {
        ObjectNode tree = (ObjectNode) Fixtures.JSON.readTree(WAY_A.toFile());
        tree.put(field, value);
        Path file = dir.resolve(field + ".json");
        Files.writeString(file, tree.toString());
        return file;
    }

    private static void assertPatch(String body, String versionKey, String version, String timeoutKey, String timeout)
            throws Exception {
        JsonNode data = Fixtures.JSON.readTree(body).path("data");

        assertEquals(2, data.size());
        assertEquals(version, data.path(versionKey).asText());
        assertEquals(timeout, data.path(timeoutKey).asText());
    }
}
