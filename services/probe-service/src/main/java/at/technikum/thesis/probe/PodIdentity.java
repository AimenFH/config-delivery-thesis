package at.technikum.thesis.probe;

import org.springframework.stereotype.Component;

/**
 * Deliberately NOT @RefreshScope.
 *
 * Pod name and process start time must survive a refresh - if they were rebuilt,
 * startedAt would reset and the scale-out scenario could not be measured.
 * This is also the first concrete example of a bean that a refresh does not touch,
 * which is the intra-JVM angle discussed in the thesis.
 */
@Component
public class PodIdentity {

    private final String podName;
    private final long startedAt;

    public PodIdentity() {
        String host = System.getenv("HOSTNAME");
        this.podName = (host == null || host.isBlank()) ? "local" : host;
        this.startedAt = System.currentTimeMillis();
    }

    public String podName()  { return podName; }
    public long   startedAt() { return startedAt; }
}
