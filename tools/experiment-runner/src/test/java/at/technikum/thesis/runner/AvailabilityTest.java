package at.technikum.thesis.runner;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvailabilityTest {

    private static final double LOAD_STARTED_AT = 1_000;
    private static final Interval WHOLE = new Interval(1_000, 3_000);

    @Test
    void a_request_is_failed_when_it_is_not_ok_or_has_no_pod_or_no_version() {
        assertFalse(Availability.failed(ok(0, "a")));
        assertTrue(Availability.failed(new Observation(1, 0, 0, 0, 5, 503, "a", "v1", 0, null)));
        assertTrue(Availability.failed(new Observation(2, 0, 0, 0, 2000, 0, null, null, 0, "HttpTimeoutException")));
        assertTrue(Availability.failed(new Observation(3, 0, 0, 0, 5, 200, null, "v1", 0, null)));
        assertTrue(Availability.failed(new Observation(4, 0, 0, 0, 5, 200, "a", null, 0, null)));
        assertTrue(Availability.failed(new Observation(5, 0, 0, 0, 5, 200, "a", "v1", 0, "BadBody")));
    }

    @Test
    void tick_seq_has_the_intended_time_load_start_plus_seq_times_the_period() {
        Availability availability = availability(100);

        assertEquals(1_000, availability.intendedAt(0));
        assertEquals(1_020, availability.intendedAt(1));
        assertEquals(2_980, availability.intendedAt(99));
        assertEquals(20, availability.periodMillis());
    }

    @Test
    void scheduled_requests_in_an_interval_are_the_ticks_whose_intended_time_lies_in_it() {
        Availability availability = availability(100);

        assertEquals(100, availability.scheduledIn(WHOLE));
        assertEquals(10, availability.scheduledIn(new Interval(1_200, 1_400)));
        assertEquals(11, availability.scheduledIn(new Interval(1_200, 1_400.1)));
        assertEquals(0, availability.scheduledIn(new Interval(3_000, 4_000)));
        assertEquals(1, availability.scheduledIn(new Interval(2_980, 9_000)));
    }

    @Test
    void a1_is_failed_in_H_divided_by_scheduled_in_H() {
        Availability availability = availability(100);

        assertEquals(6, availability.failedIn(WHOLE));
        assertEquals(0.06, availability.failedRatio(WHOLE));
        assertEquals(0.5, availability.failedRatio(new Interval(1_200, 1_400)));
        assertEquals(0.0, availability.failedRatio(new Interval(1_000, 1_200)));
    }

    @Test
    void a_tick_without_an_observation_counts_as_failed() {
        Availability availability = availability(100);

        assertEquals(1, availability.missingIn(WHOLE));
        assertEquals(1, availability.failedIn(new Interval(1_260, 1_280)));
        assertEquals(1.0, availability.failedRatio(new Interval(1_260, 1_280)));
    }

    @Test
    void ticks_after_the_last_observation_count_as_scheduled_and_failed() {
        Availability availability = availability(110);
        Interval tail = new Interval(3_000, 3_200);

        assertEquals(10, availability.scheduledIn(tail));
        assertEquals(10, availability.missingIn(tail));
        assertEquals(1.0, availability.failedRatio(tail));
    }

    @Test
    void a1_is_empty_when_no_request_is_scheduled_in_the_interval() {
        assertNull(availability(100).failedRatio(new Interval(5_000, 6_000)));
    }

    @Test
    void a2_is_the_longest_run_of_consecutive_failed_requests_as_count_times_period() {
        Availability availability = availability(100);

        assertEquals(100, availability.longestOutageMillis(WHOLE));
    }

    @Test
    void a2_counts_only_the_ticks_inside_the_interval() {
        Availability availability = availability(100);

        assertEquals(60, availability.longestOutageMillis(new Interval(1_240, 3_000)));
        assertEquals(20, availability.longestOutageMillis(new Interval(1_300, 3_000)));
        assertEquals(0, availability.longestOutageMillis(new Interval(2_100, 3_000)));
    }

    @Test
    void a3_is_the_lowest_number_of_distinct_answering_pods_in_any_2_s_bucket() {
        List<Observation> observations = new ArrayList<>();
        for (long seq = 0; seq < 300; seq++) {
            String pod = seq >= 100 && seq < 200 ? List.of("a", "b").get((int) (seq % 2))
                    : List.of("a", "b", "c").get((int) (seq % 3));
            observations.add(ok(seq, pod));
        }
        Availability availability = new Availability(observations, LOAD_STARTED_AT, 300, 50);

        assertEquals(2, availability.lowestServingPods(new Interval(1_000, 7_000)));
        assertEquals(3, availability.lowestServingPods(new Interval(1_000, 3_000)));
    }

    @Test
    void a3_buckets_start_at_the_interval_start_and_a_last_bucket_shorter_than_2_s_is_left_out() {
        List<Observation> observations = new ArrayList<>();
        for (long seq = 0; seq < 250; seq++) {
            String pod = seq >= 200 ? "a" : List.of("a", "b", "c").get((int) (seq % 3));
            observations.add(ok(seq, pod));
        }
        Availability availability = new Availability(observations, LOAD_STARTED_AT, 250, 50);

        assertEquals(3, availability.lowestServingPods(new Interval(1_000, 6_000)));
        assertEquals(1, availability.lowestServingPods(new Interval(1_000, 7_000)));
        assertEquals(3, availability.lowestServingPods(new Interval(4_900, 6_900)));
        assertEquals(1, availability.lowestServingPods(new Interval(5_000, 7_000)));
        assertNull(availability.lowestServingPods(new Interval(1_000, 2_999)));
    }

    @Test
    void a3_is_zero_when_a_bucket_has_no_answer_and_failed_requests_do_not_serve() {
        List<Observation> observations = new ArrayList<>();
        for (long seq = 0; seq < 300; seq++) {
            if (seq < 100) {
                observations.add(ok(seq, "a"));
            } else if (seq < 200) {
                observations.add(new Observation(seq, 1_000 + seq * 20, 0, 0, 0, 503, "a", "v1", 0, null));
            } else {
                observations.add(ok(seq, "b"));
            }
        }
        Availability availability = new Availability(observations, LOAD_STARTED_AT, 300, 50);

        assertEquals(0, availability.lowestServingPods(new Interval(1_000, 7_000)));
    }

    private static Availability availability(long scheduled) {
        List<Observation> observations = new ArrayList<>();
        for (long seq = 0; seq < 100; seq++) {
            double at = LOAD_STARTED_AT + seq * 20;
            if (seq == 10 || seq == 50) {
                observations.add(new Observation(seq, at, at, at, at + 5, 503, "a", "v1", 0, null));
            } else if (seq == 11) {
                observations.add(new Observation(seq, at, at, at, at + 2000, 0, null, null, 0, "HttpTimeoutException"));
            } else if (seq == 12) {
                observations.add(new Observation(seq, at, at, at, at + 5, 200, null, "v1", 0, null));
            } else if (seq == 14) {
                observations.add(new Observation(seq, at, at, at, at + 5, 200, "a", null, 0, null));
            } else if (seq != 13) {
                observations.add(ok(seq, "a"));
            }
        }
        return new Availability(observations, LOAD_STARTED_AT, scheduled, 50);
    }

    private static Observation ok(long seq, String pod) {
        double at = LOAD_STARTED_AT + seq * 20;
        return new Observation(seq, at, at, at, at + 5, 200, pod, "v1", 0, null);
    }
}
