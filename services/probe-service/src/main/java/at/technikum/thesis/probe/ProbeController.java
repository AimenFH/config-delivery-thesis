package at.technikum.thesis.probe;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RefreshScope
@RestController
public class ProbeController {

    private final String value;
    private final String configVersion;
    private final PodIdentity identity;
    private final ConfigRules rules;
    private final ProbeRequestRepository requests;

    public ProbeController(@Value("${demo.value:UNSET}") String value,
                           @Value("${demo.configVersion:v0}") String configVersion,
                           PodIdentity identity,
                           ConfigRules rules,
                           ProbeRequestRepository requests) {
        this.value = value;
        this.configVersion = configVersion;
        this.identity = identity;
        this.rules = rules;
        this.requests = requests;
    }

    @GetMapping("/probe")
    public ProbeResponse probe() {
        requests.save(identity.podName(), configVersion, rules.effectiveTimeoutMs());

        return new ProbeResponse(
                value,
                configVersion,
                identity.podName(),
                identity.startedAt(),
                System.currentTimeMillis()
        );
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<Map<String, String>> databaseUnavailable() {
        return ResponseEntity.status(503).body(Map.of("error", "database"));
    }
}
