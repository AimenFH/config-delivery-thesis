package at.technikum.thesis.probe;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@RefreshScope
@Component
public class ConfigRules {

    private static final int MIN_TIMEOUT_MS = 100;
    private static final int MAX_TIMEOUT_MS = 30_000;
    private static final int DEFAULT_TIMEOUT_MS = 1000;

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

    public int effectiveTimeoutMs() {
        try {
            int timeout = Integer.parseInt(timeoutMs.trim());
            return timeout < MIN_TIMEOUT_MS || timeout > MAX_TIMEOUT_MS ? DEFAULT_TIMEOUT_MS : timeout;
        } catch (NumberFormatException e) {
            return DEFAULT_TIMEOUT_MS;
        }
    }

    public List<String> violations() {
        List<String> violations = new ArrayList<>();

        try {
            int timeout = Integer.parseInt(timeoutMs.trim());
            if (timeout < MIN_TIMEOUT_MS || timeout > MAX_TIMEOUT_MS) {
                violations.add("demo.timeoutMs=" + timeout + " outside [" + MIN_TIMEOUT_MS + "," + MAX_TIMEOUT_MS + "]");
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
