package at.technikum.thesis.probe;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class ConfigHealth {

    private final ConfigRules rules;

    public ConfigHealth(ConfigRules rules) {
        this.rules = rules;
    }

    @GetMapping("/config-health")
    public ResponseEntity<Object> health() {
        List<String> violations = rules.violations();

        return violations.isEmpty()
                ? ResponseEntity.ok(Map.of("status", "VALID", "configVersion", rules.configVersion()))
                : ResponseEntity.status(503).body(Map.of("status", "INVALID", "violations", violations));
    }
}
