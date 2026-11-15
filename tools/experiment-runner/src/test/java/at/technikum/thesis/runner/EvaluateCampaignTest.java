package at.technikum.thesis.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static at.technikum.thesis.runner.TestRuns.A;
import static at.technikum.thesis.runner.TestRuns.B;
import static at.technikum.thesis.runner.TestRuns.C;
import static at.technikum.thesis.runner.TestRuns.cleanS1;
import static at.technikum.thesis.runner.TestRuns.commit;
import static at.technikum.thesis.runner.TestRuns.failure;
import static at.technikum.thesis.runner.TestRuns.load;
import static at.technikum.thesis.runner.TestRuns.replace;
import static at.technikum.thesis.runner.TestRuns.samples;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluateCampaignTest {

    private static final Payload V2 = new Payload("v2", "1000");
    private static final List<String> APPROACHES = List.of("way-a", "way-b", "way-c");

    @Test
    void evaluating_the_same_folder_twice_gives_identical_runs_csv_and_summary_csv_bytes(@TempDir Path dir)
            throws Exception {
        campaign(dir);

        Evaluator.evaluateCampaign(dir);
        byte[] runs = Files.readAllBytes(dir.resolve("runs.csv"));
        byte[] summary = Files.readAllBytes(dir.resolve("summary.csv"));
        Evaluator.evaluateCampaign(dir);

        assertArrayEquals(runs, Files.readAllBytes(dir.resolve("runs.csv")));
        assertArrayEquals(summary, Files.readAllBytes(dir.resolve("summary.csv")));
        assertFalse(new String(runs, StandardCharsets.UTF_8).contains("\r"));
        assertFalse(new String(summary, StandardCharsets.UTF_8).contains("\r"));
    }

    @Test
    void raw_files_are_never_rewritten(@TempDir Path dir) throws Exception {
        campaign(dir);
        Map<String, String> before = contents(dir);

        Evaluator.evaluateCampaign(dir);

        Map<String, String> after = contents(dir);
        after.remove("runs.csv");
        after.remove("summary.csv");
        assertEquals(before, after);
    }

    @Test
    void runs_csv_has_one_row_per_run_valid_and_invalid_sorted_by_run_id(@TempDir Path dir) throws Exception {
        campaign(dir);

        List<RunMetrics> metrics = Evaluator.evaluateCampaign(dir);

        List<String> lines = Files.readAllLines(dir.resolve("runs.csv"));
        assertEquals("run_id,approach,block,position,attempt,verdict,reasons,flags,"
                + "P1,P2,P3,C1,C1_max,C2,C3,A1,A2,A3,F1,"
                + "F2_p50_B,F2_p95_B,F2_p99_B,F2_max_B,F2_p50_H,F2_p95_H,F2_p99_H,F2_max_H,F2_p99_ratio,F3,"
                + "O1,O2,O3_cpu,O3_memory,O4,O5_extraComponents,O5_extraComponents_text,O5_operatorActions,"
                + "O5_clusterWideRbac,O5_serviceCoupling,O5_requestedCpu,O5_requestedMemory", lines.get(0));
        assertEquals(List.of("20261115-200100_b01_way-a", "20261115-200800_b01_way-b", "20261115-201500_b01_way-c",
                        "20261115-202200_b02_way-b", "20261115-202900_b02_way-b", "20261115-203600_b02_way-a",
                        "20261115-204300_b02_way-c"),
                lines.subList(1, lines.size()).stream().map(line -> line.substring(0, line.indexOf(','))).toList());
        assertEquals(lines.subList(1, lines.size()).stream().map(line -> line.substring(0, line.indexOf(','))).toList(),
                metrics.stream().map(RunMetrics::runId).toList());
    }

    @Test
    void a_row_of_runs_csv_holds_identity_verdict_and_one_cell_per_metric_column(@TempDir Path dir)
            throws Exception {
        campaign(dir);

        Evaluator.evaluateCampaign(dir);

        List<String> lines = Files.readAllLines(dir.resolve("runs.csv"));
        assertTrue(lines.get(1).startsWith("20261115-200100_b01_way-a,way-a,1,1,1,VALID,,,"
                + "5342,4942,100,400,520,7,0,0,0,3,-75,2,2,2,2,2,2,2,2,1,2,"), lines.get(1));
        assertTrue(lines.get(1).endsWith(",64,150,768,0,1,Stakater Reloader,1,1,"
                + "\"none, values read at process start\",reloader-reloader=10m,reloader-reloader=64Mi")
                || lines.get(1).matches(".*,64,1(50\\.0000\\d*|49\\.9999\\d*),768,0,1,Stakater Reloader,1,1,"
                + "\"none, values read at process start\",reloader-reloader=10m,reloader-reloader=64Mi"), lines.get(1));
    }

    @Test
    void a_censored_value_is_written_with_a_greater_than_sign_and_flags_and_reasons_are_listed(@TempDir Path dir)
            throws Exception {
        campaign(dir);

        Evaluator.evaluateCampaign(dir);

        List<String> lines = Files.readAllLines(dir.resolve("runs.csv"));
        assertTrue(lines.get(2).startsWith("20261115-200800_b01_way-b,way-b,1,2,1,VALID,,NOT_CONVERGED,"
                + ">150000,4902,100,144980,"), lines.get(2));
        assertTrue(lines.get(4).startsWith("20261115-202200_b02_way-b,way-b,2,1,1,INVALID,"
                + "baseline: 1 failed requests,,"), lines.get(4));
        assertTrue(lines.get(5).startsWith("20261115-202900_b02_way-b,way-b,2,1,2,VALID,,,"), lines.get(5));
    }

    @Test
    void every_run_is_evaluated_again_whatever_verdict_its_meta_file_stores(@TempDir Path dir) throws Exception {
        campaign(dir);

        List<RunMetrics> metrics = Evaluator.evaluateCampaign(dir);

        assertTrue(Files.readString(dir.resolve("20261115-204300_b02_way-c.meta.json")).contains("\"INVALID\""));
        assertEquals(RunMetrics.Verdict.VALID, metrics.get(6).verdict());
        assertEquals(List.of(), metrics.get(6).invalidReasons());
    }

    @Test
    void summary_csv_reports_counts_statistics_comparisons_and_sensitivity_rows(@TempDir Path dir)
            throws Exception {
        campaign(dir);

        Evaluator.evaluateCampaign(dir);

        List<String> lines = Files.readAllLines(dir.resolve("summary.csv"));
        assertEquals(String.join(",", Summary.HEADER), lines.get(0));
        assertTrue(lines.contains("count,way-b,,runs,3,,,,,,,,,,"), lines.toString());
        assertTrue(lines.contains("count,way-b,,valid,2,,,,,,,,,,"));
        assertTrue(lines.contains("count,way-b,,invalid,1,,,,,,,,,,"));
        assertTrue(lines.contains("count,way-b,,NOT_CONVERGED,1,,,,,,,,,,"));
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("metric,way-a,,P1,2,,0,4902,5012,5122,5232,5342,")),
                lines.toString());
        assertTrue(lines.stream().anyMatch(line ->
                line.startsWith("metric,way-b,,P1,2,,1,4902,censored,censored,censored,censored,")));
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("difference,way-a,way-c,P1,2,2,0,,,110,,,")),
                lines.toString());
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("sensitivity,way-b,,A1,3,,0,0,0,0,0,0,0,0,")));
        assertEquals(1 + 3 * 9 + 3 * 32 + 7 * 3 + 3 * 2, lines.size());
    }

    private static void campaign(Path dir) throws Exception {
        CampaignConfig.Loaded loaded = CampaignConfig.read(Fixtures.ROOT.resolve("campaigns/s1.json"))
                .load(Fixtures.ROOT);
        CampaignRecord campaign = new CampaignRecord("20261115-200000_s1", loaded.config(), loaded.protocol(),
                loaded.scenario(), APPROACHES, List.of(List.of("way-a", "way-b", "way-c"),
                List.of("way-b", "way-a", "way-c")));
        Fixtures.JSON.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("campaign.json").toFile(), campaign);
        Files.writeString(dir.resolve("fingerprint.json"), "{\"gitCommit\":\"ecb21b3\"}");
        Files.createDirectories(dir.resolve("approaches"));
        for (String approach : APPROACHES) {
            Files.copy(Fixtures.ROOT.resolve("approaches").resolve(approach).resolve("approach.json"),
                    dir.resolve("approaches").resolve(approach + ".json"));
        }

        Evaluator evaluator = new Evaluator(loaded.protocol(), loaded.scenario(), loaded.approaches());
        ResultWriter store = new ResultWriter(dir);
        List<Observation> staggered = load(0, 12_000, (pod, at) ->
                at < (pod.equals(A) ? 95_000 : pod.equals(B) ? 95_200 : 95_500) ? "v1" : "v2");
        List<Observation> notConverged = load(0, 12_000, (pod, at) -> pod.equals(C) || at < 95_000 ? "v1" : "v2");
        List<RunRecord> records = new ArrayList<>(List.of(
                run("20261115-203600_b02_way-a", "way-a", 2, 2, 1, cleanS1()),
                run("20261115-200100_b01_way-a", "way-a", 1, 1, 1, staggered),
                run("20261115-202900_b02_way-b", "way-b", 2, 1, 2, cleanS1()),
                run("20261115-200800_b01_way-b", "way-b", 1, 2, 1, notConverged),
                run("20261115-202200_b02_way-b", "way-b", 2, 1, 1, replace(cleanS1(), failure(3_000))),
                run("20261115-201500_b01_way-c", "way-c", 1, 3, 1, cleanS1())));
        for (RunRecord record : records) {
            store.save(record, evaluator.evaluate(record));
        }
        RunRecord last = run("20261115-204300_b02_way-c", "way-c", 2, 3, 1,
                load(0, 12_000, (pod, at) -> at < 95_220 ? "v1" : "v2"));
        RunMetrics wrong = evaluator.evaluate(run("20261115-204300_b02_way-c", "way-c", 2, 3, 1,
                replace(cleanS1(), failure(3_000))));
        store.save(last, wrong);
    }

    private static RunRecord run(String runId, String approach, int block, int position, int attempt,
                                 List<Observation> observations) {
        return TestRuns.run(runId, approach, "s1-valid-change", block, position, attempt,
                List.of(commit(0, 0, V2, 90_000, 90_100)), observations, samples(0, 240_000), 240_000);
    }

    private static Map<String, String> contents(Path dir) throws Exception {
        Map<String, String> contents = new TreeMap<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                contents.put(dir.relativize(file).toString(),
                        Arrays.hashCode(Files.readAllBytes(file)) + "@" + Files.getLastModifiedTime(file).toMillis());
            }
        }
        return contents;
    }
}
