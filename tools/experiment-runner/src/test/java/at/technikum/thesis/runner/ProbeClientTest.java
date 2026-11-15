package at.technikum.thesis.runner;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProbeClientTest {

    static {
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "connection");
    }

    private final ExecutorService handlers = Executors.newCachedThreadPool();
    private HttpServer server;
    private String base;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(handlers);
        server.createContext("/fast", exchange -> answerAfter(exchange, 0));
        server.createContext("/slow", exchange -> answerAfter(exchange, 600));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        handlers.shutdownNow();
    }

    @Test
    void a_request_without_an_answer_within_the_timeout_is_failed_and_is_received_at_about_the_timeout() {
        RunnerClock clock = new RunnerClock();
        ProbeClient client = new ProbeClient(URI.create(base + "/slow"), clock, Duration.ofMillis(200));

        Observation observation = client.probe(0, 0, 0).join();

        assertFalse(observation.ok());
        assertTrue(Availability.failed(observation));
        assertEquals(0, observation.status());
        assertNull(observation.pod());
        assertTrue(observation.error().contains("HttpTimeoutException"), observation.error());
        double waited = observation.receivedAt() - observation.sentAt();
        assertTrue(waited >= 190 && waited < 500, "waited " + waited + " ms");
    }

    @Test
    void an_answer_within_the_timeout_is_ok() {
        RunnerClock clock = new RunnerClock();
        ProbeClient client = new ProbeClient(URI.create(base + "/fast"), clock, Duration.ofMillis(200));

        Observation observation = client.probe(0, 0, 0).join();

        assertTrue(observation.ok(), String.valueOf(observation.error()));
        assertEquals(200, observation.status());
        assertEquals("pod-a", observation.pod());
        assertEquals("v1", observation.configVersion());
    }

    @Test
    void the_constructor_without_a_timeout_waits_2_s_so_an_answer_after_600_ms_is_ok() {
        RunnerClock clock = new RunnerClock();
        ProbeClient client = new ProbeClient(URI.create(base + "/slow"), clock);

        Observation observation = client.probe(0, 0, 0).join();

        assertTrue(observation.ok(), String.valueOf(observation.error()));
        assertTrue(observation.receivedAt() - observation.sentAt() >= 590);
    }

    @Test
    void the_http_service_probe_uses_the_request_timeout_of_the_protocol_for_single_probes() throws Exception {
        ServiceProbe shortTimeout = ServiceProbe.http(Fixtures.protocolWith(tree -> tree.put("url", base + "/slow")
                .put("requestTimeoutMillis", 200)));
        ServiceProbe protocolTimeout = ServiceProbe.http(Fixtures.protocolWith(tree -> tree.put("url", base + "/slow")));

        Observation failed = shortTimeout.probe(1, new RunnerClock()).get(0);
        Observation ok = protocolTimeout.probe(1, new RunnerClock()).get(0);

        assertFalse(failed.ok());
        assertTrue(failed.error().contains("HttpTimeoutException"), failed.error());
        assertTrue(failed.receivedAt() - failed.sentAt() < 500, "waited " + (failed.receivedAt() - failed.sentAt()));
        assertEquals(2_000, Fixtures.protocol().requestTimeoutMillis());
        assertTrue(ok.ok(), String.valueOf(ok.error()));
    }

    @Test
    void the_http_service_probe_uses_the_request_timeout_of_the_protocol_for_the_load() throws Exception {
        ServiceProbe shortTimeout = ServiceProbe.http(Fixtures.protocolWith(tree -> tree.put("url", base + "/slow")
                .put("requestTimeoutMillis", 200)));

        ServiceProbe.Load load = shortTimeout.startLoad(new RunnerClock());
        Thread.sleep(200);
        List<Observation> observations = load.stop();

        assertTrue(observations.size() >= 5, "only " + observations.size() + " observations");
        for (Observation observation : observations) {
            assertFalse(observation.ok());
            assertTrue(observation.error().contains("HttpTimeoutException"), observation.error());
            assertTrue(observation.receivedAt() - observation.sentAt() < 500,
                    "waited " + (observation.receivedAt() - observation.sentAt()));
        }
    }

    private static void answerAfter(HttpExchange exchange, long delayMillis) {
        try {
            Thread.sleep(delayMillis);
            byte[] body = "{\"pod\":\"pod-a\",\"configVersion\":\"v1\",\"startedAt\":1}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } catch (IOException | InterruptedException ignored) {
        } finally {
            exchange.close();
        }
    }
}
