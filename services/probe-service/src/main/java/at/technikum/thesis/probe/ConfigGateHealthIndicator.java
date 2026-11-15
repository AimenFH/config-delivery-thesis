package at.technikum.thesis.probe;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component("configGate")
@ConditionalOnProperty(name = "CONFIG_GATE_ENABLED", havingValue = "true")
public class ConfigGateHealthIndicator implements HealthIndicator {

    private final ConfigRules rules;

    public ConfigGateHealthIndicator(ConfigRules rules) {
        this.rules = rules;
    }

    @Override
    public Health health() {
        List<String> violations = rules.violations();

        return violations.isEmpty()
                ? Health.up().build()
                : Health.down().withDetail("violations", violations).build();
    }
}
