package at.technikum.thesis.probe;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@RefreshScope
@Component
public class ConfigRules {

    private final String timeoutMs;
    private final String configVersion;

    public ConfigRules(@Value("${demo.timeoutMs:1000}") String timeoutMs,
                       @Value("${demo.configVersion:v0}") String configVersion) {
        this.timeoutMs = timeoutMs;
        this.configVersion = configVersion;
    }

    public String configVersion() {
        return configVersion;
    }

    public List<String> violations() {
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

        return violations;
    }
}
