package at.technikum.thesis.runner;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ResourceMetricsParserTest {

    private static final String CPU = "container_cpu_usage_seconds_total";
    private static final String MEMORY = "container_memory_working_set_bytes";

    private static String fixture() throws Exception {
        try (InputStream in = ResourceMetricsParserTest.class
                .getResourceAsStream("/metrics-resource-thesis-worker.txt")) {
            assertNotNull(in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static ResourceMetricsParser.Reading find(List<ResourceMetricsParser.Reading> readings, String container) {
        return readings.stream().filter(r -> r.container().equals(container)).findFirst().orElseThrow();
    }

    @Test
    void real_endpoint_output_gives_one_reading_per_container() throws Exception {
        List<ResourceMetricsParser.Reading> readings = ResourceMetricsParser.parse(fixture(), "thesis-worker");

        assertEquals(4, readings.size());
        assertEquals(List.of("kindnet-cni", "kube-proxy", "watcher", "probe"),
                readings.stream().map(ResourceMetricsParser.Reading::container).toList());
    }

    @Test
    void real_endpoint_output_gives_cpu_seconds_working_set_and_timestamp_of_the_probe_container() throws Exception {
        ResourceMetricsParser.Reading probe = find(ResourceMetricsParser.parse(fixture(), "thesis-worker"), "probe");

        assertEquals(new ResourceMetricsParser.Reading("thesis-worker", "thesis", "probe-service-f664c8f55-ldcdv",
                "probe", 177.772262, 372809728L, 1794749463760L), probe);
    }

    @Test
    void real_endpoint_output_gives_cpu_seconds_working_set_and_timestamp_of_the_watcher_container() throws Exception {
        ResourceMetricsParser.Reading watcher = find(ResourceMetricsParser.parse(fixture(), "thesis-worker"), "watcher");

        assertEquals(new ResourceMetricsParser.Reading("thesis-worker", "platform", "config-watcher-5b57946567-t4xfq",
                "watcher", 126.53891, 518287360L, 1794749459296L), watcher);
    }

    @Test
    void real_endpoint_output_gives_cpu_seconds_working_set_and_timestamp_of_the_kube_proxy_container() throws Exception {
        ResourceMetricsParser.Reading proxy = find(ResourceMetricsParser.parse(fixture(), "thesis-worker"), "kube-proxy");

        assertEquals(new ResourceMetricsParser.Reading("thesis-worker", "kube-system", "kube-proxy-mzwkc",
                "kube-proxy", 40.144177, 75296768L, 1794749467130L), proxy);
    }

    @Test
    void pod_and_node_level_families_of_the_real_output_are_not_readings() throws Exception {
        List<ResourceMetricsParser.Reading> readings = ResourceMetricsParser.parse(fixture(), "thesis-worker");

        assertEquals(0, readings.stream().filter(r -> r.cpuSeconds() == 177.806831).count());
        assertEquals(0, readings.stream().filter(r -> r.cpuSeconds() == 1785.192764).count());
    }

    @Test
    void cpu_and_working_set_of_one_container_are_joined_into_one_reading() {
        String text = CPU + "{container=\"c\",namespace=\"ns\",pod=\"p\"} 12.5 1000\n"
                + MEMORY + "{container=\"c\",namespace=\"ns\",pod=\"p\"} 2048 1000\n";

        assertEquals(List.of(new ResourceMetricsParser.Reading("n1", "ns", "p", "c", 12.5, 2048L, 1000L)),
                ResourceMetricsParser.parse(text, "n1"));
    }

    @Test
    void values_in_exponent_form_are_read() {
        String text = CPU + "{container=\"c\",namespace=\"ns\",pod=\"p\"} 1.5e+02 1000\n"
                + MEMORY + "{container=\"c\",namespace=\"ns\",pod=\"p\"} 3.72846592e+08 1000\n";

        assertEquals(List.of(new ResourceMetricsParser.Reading("n1", "ns", "p", "c", 150.0, 372846592L, 1000L)),
                ResourceMetricsParser.parse(text, "n1"));
    }

    @Test
    void a_line_without_timestamp_gives_timestamp_zero() {
        String text = CPU + "{container=\"c\",namespace=\"ns\",pod=\"p\"} 7.25\n"
                + MEMORY + "{container=\"c\",namespace=\"ns\",pod=\"p\"} 4096\n";

        assertEquals(List.of(new ResourceMetricsParser.Reading("n1", "ns", "p", "c", 7.25, 4096L, 0L)),
                ResourceMetricsParser.parse(text, "n1"));
    }

    @Test
    void comment_lines_and_unknown_metric_families_are_skipped() {
        String text = "# HELP " + CPU + " [STABLE] Cumulative cpu time\n"
                + "# TYPE " + CPU + " counter\n"
                + CPU + "{container=\"c\",namespace=\"ns\",pod=\"p\"} 1.0 1000\n"
                + "container_start_time_seconds{container=\"c\",namespace=\"ns\",pod=\"p\"} 1.79e+09\n"
                + "container_swap_usage_bytes{container=\"c\",namespace=\"ns\",pod=\"p\"} 0 1000\n"
                + "pod_cpu_usage_seconds_total{namespace=\"ns\",pod=\"p\"} 99.0 1000\n"
                + "node_cpu_usage_seconds_total 500.0 1000\n"
                + "resource_scrape_error 0\n"
                + MEMORY + "{container=\"c\",namespace=\"ns\",pod=\"p\"} 100 1000\n";

        assertEquals(List.of(new ResourceMetricsParser.Reading("n1", "ns", "p", "c", 1.0, 100L, 1000L)),
                ResourceMetricsParser.parse(text, "n1"));
    }

    @Test
    void broken_lines_are_skipped_without_failing() {
        String text = CPU + "{container=\"good\",namespace=\"ns\",pod=\"p\"} 1.0 1000\n"
                + MEMORY + "{container=\"good\",namespace=\"ns\",pod=\"p\"} 100 1000\n"
                + CPU + "{container=\"novalue\",namespace=\"ns\",pod=\"p\"}\n"
                + MEMORY + "{container=\"novalue\",namespace=\"ns\",pod=\"p\"} 100 1000\n"
                + CPU + "{container=\"text\",namespace=\"ns\",pod=\"p\"} abc 1000\n"
                + MEMORY + "{container=\"text\",namespace=\"ns\",pod=\"p\"} 100 1000\n"
                + CPU + "{container=\"open\",namespace=\"ns\",pod=\"p\" 1.0 1000\n"
                + MEMORY + "{container=\"open\",namespace=\"ns\",pod=\"p\"} 100 1000\n"
                + CPU + "{container=\"badtime\",namespace=\"ns\",pod=\"p\"} 1.0 soon\n"
                + MEMORY + "{container=\"badtime\",namespace=\"ns\",pod=\"p\"} 100 1000\n"
                + CPU + "{container=\"nopod\",namespace=\"ns\"} 1.0 1000\n"
                + MEMORY + "{container=\"nopod\",namespace=\"ns\"} 100 1000\n"
                + CPU + "\n"
                + "{}\n"
                + "\n"
                + "not a metric line at all\n";

        assertEquals(List.of(new ResourceMetricsParser.Reading("n1", "ns", "p", "good", 1.0, 100L, 1000L)),
                ResourceMetricsParser.parse(text, "n1"));
    }

    @Test
    void a_container_with_only_a_cpu_line_is_skipped() {
        String text = CPU + "{container=\"both\",namespace=\"ns\",pod=\"p\"} 1.0 1000\n"
                + CPU + "{container=\"cpuonly\",namespace=\"ns\",pod=\"p\"} 2.0 1000\n"
                + MEMORY + "{container=\"both\",namespace=\"ns\",pod=\"p\"} 100 1000\n";

        assertEquals(List.of(new ResourceMetricsParser.Reading("n1", "ns", "p", "both", 1.0, 100L, 1000L)),
                ResourceMetricsParser.parse(text, "n1"));
    }

    @Test
    void a_container_with_only_a_working_set_line_is_skipped() {
        String text = CPU + "{container=\"both\",namespace=\"ns\",pod=\"p\"} 1.0 1000\n"
                + MEMORY + "{container=\"both\",namespace=\"ns\",pod=\"p\"} 100 1000\n"
                + MEMORY + "{container=\"memonly\",namespace=\"ns\",pod=\"p\"} 200 1000\n";

        assertEquals(List.of(new ResourceMetricsParser.Reading("n1", "ns", "p", "both", 1.0, 100L, 1000L)),
                ResourceMetricsParser.parse(text, "n1"));
    }

    @Test
    void containers_with_the_same_name_in_different_pods_and_namespaces_stay_separate() {
        String text = CPU + "{container=\"c\",namespace=\"a\",pod=\"p1\"} 1.0 1\n"
                + CPU + "{container=\"c\",namespace=\"a\",pod=\"p2\"} 2.0 2\n"
                + CPU + "{container=\"c\",namespace=\"b\",pod=\"p1\"} 3.0 3\n"
                + MEMORY + "{container=\"c\",namespace=\"b\",pod=\"p1\"} 30 3\n"
                + MEMORY + "{container=\"c\",namespace=\"a\",pod=\"p2\"} 20 2\n"
                + MEMORY + "{container=\"c\",namespace=\"a\",pod=\"p1\"} 10 1\n";

        assertEquals(List.of(
                new ResourceMetricsParser.Reading("n1", "a", "p1", "c", 1.0, 10L, 1L),
                new ResourceMetricsParser.Reading("n1", "a", "p2", "c", 2.0, 20L, 2L),
                new ResourceMetricsParser.Reading("n1", "b", "p1", "c", 3.0, 30L, 3L)),
                ResourceMetricsParser.parse(text, "n1"));
    }

    @Test
    void labels_are_read_by_name_in_any_order() {
        String text = CPU + "{pod=\"p\",container=\"c\",namespace=\"ns\"} 1.0 1000\n"
                + MEMORY + "{namespace=\"ns\",pod=\"p\",container=\"c\"} 100 1000\n";

        assertEquals(List.of(new ResourceMetricsParser.Reading("n1", "ns", "p", "c", 1.0, 100L, 1000L)),
                ResourceMetricsParser.parse(text, "n1"));
    }

    @Test
    void empty_text_gives_no_readings() {
        assertEquals(List.of(), ResourceMetricsParser.parse("", "n1"));
    }
}
