package at.technikum.thesis.probe;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

final class RunningProbe implements AutoCloseable {

    record Answer(int status, String body) {
        JsonNode json() {
            try {
                return new ObjectMapper().readTree(body);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private final ConfigurableApplicationContext context;
    private final HttpClient client = HttpClient.newHttpClient();
    private final int port;

    private RunningProbe(ConfigurableApplicationContext context) {
        this.context = context;
        this.port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
    }

    static RunningProbe start(String... properties) {
        List<String> args = new ArrayList<>();
        args.add("--server.port=0");
        args.add("--management.endpoint.health.probes.enabled=true");
        args.add("--spring.jmx.enabled=false");
        args.add("--spring.datasource.url=" + TestDatabase.container().getJdbcUrl());
        args.add("--spring.datasource.username=" + TestDatabase.container().getUsername());
        args.add("--spring.datasource.password=" + TestDatabase.container().getPassword());
        for (String property : properties) {
            args.add("--" + property);
        }
        return new RunningProbe(new SpringApplicationBuilder(ProbeServiceApplication.class)
                .run(args.toArray(String[]::new)));
    }

    Answer get(String path) {
        return send(HttpRequest.newBuilder(uri(path)).GET().build());
    }

    Answer post(String path) {
        return send(HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.noBody()).build());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private Answer send(HttpRequest request) {
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new Answer(response.statusCode(), response.body());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        context.close();
    }
}
