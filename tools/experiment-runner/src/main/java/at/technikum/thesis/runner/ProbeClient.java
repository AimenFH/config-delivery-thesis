package at.technikum.thesis.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

public final class ProbeClient {

    private final HttpClient http;
    private final HttpRequest request;
    private final RunnerClock clock;
    private final ObjectMapper json = new ObjectMapper();

    public ProbeClient(URI url, RunnerClock clock) {
        this.clock = clock;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
        this.request = HttpRequest.newBuilder(url)
                .header("Connection", "close")
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();
    }

    public CompletableFuture<Observation> probe(long seq, double scheduledAt) {
        double sentAt = clock.millisSinceStart();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .handle((response, failure) -> {
                    double receivedAt = clock.millisSinceStart();
                    if (failure != null) {
                        return new Observation(seq, scheduledAt, sentAt, receivedAt, 0,
                                null, null, 0, failure.getClass().getSimpleName());
                    }
                    try {
                        JsonNode body = json.readTree(response.body());
                        return new Observation(seq, scheduledAt, sentAt, receivedAt, response.statusCode(),
                                body.path("pod").asText(null),
                                body.path("configVersion").asText(null),
                                body.path("startedAt").asLong(),
                                null);
                    } catch (Exception e) {
                        return new Observation(seq, scheduledAt, sentAt, receivedAt, response.statusCode(),
                                null, null, 0, "BadBody");
                    }
                });
    }
}