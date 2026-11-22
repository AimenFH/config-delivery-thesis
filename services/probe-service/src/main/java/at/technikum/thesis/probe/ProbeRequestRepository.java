package at.technikum.thesis.probe;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class ProbeRequestRepository {

    private final JdbcClient jdbc;

    public ProbeRequestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void save(String pod, String configVersion, int timeoutMs) {
        jdbc.sql("select set_config('statement_timeout', :timeout, true)")
                .param("timeout", timeoutMs + "ms")
                .query(String.class)
                .single();
        jdbc.sql("insert into probe_request (pod, config_version) values (:pod, :configVersion)")
                .param("pod", pod)
                .param("configVersion", configVersion)
                .update();
    }

    public List<ProbeRequest> findByPod(String pod) {
        return jdbc.sql("""
                        select id, received_at, pod, config_version
                        from probe_request
                        where pod = :pod
                        order by id
                        """)
                .param("pod", pod)
                .query((rs, rowNumber) -> new ProbeRequest(
                        rs.getLong("id"),
                        rs.getObject("received_at", OffsetDateTime.class).toInstant(),
                        rs.getString("pod"),
                        rs.getString("config_version")))
                .list();
    }
}
