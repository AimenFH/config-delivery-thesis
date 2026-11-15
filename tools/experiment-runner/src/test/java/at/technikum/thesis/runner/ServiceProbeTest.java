package at.technikum.thesis.runner;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceProbeTest {

    static {
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "connection");
    }

    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer server;
    private ServiceProbe probe;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/probe", exchange -> {
            int number = requests.getAndIncrement();
            byte[] body = ("{\"pod\":\"pod-" + number % 3 + "\",\"configVersion\":\"v1\",\"startedAt\":1}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/probe";
        probe = ServiceProbe.http(Fixtures.protocolWith(tree -> tree.put("url", url)));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void the_given_number_of_probes_is_sent_to_the_url_of_the_protocol_one_after_the_other() {
        List<Observation> observations = probe.probe(6, new RunnerClock());

        assertEquals(6, requests.get());
        assertEquals(List.of("pod-0", "pod-1", "pod-2", "pod-0", "pod-1", "pod-2"),
                observations.stream().map(Observation::pod).toList());
        assertTrue(observations.stream().allMatch(Observation::ok));
        assertTrue(observations.stream().allMatch(o -> o.latencyMillis() >= 0));
    }

    @Test
    void the_load_runs_at_the_rate_of_the_protocol_from_start_to_stop() throws Exception {
        RunnerClock clock = new RunnerClock();

        ServiceProbe.Load load = probe.startLoad(clock);
        Thread.sleep(300);
        List<Observation> observations = load.stop();

        assertTrue(observations.size() >= 8, "only " + observations.size() + " observations");
        assertEquals(load.scheduledCount(), observations.size());
        assertTrue(load.startedAt() >= 0 && load.startedAt() < 300, "started at " + load.startedAt());
        for (int i = 0; i < observations.size(); i++) {
            assertEquals(load.startedAt() + i * 20.0, observations.get(i).intendedAt(), 1e-9);
        }
    }
}
