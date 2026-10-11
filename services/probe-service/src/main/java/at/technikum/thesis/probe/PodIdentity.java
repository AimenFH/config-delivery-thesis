package at.technikum.thesis.probe;

import org.springframework.stereotype.Component;

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
