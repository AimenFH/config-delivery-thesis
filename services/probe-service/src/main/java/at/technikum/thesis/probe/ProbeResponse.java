package at.technikum.thesis.probe;

/**
 * One observation of this replica's configuration state.
 *
 * Every field exists for a measurement reason:
 *  value          - the configured content itself
 *  configVersion  - identifies old vs new; this is what the window detector compares
 *  pod            - identifies WHICH replica answered; without it drift is invisible
 *  startedAt      - epoch millis of process start; distinguishes a fresh pod from an old one
 *                   (needed for the scale-out-during-change scenario)
 *  servedAt       - pod-local time; SECONDARY signal only. The authoritative timestamp is
 *                   taken client-side by the experiment runner, because replicas sit on
 *                   different nodes whose clock offset may exceed the measured window.
 */
public record ProbeResponse(
        String value,
        String configVersion,
        String pod,
        long startedAt,
        long servedAt
) {}
