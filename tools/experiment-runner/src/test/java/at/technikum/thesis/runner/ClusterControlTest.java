package at.technikum.thesis.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterControlTest {

    @Test
    void patchCommandTargetsTheDemoConfigMap() {
        List<String> command = ClusterControl.patchCommand("demo.configVersion", "v101");

        assertEquals(List.of("kubectl", "-n", "thesis", "patch", "configmap", "demo-config", "--type", "merge"),
                command.subList(0, 8));
        assertEquals("-p", command.get(8));
    }

    @Test
    void patchBodyIsValidJsonWithTheGivenKey() throws Exception {
        List<String> command = ClusterControl.patchCommand("configVersion", "v202");

        JsonNode body = new ObjectMapper().readTree(command.get(9));

        assertEquals("v202", body.path("data").path("configVersion").asText());
        assertEquals(1, body.path("data").size());
    }

    @Test
    void steadyNeedsAllReplicasOneVersionNoFailuresAndFastAnswers() {
        Set<String> threePods = Set.of("a", "b", "c");

        assertTrue(ClusterControl.isSteady(new ClusterControl.Snapshot(threePods, Set.of("v1"), 0, 20), 3));
        assertFalse(ClusterControl.isSteady(new ClusterControl.Snapshot(Set.of("a"), Set.of("v1"), 0, 20), 3));
        assertFalse(ClusterControl.isSteady(new ClusterControl.Snapshot(threePods, Set.of("v1", "v2"), 0, 20), 3));
        assertFalse(ClusterControl.isSteady(new ClusterControl.Snapshot(threePods, Set.of("v1"), 1, 20), 3));
        assertFalse(ClusterControl.isSteady(new ClusterControl.Snapshot(threePods, Set.of("v1"), 0, 450), 3));
    }
}
