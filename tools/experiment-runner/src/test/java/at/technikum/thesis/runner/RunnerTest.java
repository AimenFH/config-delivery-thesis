package at.technikum.thesis.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunnerTest {

    @Test
    void the_evaluate_argument_reads_the_named_campaign_folder() {
        IOException thrown = assertThrows(IOException.class,
                () -> Runner.main(new String[]{"--evaluate", "results/raw/no-such-campaign"}));

        assertTrue(thrown.getMessage().contains("no-such-campaign"), thrown.getMessage());
    }

    @Test
    void the_campaign_argument_reads_the_named_campaign_file() {
        IOException thrown = assertThrows(IOException.class,
                () -> Runner.main(new String[]{"--campaign", "campaigns/no-such-campaign.json"}));

        assertTrue(thrown.getMessage().contains("no-such-campaign.json"), thrown.getMessage());
    }

    @Test
    void a_campaign_written_by_the_result_writer_is_evaluated_to_runs_csv_and_summary_csv_and_again_to_the_same_bytes(
            @TempDir Path results) throws Exception {
        CampaignConfig.Loaded loaded = Fakes.World.loaded("s1-valid-change", 1, 1, 5);
        Fakes.World world = new Fakes.World(loaded, results);
        world.probe.loadObservations = TestRuns.cleanS1();
        AtomicReference<Path> folder = new AtomicReference<>();

        int exitCode = new Campaign(loaded, descriptor -> world.adapters.get(descriptor.name()),
                () -> new Fakes.Sampling(world.log), Runner.Wiring.judge(loaded), directory -> {
            folder.set(directory);
            return Runner.Wiring.store(loaded, directory);
        }, world.kubectl, world.probe, world.time, results).run();

        Path dir = folder.get();
        List<String> runIds = new ResultWriter(dir).runIds();
        assertEquals(2, exitCode);
        assertEquals(3 * 3, runIds.size());
        for (String runId : runIds) {
            for (String suffix : List.of(".ndjson", ".resources.ndjson", ".cluster.json", ".meta.json")) {
                assertTrue(Files.isRegularFile(dir.resolve(runId + suffix)), runId + suffix);
            }
            assertTrue(Files.readString(dir.resolve(runId + ".meta.json")).contains("\"verdict\" : \"INVALID\""));
        }
        assertFalse(Files.exists(dir.resolve("runs.csv")));
        assertFalse(Files.exists(dir.resolve("summary.csv")));

        assertEquals(0, Runner.Wiring.evaluate(dir));

        byte[] runs = Files.readAllBytes(dir.resolve("runs.csv"));
        byte[] summary = Files.readAllBytes(dir.resolve("summary.csv"));
        List<String> runLines = Files.readAllLines(dir.resolve("runs.csv"));
        List<String> summaryLines = Files.readAllLines(dir.resolve("summary.csv"));
        assertEquals(1 + 9, runLines.size());
        assertEquals(runIds, runLines.subList(1, 10).stream().map(line -> line.split(",")[1]).toList());
        for (String line : runLines.subList(1, 10)) {
            assertTrue(line.startsWith("1,"), line);
            assertTrue(line.contains(",INVALID,resource sampling: node thesis-worker has no sample within 15 s of "
                    + "the start of the measured run; resource sampling: node thesis-worker has no sample within "
                    + "15 s of the end of the measured run; resource sampling: node thesis-worker2 runs pods of "
                    + "the pod list and has no sample; resource sampling: node thesis-worker3 runs pods of the "
                    + "pod list and has no sample,,4882,4882,120,0,"), line);
        }
        assertEquals(1 + 3 * 11 + 3 * 32 + 7 * 3 + 3 * 2, summaryLines.size());
        for (String approach : List.of("way-a", "way-b", "way-c")) {
            assertTrue(summaryLines.contains("1,count," + approach + ",,runs,3,,,,,,,,,,"), summaryLines.toString());
            assertTrue(summaryLines.contains("1,count," + approach + ",,valid,0,,,,,,,,,,"));
            assertTrue(summaryLines.contains("1,count," + approach + ",,invalid,3,,,,,,,,,,"));
        }

        assertEquals(0, Runner.Wiring.evaluate(dir));

        assertArrayEquals(runs, Files.readAllBytes(dir.resolve("runs.csv")));
        assertArrayEquals(summary, Files.readAllBytes(dir.resolve("summary.csv")));
    }

    @Test
    void a_campaign_is_judged_by_the_evaluator_stored_by_the_result_writer_and_sampled_from_the_kubelet() throws Exception {
        CampaignConfig.Loaded loaded = Fakes.World.loaded("s1-valid-change", 1, 1, 1);

        assertInstanceOf(Evaluator.class, Runner.Wiring.judge(loaded));
        assertInstanceOf(ResultWriter.class, Runner.Wiring.store(loaded, Path.of("results/raw/x")));
        assertInstanceOf(ResourceSampler.class, Runner.Wiring.sampler(loaded));
    }
}
