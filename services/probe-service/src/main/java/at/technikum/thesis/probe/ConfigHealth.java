package at.technikum.thesis.probe;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.ArrayList;

/**
 * Self-validation of the currently active configuration.
 *
 * Used by the "invalid configuration" scenario: it answers how long it takes
 * a replica to NOTICE that it is holding a broken configuration - as opposed to
 * how long it takes to receive one. Returns 503 when the active config is invalid.
 */
@RefreshScope
@RestController
public class ConfigHealth {

    private final int timeoutMs;
    private final String configVersion;

    public ConfigHealth(@Value("${demo.timeoutMs:1000}") int timeoutMs,
                        @Value("${demo.configVersion:UNSET}") String configVersion) {
        this.timeoutMs = timeoutMs;
        this.configVersion = configVersion;
    }

    @GetMapping("/config-health")
    public ResponseEntity<Object> health() {
        List<String> violations = new ArrayList<>();

        if (timeoutMs < 100 || timeoutMs > 30_000) {
            violations.add("demo.timeoutMs=" + timeoutMs + " outside [100,30000]");
        }
        if (!configVersion.matches("v\\d+")) {
            violations.add("demo.configVersion='" + configVersion + "' does not match v<number>");
        }

        return violations.isEmpty()
                ? ResponseEntity.ok(java.util.Map.of("status", "VALID", "configVersion", configVersion))
                : ResponseEntity.status(503).body(java.util.Map.of("status", "INVALID", "violations", violations));
    }
}
