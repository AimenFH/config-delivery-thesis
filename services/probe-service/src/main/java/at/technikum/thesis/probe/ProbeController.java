package at.technikum.thesis.probe;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The object under study.
 *
 * @RefreshScope means: on /actuator/refresh this bean is discarded and rebuilt
 * on next access. Note that the rebuild is LAZY - it happens when a request
 * arrives, not at refresh time. Measuring that laziness is part of the thesis.
 */
@RefreshScope
@RestController
public class ProbeController {

    private final String value;
    private final String configVersion;
    private final PodIdentity identity;

    public ProbeController(@Value("${demo.value:UNSET}") String value,
                           @Value("${demo.configVersion:UNSET}") String configVersion,
                           PodIdentity identity) {
        this.value = value;
        this.configVersion = configVersion;
        this.identity = identity;
    }

    @GetMapping("/probe")
    public ProbeResponse probe() {
        return new ProbeResponse(
                value,
                configVersion,
                identity.podName(),
                identity.startedAt(),
                System.currentTimeMillis()
        );
    }
}
