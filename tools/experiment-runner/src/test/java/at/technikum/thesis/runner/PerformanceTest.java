package at.technikum.thesis.runner;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PerformanceTest {

    private static final Interval B = new Interval(0, 10_000);
    private static final Interval H = new Interval(10_000, 20_000);

    @Test
    void latency_is_receive_time_minus_intended_send_time_of_the_ok_requests_in_the_interval() {
        List<Observation> observations = List.of(
                new Observation(0, 100, 130, 131, 145, 200, "a", "v1", 0, null),
                new Observation(1, 200, 200, 200, 207, 200, "a", "v1", 0, null),
                new Observation(2, 300, 300, 300, 2_300, 0, null, null, 0, "HttpTimeoutException"),
                new Observation(3, 10_000, 10_000, 10_000, 10_001, 200, "a", "v1", 0, null));

        assertEquals(List.of(7.0, 45.0), boxed(Performance.latencies(observations, B)));
    }

    @Test
    void f1_is_the_ok_requests_in_H_slower_than_the_p99_of_B_minus_one_percent_of_the_ok_requests_in_H() {
        List<Observation> observations = baselineOneToHundred();
        for (int i = 0; i < 190; i++) {
            observations.add(request(10_000 + i * 20, 5));
        }
        for (int i = 0; i < 9; i++) {
            observations.add(request(14_000 + i * 20, 150));
        }
        observations.add(request(15_000, 99));
        observations.add(new Observation(0, 16_000, 16_000, 16_000, 18_000, 0, null, null, 0, "HttpTimeoutException"));

        assertEquals(9 - 2.0, Performance.excessSlowRequests(observations, B, H));
    }

    @Test
    void f1_is_negative_when_fewer_than_one_percent_of_the_requests_are_slower_than_the_threshold() {
        List<Observation> observations = baselineOneToHundred();
        for (int i = 0; i < 300; i++) {
            observations.add(request(10_000 + i * 20, 5));
        }

        assertEquals(-3.0, Performance.excessSlowRequests(observations, B, H));
    }

    @Test
    void f1_is_empty_without_an_ok_request_in_B() {
        assertNull(Performance.excessSlowRequests(List.of(request(10_000, 5)), B, H));
    }

    @Test
    void f2_reports_p50_p95_p99_and_max_for_B_and_H_and_the_ratio_of_the_two_p99_values() {
        List<Observation> observations = baselineOneToHundred();
        for (int i = 1; i <= 20; i++) {
            observations.add(request(10_000 + i * 20, i * 10));
        }

        List<Metric> metrics = Performance.percentiles(observations, B, H);

        assertEquals(List.of("F2_p50_B", "F2_p95_B", "F2_p99_B", "F2_max_B",
                        "F2_p50_H", "F2_p95_H", "F2_p99_H", "F2_max_H", "F2_p99_ratio"),
                metrics.stream().map(Metric::column).toList());
        assertEquals(List.of(50.0, 95.0, 99.0, 100.0, 100.0, 190.0, 200.0, 200.0),
                metrics.subList(0, 8).stream().map(Metric::value).toList());
        assertEquals(200.0 / 99.0, metrics.get(8).value(), 1e-12);
    }

    @Test
    void f2_percentiles_use_the_nearest_rank_rule() {
        double[] five = {10, 20, 30, 40, 50};

        assertEquals(30, Performance.percentile(five, 50));
        assertEquals(50, Performance.percentile(five, 95));
        assertEquals(10, Performance.percentile(five, 20));
        assertEquals(20, Performance.percentile(five, 21));
        assertNull(Performance.percentile(new double[0], 50));
    }

    @Test
    void f3_is_the_highest_p95_of_any_1_s_bucket_of_H() {
        List<Observation> observations = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            observations.add(request(10_000 + i * 20, i));
        }
        for (int i = 0; i < 10; i++) {
            observations.add(request(11_000 + i * 20, 5));
        }
        observations.add(request(11_500, 80));
        for (int i = 0; i < 20; i++) {
            observations.add(request(12_000 + i * 20, 3));
        }

        assertEquals(80, Performance.worstSecond(observations, new Interval(10_000, 13_000)));
        assertEquals(19, Performance.worstSecond(observations, new Interval(10_000, 11_000)));
    }

    @Test
    void f3_assigns_a_request_to_a_bucket_by_its_intended_time() {
        List<Observation> observations = new ArrayList<>();
        observations.add(request(10_990, 500));
        for (int i = 0; i < 30; i++) {
            observations.add(request(11_000 + i * 20, 4));
        }

        assertEquals(500, Performance.worstSecond(observations, new Interval(10_000, 12_000)));
        assertEquals(4, Performance.worstSecond(observations, new Interval(11_000, 12_000)));
    }

    private static List<Observation> baselineOneToHundred() {
        List<Observation> observations = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            observations.add(request(i * 20, i));
        }
        return observations;
    }

    private static Observation request(double intendedAt, double latency) {
        return new Observation(0, intendedAt, intendedAt, intendedAt, intendedAt + latency, 200, "a", "v1", 0, null);
    }

    private static List<Double> boxed(double[] values) {
        List<Double> list = new ArrayList<>();
        for (double value : values) {
            list.add(value);
        }
        return list;
    }
}
