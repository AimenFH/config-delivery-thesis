package at.technikum.thesis.runner;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadGeneratorTest {

    static {
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "connection");
    }

    private HttpServer server;
    private URI url;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/probe", exchange -> {
            byte[] body = "{\"pod\":\"pod-a\",\"configVersion\":\"v1\",\"startedAt\":1}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/probe");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void intendedAtIsTheStartPlusSeqTimesThePeriod() throws Exception {
        RunnerClock clock = new RunnerClock();
        LoadGenerator load = new LoadGenerator(new ProbeClient(url, clock), clock, 50);

        load.start();
        Thread.sleep(400);
        List<Observation> observations = load.stop();

        assertTrue(observations.size() >= 10, "only " + observations.size() + " observations");
        assertEquals(load.scheduledCount(), observations.size());
        for (int i = 0; i < observations.size(); i++) {
            Observation o = observations.get(i);
            assertEquals(i, o.seq());
            assertEquals(load.startedAt() + i * 20.0, o.intendedAt(), 1e-9);
            assertTrue(o.ok(), String.valueOf(o.error()));
            assertEquals(o.receivedAt() - o.intendedAt(), o.latencyMillis());
        }
    }

    @Test
    void runStartsAndStopsTheLoad() throws Exception {
        RunnerClock clock = new RunnerClock();
        LoadGenerator load = new LoadGenerator(new ProbeClient(url, clock), clock, 100);

        List<Observation> observations = load.run(Duration.ofMillis(200));

        assertTrue(observations.size() >= 5, "only " + observations.size() + " observations");
        assertEquals(load.startedAt() + 10.0, observations.get(1).intendedAt(), 1e-9);
    }

    @Test
    void aLoadGeneratorStartsOnlyOnce() throws Exception {
        RunnerClock clock = new RunnerClock();
        LoadGenerator load = new LoadGenerator(new ProbeClient(url, clock), clock, 50);

        assertThrows(IllegalStateException.class, load::stop);
        load.start();
        assertThrows(IllegalStateException.class, load::start);
        load.stop();
    }
}
