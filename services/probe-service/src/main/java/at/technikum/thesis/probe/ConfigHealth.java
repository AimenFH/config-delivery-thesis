package at.technikum.thesis.probe;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RefreshScope
@RestController
public class ConfigHealth {

    private final String timeoutMs;
    private final String configVersion;

    public ConfigHealth(@Value("${demo.timeoutMs:1000}") String timeoutMs,
                        @Value("${demo.configVersion:v0}") String configVersion) {
        this.timeoutMs = timeoutMs;
        this.configVersion = configVersion;
    }

    @GetMapping("/config-health")
    public ResponseEntity<Object> health() {
        List<String> violations = new ArrayList<>();

        try {
            int timeout = Integer.parseInt(timeoutMs.trim());
            if (timeout < 100 || timeout > 30_000) {
                violations.add("demo.timeoutMs=" + timeout + " outside [100,30000]");
            }
        } catch (NumberFormatException e) {
            violations.add("demo.timeoutMs='" + timeoutMs + "' is not an integer");
        }
        if (!configVersion.matches("v\\d+")) {
            violations.add("demo.configVersion='" + configVersion + "' does not match v<number>");
        }

        return violations.isEmpty()
                ? ResponseEntity.ok(Map.of("status", "VALID", "configVersion", configVersion))
                : ResponseEntity.status(503).body(Map.of("status", "INVALID", "violations", violations));
    }
}
