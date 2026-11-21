package at.technikum.thesis.runner;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record Scenario(
        String name,
        String title,
        boolean gate,
        List<String> invalidVersions,
        List<Step> steps
) {

    public enum Action {
        @JsonProperty("commit") COMMIT,
        @JsonProperty("infraDown") INFRA_DOWN,
        @JsonProperty("infraUp") INFRA_UP,
        @JsonProperty("deletePod") DELETE_POD,
        @JsonProperty("deleteReadyPod") DELETE_READY_POD
    }

    public record Step(int atSeconds, Action action, Payload payload) {
    }

    public Scenario {
        invalidVersions = invalidVersions == null ? List.of() : List.copyOf(invalidVersions);
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public static Scenario read(Path file) throws IOException {
        return new ObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(file.toFile(), Scenario.class);
    }

    public List<String> problems(Protocol protocol) {
        List<String> problems = new ArrayList<>();
        if (name == null || name.isBlank()) {
            problems.add("name is missing");
        }
        if (steps.isEmpty()) {
            problems.add("steps is empty");
        }
        int previous = Integer.MIN_VALUE;
        boolean down = false;
        Set<String> versions = new HashSet<>();
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            String label = "step " + i;
            if (step.atSeconds() < 0) {
                problems.add(label + ": atSeconds must not be negative");
            }
            if (step.atSeconds() < previous) {
                problems.add(label + ": atSeconds must not decrease");
            }
            previous = step.atSeconds();
            if (step.action() == null) {
                problems.add(label + ": action is missing");
                continue;
            }
            if (step.action() == Action.COMMIT) {
                Payload payload = step.payload();
                if (payload == null || payload.configVersion() == null || payload.timeoutMs() == null) {
                    problems.add(label + ": commit needs a payload with configVersion and timeoutMs");
                } else {
                    if (!versions.add(payload.configVersion())) {
                        problems.add(label + ": version " + payload.configVersion() + " is used by more than one payload");
                    }
                    if (payload.configVersion().equals(protocol.initialVersion())) {
                        problems.add(label + ": version must differ from the initial version " + protocol.initialVersion());
                    }
                }
            } else if (step.payload() != null) {
                problems.add(label + ": only commit takes a payload");
            }
            if (step.action() == Action.INFRA_DOWN) {
                down = true;
            }
            if (step.action() == Action.INFRA_UP) {
                if (!down) {
                    problems.add(label + ": infraUp needs an earlier infraDown");
                }
                down = false;
            }
        }
        if (down) {
            problems.add("infraDown is not followed by an infraUp");
        }
        for (String invalid : invalidVersions) {
            if (!versions.contains(invalid)) {
                problems.add("invalid version " + invalid + " is not the version of any payload");
            }
        }
        return problems;
    }

    public List<Payload> payloads() {
        return steps.stream().map(Step::payload).filter(Objects::nonNull).toList();
    }

    public Set<String> declaredVersions(Protocol protocol) {
        Set<String> versions = new LinkedHashSet<>();
        versions.add(protocol.initialVersion());
        payloads().forEach(payload -> versions.add(payload.configVersion()));
        return versions;
    }

    public int lastStepAtSeconds() {
        return steps.isEmpty() ? 0 : steps.get(steps.size() - 1).atSeconds();
    }

    public Set<MetricId> reportedMetrics() {
        if (steps.stream().anyMatch(step -> step.action() == Action.INFRA_DOWN)) {
            return EnumSet.range(MetricId.U1, MetricId.U5);
        }
        if (!invalidVersions.isEmpty()) {
            return EnumSet.range(MetricId.E1, MetricId.R1);
        }
        if (payloads().size() > 1) {
            return EnumSet.range(MetricId.Q1, MetricId.Q7);
        }
        return EnumSet.range(MetricId.P1, MetricId.O5);
    }
}
