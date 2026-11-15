package at.technikum.thesis.runner;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ResourceUsageTest {

    private static final long MIB = 1024 * 1024;
    private static final Interval INTERVAL = new Interval(0, 30_000);

    @Test
    void cpu_is_the_sum_of_the_counter_increments_divided_by_the_time_between_first_and_last_sample() {
        List<ResourceSample> samples = List.of(
                sample(0, "pod-a", 10.0, 100),
                sample(5_000, "pod-a", 10.1, 100),
                sample(10_000, "pod-a", 10.2, 100),
                sample(15_000, "pod-a", 10.3, 100),
                sample(20_000, "pod-a", 10.4, 100));

        assertEquals(0.4 / 20 * 1000, ResourceUsage.of(samples, INTERVAL, s -> true).cpuMillicores(), 1e-9);
    }

    @Test
    void a_container_that_disappears_keeps_what_it_used_one_that_appears_counts_from_its_second_sample_and_a_counter_reset_adds_nothing() {
        ResourceUsage.Usage usage = ResourceUsage.of(mixed(), INTERVAL, s -> true);

        assertEquals(70, usage.cpuMillicores(), 1e-9);
    }

    @Test
    void each_case_of_the_increment_logic_on_its_own() {
        List<ResourceSample> samples = mixed();

        assertEquals(20, ResourceUsage.of(samples, INTERVAL, s -> s.pod().equals("stays")).cpuMillicores(), 1e-9);
        assertEquals(20, ResourceUsage.of(samples, INTERVAL, s -> s.pod().equals("disappears")).cpuMillicores(), 1e-9);
        assertEquals(15, ResourceUsage.of(samples, INTERVAL, s -> s.pod().equals("appears")).cpuMillicores(), 1e-9);
        assertEquals(15, ResourceUsage.of(samples, INTERVAL, s -> s.pod().equals("restarts")).cpuMillicores(), 1e-9);
    }

    @Test
    void memory_is_the_highest_sum_of_working_sets_in_one_sampling_round_in_MiB() {
        ResourceUsage.Usage usage = ResourceUsage.of(mixed(), INTERVAL, s -> true);

        assertEquals(200, usage.memoryMiB());
    }

    @Test
    void two_containers_of_one_pod_are_counted_separately() {
        List<ResourceSample> samples = List.of(
                new ResourceSample(0, "n", "ns", "pod", "main", 1.0, 10 * MIB, 0),
                new ResourceSample(0, "n", "ns", "pod", "sidecar", 9.0, 5 * MIB, 0),
                new ResourceSample(10_000, "n", "ns", "pod", "main", 1.5, 10 * MIB, 0),
                new ResourceSample(10_000, "n", "ns", "pod", "sidecar", 9.1, 7 * MIB, 0));

        ResourceUsage.Usage usage = ResourceUsage.of(samples, INTERVAL, s -> true);

        assertEquals(60, usage.cpuMillicores(), 1e-9);
        assertEquals(17, usage.memoryMiB());
    }

    @Test
    void samples_outside_the_interval_are_ignored() {
        List<ResourceSample> samples = new ArrayList<>(List.of(
                sample(-5_000, "pod-a", 1.0, 900),
                sample(0, "pod-a", 10.0, 100),
                sample(10_000, "pod-a", 10.5, 100),
                sample(30_000, "pod-a", 99.0, 900)));

        ResourceUsage.Usage usage = ResourceUsage.of(samples, INTERVAL, s -> true);

        assertEquals(50, usage.cpuMillicores(), 1e-9);
        assertEquals(100, usage.memoryMiB());
    }

    @Test
    void the_time_base_is_the_first_and_last_sampling_time_in_the_interval_also_when_the_selected_pod_was_sampled_for_a_shorter_time() {
        List<ResourceSample> samples = List.of(
                sample(0, "other", 1.0, 1),
                sample(5_000, "selected", 2.0, 30),
                sample(10_000, "selected", 2.2, 40),
                sample(20_000, "other", 1.5, 1));

        ResourceUsage.Usage usage = ResourceUsage.of(samples, INTERVAL, s -> s.pod().equals("selected"));

        assertEquals(0.2 / 20 * 1000, usage.cpuMillicores(), 1e-9);
        assertEquals(40, usage.memoryMiB());
    }

    @Test
    void without_a_sample_of_the_selected_pods_there_is_no_figure() {
        ResourceUsage.Usage usage = ResourceUsage.of(mixed(), INTERVAL, s -> s.pod().equals("unknown"));

        assertNull(usage.cpuMillicores());
        assertNull(usage.memoryMiB());
    }

    private static List<ResourceSample> mixed() {
        return List.of(
                sample(0, "stays", 10.0, 100),
                sample(5_000, "stays", 10.1, 100),
                sample(10_000, "stays", 10.2, 100),
                sample(15_000, "stays", 10.3, 120),
                sample(20_000, "stays", 10.4, 100),
                sample(0, "disappears", 5.0, 50),
                sample(5_000, "disappears", 5.2, 50),
                sample(10_000, "disappears", 5.4, 50),
                sample(10_000, "appears", 0.5, 40),
                sample(15_000, "appears", 0.6, 40),
                sample(20_000, "appears", 0.8, 40),
                sample(0, "restarts", 3.0, 30),
                sample(5_000, "restarts", 3.1, 30),
                sample(10_000, "restarts", 0.05, 10),
                sample(15_000, "restarts", 0.15, 30),
                sample(20_000, "restarts", 0.25, 30));
    }

    private static ResourceSample sample(double at, String pod, double cpuSeconds, long workingSetMiB) {
        return new ResourceSample(at, "node-1", "platform", pod, "main", cpuSeconds, workingSetMiB * MIB, 0);
    }
}
