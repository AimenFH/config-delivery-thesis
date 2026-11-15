package at.technikum.thesis.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record Fingerprint(
        String gitCommit,
        Boolean gitDirty,
        String kubernetesClientVersion,
        String kubernetesServerVersion,
        Integer nodeCount,
        List<Image> workloadImages,
        List<Image> infrastructureImages,
        String javaVersion,
        String os,
        String machine,
        Integer dockerCpus,
        Long dockerMemoryBytes
) {

    public record Image(String namespace, String pod, String container, String image, String imageId) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    public static Fingerprint collect(Protocol protocol, List<ApproachDescriptor> approaches,
                                      CommandRunner commands, RunnerClock clock) {
        String status = output(commands, clock, "git", "status", "--porcelain");
        JsonNode version = json(commands, clock, "kubectl", "version", "-o", "json");
        JsonNode nodes = json(commands, clock, "kubectl", "get", "nodes", "-o", "json");
        JsonNode workload = json(commands, clock, "kubectl", "-n", protocol.namespace(), "get", "pods",
                "-l", ClusterControl.selector(protocol), "-o", "json");
        Set<String> namespaces = new LinkedHashSet<>();
        for (ApproachDescriptor approach : approaches) {
            approach.infrastructure().forEach(entry -> namespaces.add(entry.namespace()));
        }
        List<Image> infrastructure = new ArrayList<>();
        boolean infrastructureRead = true;
        for (String namespace : namespaces) {
            JsonNode pods = json(commands, clock, "kubectl", "-n", namespace, "get", "pods", "-o", "json");
            if (pods == null) {
                infrastructureRead = false;
            } else {
                infrastructure.addAll(images(pods));
            }
        }
        String[] docker = words(output(commands, clock, "docker", "info", "--format", "{{.NCPU}} {{.MemTotal}}"));
        Long dockerCpus = number(docker, 0);
        return new Fingerprint(
                output(commands, clock, "git", "rev-parse", "HEAD"),
                status == null ? null : !status.isEmpty(),
                version == null ? null : version.path("clientVersion").path("gitVersion").asText(null),
                version == null ? null : version.path("serverVersion").path("gitVersion").asText(null),
                nodes == null || !nodes.path("items").isArray() ? null : nodes.path("items").size(),
                workload == null ? null : images(workload),
                infrastructureRead ? infrastructure : null,
                output(commands, clock, "java", "-version"),
                output(commands, clock, "uname", "-sr"),
                output(commands, clock, "uname", "-m"),
                dockerCpus == null ? null : dockerCpus.intValue(),
                number(docker, 1));
    }

    public void write(Path file) throws IOException {
        JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), this);
    }

    private static List<Image> images(JsonNode pods) {
        List<Image> images = new ArrayList<>();
        for (JsonNode pod : pods.path("items")) {
            for (JsonNode status : pod.path("status").path("containerStatuses")) {
                images.add(new Image(
                        pod.path("metadata").path("namespace").asText(null),
                        pod.path("metadata").path("name").asText(null),
                        status.path("name").asText(null),
                        status.path("image").asText(null),
                        status.path("imageID").asText(null)));
            }
        }
        return images;
    }

    private static String output(CommandRunner commands, RunnerClock clock, String... argv) {
        try {
            ChangeTrigger.Result result = commands.run(List.of(argv), clock);
            return result.exitCode() == 0 ? result.output() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static JsonNode json(CommandRunner commands, RunnerClock clock, String... argv) {
        String output = output(commands, clock, argv);
        if (output == null) {
            return null;
        }
        try {
            JsonNode tree = JSON.readTree(output);
            return tree == null || !tree.isObject() ? null : tree;
        } catch (IOException e) {
            return null;
        }
    }

    private static String[] words(String output) {
        return output == null ? new String[0] : output.trim().split("\\s+");
    }

    private static Long number(String[] words, int index) {
        if (words.length != 2) {
            return null;
        }
        try {
            return Long.parseLong(words[index]);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
