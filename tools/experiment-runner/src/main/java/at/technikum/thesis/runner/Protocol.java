package at.technikum.thesis.runner;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record Protocol(
        int protocolVersion,
        String namespace,
        String deployment,
        String service,
        String url,
        int replicas,
        int ratePerSecond,
        int requestTimeoutMillis,
        String initialVersion,
        String initialTimeoutMs,
        String targetVersion,
        String unconfiguredVersion,
        int settleSeconds,
        int warmupSeconds,
        int baselineSeconds,
        int observationSeconds,
        int resourceSamplingSeconds,
        int coverageLimitMillis,
        int maxBlocks,
        int retriesPerBlock,
        int steadyProbes,
        int steadyMaxLatencyMillis,
        int steadySnapshotGapSeconds,
        int steadyTimeoutSeconds,
        int triggerTimeoutSeconds,
        int scheduleLagLimitMillis,
        double unsentLimitRatio,
        int resourceGapLimitSeconds,
        int bootstrapResamples,
        long bootstrapSeed,
        List<String> approaches,
        Workload workload,
        Database database
) {

    public record Database(
            String namespace,
            String pod,
            String container,
            String user,
            String name,
            String table
    ) {

        public List<String> problems() {
            List<String> problems = new ArrayList<>();
            text(problems, "database.namespace", namespace);
            text(problems, "database.pod", pod);
            text(problems, "database.container", container);
            text(problems, "database.user", user);
            text(problems, "database.name", name);
            text(problems, "database.table", table);
            return problems;
        }
    }

    public record Workload(
            String image,
            String container,
            String cpuRequest,
            String memoryRequest,
            String cpuLimit,
            String memoryLimit,
            String readinessPath,
            int readinessPort,
            int readinessPeriodSeconds,
            String topologyKey,
            int maxSkew,
            String whenUnsatisfiable,
            String serviceType,
            int servicePort,
            int serviceTargetPort,
            int serviceNodePort,
            Map<String, String> serviceSelector
    ) {

        public List<String> problems() {
            List<String> problems = new ArrayList<>();
            text(problems, "workload.image", image);
            text(problems, "workload.container", container);
            text(problems, "workload.cpuRequest", cpuRequest);
            text(problems, "workload.memoryRequest", memoryRequest);
            text(problems, "workload.cpuLimit", cpuLimit);
            text(problems, "workload.memoryLimit", memoryLimit);
            text(problems, "workload.readinessPath", readinessPath);
            positive(problems, "workload.readinessPort", readinessPort);
            positive(problems, "workload.readinessPeriodSeconds", readinessPeriodSeconds);
            text(problems, "workload.topologyKey", topologyKey);
            positive(problems, "workload.maxSkew", maxSkew);
            text(problems, "workload.whenUnsatisfiable", whenUnsatisfiable);
            text(problems, "workload.serviceType", serviceType);
            positive(problems, "workload.servicePort", servicePort);
            positive(problems, "workload.serviceTargetPort", serviceTargetPort);
            positive(problems, "workload.serviceNodePort", serviceNodePort);
            if (serviceSelector == null || serviceSelector.isEmpty()) {
                problems.add("workload.serviceSelector is missing");
            }
            return problems;
        }
    }

    public static Protocol read(Path file) throws IOException {
        Protocol protocol = new ObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(file.toFile(), Protocol.class);
        List<String> problems = protocol.problems();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(file + ": " + String.join("; ", problems));
        }
        return protocol;
    }

    public URI uri() {
        return URI.create(url);
    }

    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        positive(problems, "protocolVersion", protocolVersion);
        text(problems, "namespace", namespace);
        text(problems, "deployment", deployment);
        text(problems, "service", service);
        text(problems, "url", url);
        positive(problems, "replicas", replicas);
        positive(problems, "ratePerSecond", ratePerSecond);
        positive(problems, "requestTimeoutMillis", requestTimeoutMillis);
        text(problems, "initialVersion", initialVersion);
        text(problems, "initialTimeoutMs", initialTimeoutMs);
        text(problems, "targetVersion", targetVersion);
        text(problems, "unconfiguredVersion", unconfiguredVersion);
        positive(problems, "settleSeconds", settleSeconds);
        positive(problems, "warmupSeconds", warmupSeconds);
        positive(problems, "baselineSeconds", baselineSeconds);
        positive(problems, "observationSeconds", observationSeconds);
        positive(problems, "resourceSamplingSeconds", resourceSamplingSeconds);
        positive(problems, "coverageLimitMillis", coverageLimitMillis);
        positive(problems, "maxBlocks", maxBlocks);
        positive(problems, "retriesPerBlock", retriesPerBlock);
        positive(problems, "steadyProbes", steadyProbes);
        positive(problems, "steadyMaxLatencyMillis", steadyMaxLatencyMillis);
        positive(problems, "steadySnapshotGapSeconds", steadySnapshotGapSeconds);
        positive(problems, "steadyTimeoutSeconds", steadyTimeoutSeconds);
        positive(problems, "triggerTimeoutSeconds", triggerTimeoutSeconds);
        positive(problems, "scheduleLagLimitMillis", scheduleLagLimitMillis);
        positive(problems, "unsentLimitRatio", unsentLimitRatio);
        positive(problems, "resourceGapLimitSeconds", resourceGapLimitSeconds);
        positive(problems, "bootstrapResamples", bootstrapResamples);
        positive(problems, "bootstrapSeed", bootstrapSeed);
        if (initialVersion != null && initialVersion.equals(targetVersion)) {
            problems.add("initialVersion and targetVersion must differ");
        }
        if (unconfiguredVersion != null
                && (unconfiguredVersion.equals(initialVersion) || unconfiguredVersion.equals(targetVersion))) {
            problems.add("unconfiguredVersion must differ from initialVersion and targetVersion");
        }
        if (approaches == null || approaches.isEmpty()) {
            problems.add("approaches is empty");
        } else if (approaches.stream().anyMatch(a -> a == null || a.isBlank())) {
            problems.add("approaches contains an empty path");
        }
        if (workload == null) {
            problems.add("workload is missing");
        } else {
            problems.addAll(workload.problems());
        }
        if (database == null) {
            problems.add("database is missing");
        } else {
            problems.addAll(database.problems());
        }
        return problems;
    }

    private static void text(List<String> problems, String field, String value) {
        if (value == null || value.isBlank()) {
            problems.add(field + " is missing");
        }
    }

    private static void positive(List<String> problems, String field, double value) {
        if (!(value > 0)) {
            problems.add(field + " must be positive");
        }
    }
}
