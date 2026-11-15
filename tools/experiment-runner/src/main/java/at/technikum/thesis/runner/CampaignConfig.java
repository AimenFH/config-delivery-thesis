package at.technikum.thesis.runner;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public record CampaignConfig(
        String name,
        String protocol,
        String scenario,
        int validRuns,
        int maxBlocks,
        long seed
) {

    public record Loaded(
            CampaignConfig config,
            Protocol protocol,
            Scenario scenario,
            List<ApproachDescriptor> approaches
    ) {
    }

    public static CampaignConfig read(Path file) throws IOException {
        CampaignConfig config = new ObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(file.toFile(), CampaignConfig.class);
        List<String> problems = config.problems();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(file + ": " + String.join("; ", problems));
        }
        return config;
    }

    public Loaded load() throws IOException {
        return load(Path.of(""));
    }

    public Loaded load(Path root) throws IOException {
        Protocol loadedProtocol = Protocol.read(root.resolve(protocol));
        List<String> problems = problems(loadedProtocol);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("campaign " + name + ": " + String.join("; ", problems));
        }
        Path scenarioFile = root.resolve(scenario);
        Scenario loadedScenario = Scenario.read(scenarioFile);
        List<String> scenarioProblems = loadedScenario.problems(loadedProtocol);
        if (!scenarioProblems.isEmpty()) {
            throw new IllegalArgumentException(scenarioFile + ": " + String.join("; ", scenarioProblems));
        }
        List<ApproachDescriptor> approaches = new ArrayList<>();
        for (String approach : loadedProtocol.approaches()) {
            approaches.add(ApproachDescriptor.read(root.resolve(approach)));
        }
        return new Loaded(this, loadedProtocol, loadedScenario, List.copyOf(approaches));
    }

    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (name == null || name.isBlank()) {
            problems.add("name is missing");
        }
        if (protocol == null || protocol.isBlank()) {
            problems.add("protocol is missing");
        }
        if (scenario == null || scenario.isBlank()) {
            problems.add("scenario is missing");
        }
        if (validRuns < 1) {
            problems.add("validRuns must be at least 1");
        }
        if (validRuns > maxBlocks) {
            problems.add("validRuns must be at most maxBlocks");
        }
        return problems;
    }

    public List<String> problems(Protocol loadedProtocol) {
        List<String> problems = problems();
        if (maxBlocks > loadedProtocol.maxBlocks()) {
            problems.add("maxBlocks must be at most " + loadedProtocol.maxBlocks() + ", the limit of the protocol");
        }
        return problems;
    }
}
