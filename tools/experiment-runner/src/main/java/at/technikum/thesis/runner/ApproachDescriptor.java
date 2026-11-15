package at.technikum.thesis.runner;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public record ApproachDescriptor(
        String name,
        String title,
        List<String> manifests,
        List<String> reset,
        List<String> commit,
        List<String> trigger,
        List<String> sourceCheck,
        List<Infrastructure> infrastructure,
        Declared declared
) {

    public record Infrastructure(String namespace, String deployment) {
    }

    public record Declared(
            List<String> extraComponents,
            int operatorActionsPerChange,
            boolean clusterWideRbac,
            String serviceCoupling
    ) {
    }

    public static ApproachDescriptor read(Path file) throws IOException {
        ApproachDescriptor descriptor = new ObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(file.toFile(), ApproachDescriptor.class);
        List<String> problems = descriptor.problems();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(file + ": " + String.join("; ", problems));
        }
        return descriptor;
    }

    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (name == null || name.isBlank()) {
            problems.add("name is missing");
        }
        if (empty(manifests)) {
            problems.add("manifests is empty");
        }
        if (empty(reset)) {
            problems.add("reset is missing");
        } else {
            placeholder(problems, "reset", reset, "{initialVersion}");
            placeholder(problems, "reset", reset, "{initialTimeoutMs}");
        }
        if (empty(commit)) {
            problems.add("commit is missing");
        } else {
            placeholder(problems, "commit", commit, "{version}");
            placeholder(problems, "commit", commit, "{timeoutMs}");
        }
        if (trigger != null && trigger.isEmpty()) {
            problems.add("trigger is empty");
        }
        if (sourceCheck != null && sourceCheck.isEmpty()) {
            problems.add("sourceCheck is empty");
        }
        if (infrastructure == null || infrastructure.isEmpty()) {
            problems.add("infrastructure is empty");
        } else if (infrastructure.stream().anyMatch(i -> i == null || blank(i.namespace()) || blank(i.deployment()))) {
            problems.add("every infrastructure entry needs namespace and deployment");
        }
        if (declared == null) {
            problems.add("declared is missing");
        } else {
            if (declared.extraComponents() == null) {
                problems.add("declared.extraComponents is missing");
            }
            if (blank(declared.serviceCoupling())) {
                problems.add("declared.serviceCoupling is missing");
            }
            if (declared.operatorActionsPerChange() < 0) {
                problems.add("declared.operatorActionsPerChange must not be negative");
            }
        }
        return problems;
    }

    private static void placeholder(List<String> problems, String field, List<String> argv, String placeholder) {
        if (argv.stream().noneMatch(part -> part != null && part.contains(placeholder))) {
            problems.add(field + " must contain " + placeholder);
        }
    }

    private static boolean empty(List<String> values) {
        return values == null || values.isEmpty();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
