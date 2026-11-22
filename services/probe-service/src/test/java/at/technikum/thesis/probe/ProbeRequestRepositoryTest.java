package at.technikum.thesis.probe;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ProbeRequestRepository.class)
class ProbeRequestRepositoryTest {

    @ServiceConnection
    static PostgreSQLContainer<?> postgres = TestDatabase.container();

    @Autowired
    ProbeRequestRepository repository;

    @Autowired
    JdbcClient jdbc;

    @Test
    void flyway_creates_the_table_probe_request_with_its_four_columns() {
        List<String> columns = jdbc.sql("""
                        select column_name
                        from information_schema.columns
                        where table_name = 'probe_request'
                        order by ordinal_position
                        """)
                .query(String.class)
                .list();

        assertEquals(List.of("id", "received_at", "pod", "config_version"), columns);
    }

    @Test
    void save_stores_pod_and_config_version_and_the_database_sets_the_time() {
        repository.save("pod-a", "v1", 1000);

        List<ProbeRequest> rows = repository.findByPod("pod-a");

        assertEquals(1, rows.size());
        assertEquals("pod-a", rows.get(0).pod());
        assertEquals("v1", rows.get(0).configVersion());
        assertTrue(Duration.between(rows.get(0).receivedAt(), Instant.now()).abs().toSeconds() < 60);
    }

    @Test
    void find_by_pod_returns_the_rows_of_that_pod_in_insert_order() {
        repository.save("pod-b", "v1", 1000);
        repository.save("pod-c", "v1", 1000);
        repository.save("pod-b", "v2", 1000);

        List<ProbeRequest> rows = repository.findByPod("pod-b");

        assertEquals(List.of("v1", "v2"), rows.stream().map(ProbeRequest::configVersion).toList());
        assertTrue(rows.get(0).id() < rows.get(1).id());
    }

    @Test
    void find_by_pod_returns_an_empty_list_for_an_unknown_pod() {
        assertTrue(repository.findByPod("pod-unknown").isEmpty());
    }
}
