package at.technikum.thesis.probe;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProbeStoresRequestTest {

    @Test
    void every_probe_request_stores_one_row_with_its_config_version() {
        try (RunningProbe probe = RunningProbe.start("demo.configVersion=v71")) {
            assertEquals(200, probe.get("/probe").status());
            assertEquals(200, probe.get("/probe").status());

            assertEquals(2, TestDatabase.rowsWithVersion("v71"));
        }
    }

    @Test
    void the_stored_pod_is_the_pod_of_the_answer() throws SQLException {
        try (RunningProbe probe = RunningProbe.start("demo.configVersion=v72")) {
            String pod = probe.get("/probe").json().get("pod").asString();

            try (Connection connection = TestDatabase.connect();
                 Statement statement = connection.createStatement();
                 var result = statement.executeQuery(
                         "select pod from probe_request where config_version = 'v72'")) {
                assertTrue(result.next());
                assertEquals(pod, result.getString(1));
            }
        }
    }

    @Test
    void an_invalid_timeoutMs_still_answers_200_and_stores_the_row() {
        try (RunningProbe probe = RunningProbe.start("demo.configVersion=v73", "demo.timeoutMs=abc")) {
            assertEquals(200, probe.get("/probe").status());

            assertEquals(1, TestDatabase.rowsWithVersion("v73"));
        }
    }

    @Test
    void a_locked_table_answers_503_after_timeoutMs_and_200_again_after_the_lock_is_gone() throws SQLException {
        try (RunningProbe probe = RunningProbe.start("demo.configVersion=v74", "demo.timeoutMs=200");
             Connection blocker = TestDatabase.connect()) {
            assertEquals(200, probe.get("/probe").status());

            blocker.setAutoCommit(false);
            try (Statement statement = blocker.createStatement()) {
                statement.execute("lock table probe_request in access exclusive mode");
            }

            long before = System.nanoTime();
            RunningProbe.Answer blocked = probe.get("/probe");
            long elapsedMillis = (System.nanoTime() - before) / 1_000_000;

            assertEquals(503, blocked.status());
            assertEquals("database", blocked.json().get("error").asString());
            assertTrue(elapsedMillis >= 200 && elapsedMillis < 1500, "elapsed " + elapsedMillis + " ms");

            blocker.rollback();

            assertEquals(200, probe.get("/probe").status());
            assertEquals(2, TestDatabase.rowsWithVersion("v74"));
        }
    }
}
