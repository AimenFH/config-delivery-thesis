package at.technikum.thesis.runner;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public record RunRecord(
        String runId,
        String campaign,
        String approach,
        String scenario,
        int protocolVersion,
        int block,
        int positionInBlock,
        int attempt,
        String runStartedAt,
        long runStartedAtEpochMillis,
        boolean gate,
        double loadStartedAt,
        double runEndAt,
        long scheduled,
        ChangeTrigger.Result sourceCheck,
        List<StepRecord> steps,
        List<Observation> observations,
        List<ResourceSample> resources,
        ClusterState cluster,
        List<String> errors
) {

    public record StepRecord(
            int index,
            int atSeconds,
            Scenario.Action action,
            Payload payload,
            double startedAt,
            double finishedAt,
            int exitCode,
            String output,
            ChangeTrigger.Result trigger,
            String detail
    ) {

        @JsonIgnore
        public Double t0() {
            return action == Scenario.Action.COMMIT && exitCode == 0 ? finishedAt : null;
        }
    }

    public record ClusterState(
            List<PodInfo> podsBefore,
            List<PodInfo> podsAfter,
            List<InfraState> infrastructure,
            JsonNode events
    ) {
    }

    public record PodInfo(String namespace, String name, String node, String imageId, String startTime) {
    }

    public record InfraState(String namespace, String deployment, int replicas, String cpuRequest, String memoryRequest) {
    }

    @JsonIgnore
    public Double reference() {
        return steps == null || steps.isEmpty() ? null : steps.get(0).startedAt();
    }

    @JsonIgnore
    public List<StepRecord> commits() {
        return steps == null ? List.of()
                : steps.stream().filter(step -> step.action() == Scenario.Action.COMMIT).toList();
    }

    @JsonIgnore
    public Double tCs() {
        List<StepRecord> commits = commits();
        return commits.isEmpty() ? null : commits.get(0).startedAt();
    }

    @JsonIgnore
    public Double t0() {
        List<StepRecord> commits = commits();
        return commits.isEmpty() ? null : commits.get(0).t0();
    }
}
