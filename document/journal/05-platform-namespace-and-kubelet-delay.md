# 05: Platform namespace and kubelet delay

*November 2026, milestone 4*

## Summary

Both activation triggers now run in the `platform` namespace, which is installed
once and never reset. Moving the Configuration Watcher there broke Way B without
any error: the Watcher still saw every ConfigMap change, but no longer found the
pods to refresh. One discovery setting fixed it.

I then measured how long the kubelet takes to write a changed ConfigMap into the
mounted files. Over 30 values the delay was between 9.2 s and 77.6 s. The Watcher's
`refreshDelay` stays at 90 s, now set from measured data.

| | Result |
|---|---|
| Way B from `platform`, propagation | 90.4 s |
| Way B from `platform`, window | 3 ms (at most 105 ms) |
| Kubelet delay, 30 values | 9.2 s to 77.6 s, median 31.8 s |
| `refreshDelay` | 90 s, unchanged |

Reference material: [decisions](../../docs/DECISIONS.md),
[runbook](../runbook-way-a.md), [previous entry](04-change-and-window-detection.md).

## Environment

Unchanged from [entry 01](01-setup-and-first-experiment.md#environment). The
triggers moved:

| Trigger | Before | Now |
|---|---|---|
| Stakater Reloader (Way A) | `default` | `platform` |
| Configuration Watcher (Way B) | `thesis` | `platform` |

## What I changed

| File | Change |
|---|---|
| `deploy/reloader/reloader.yaml` | All 6 `namespace: default` lines set to `platform` |
| `deploy/platform/watcher.yaml` | New variable `SPRING_CLOUD_KUBERNETES_DISCOVERY_NAMESPACES_0=thesis` |
| `tools/kubelet-delay.sh` | New script that measures the kubelet delay per pod |
| `results/kubelet-delay.txt` | The 10 measurement runs |

The old Watcher in `thesis` (Deployment, Role, RoleBinding, ServiceAccount) is
deleted. Its manifest was already gone from the repository, so I deleted it by name.

## What I observed

### Reloader came back in `default`

The first apply put Reloader back into `default`, because the file still said
`default` everywhere. I removed it with `kubectl delete -f` before editing the
file. That order matters: `delete -f` deletes in the namespace written in the
file, so after editing it would have looked in `platform` and left the old one
running.

Reloader logs `Starting Controller to watch resource type: configmaps in
namespace: ` with an empty namespace, which means it watches all namespaces. Way A
needs no further setting.

### The Watcher stopped refreshing, without an error

After the move I changed the Way B version to `v2` at 19:28:25 UTC. The refresh
was due 90 s later. At 19:30:28:

| What I checked | Result |
|---|---|
| Watcher log | `will schedule remote refresh based on apps : [probe-service]`, nothing else |
| Mounted file in all 3 pods | `v2` |
| Answers of all 3 pods | `v1` |

The kubelet had done its part, but the refresh never reached the pods. The Watcher
finds the pods to refresh through Kubernetes service discovery, and discovery
searches only the Watcher's own namespace by default. In `thesis` that was the
namespace of `probe-service`. In `platform` it found nothing and skipped the
refresh. `SPRING_CLOUD_KUBERNETES_RELOAD_NAMESPACES_0=thesis` only decides which
ConfigMaps the Watcher watches, not where it looks for pods.

The runner run from that time ended invalid with "new version never observed",
which is how a broken Way B looks from outside. The same run also showed pod
`74g85` unobserved for 1 216 ms, over the 1 000 ms limit, which none of the
earlier runs at 50 req/s had done.

After adding `SPRING_CLOUD_KUBERNETES_DISCOVERY_NAMESPACES_0=thesis`, the restarted
Watcher refreshed the pods once on startup and they went to `v2`. A runner check
from `v2` to `v3` was valid, with a propagation of 90.4 s and a window of 3 ms (at
most 105 ms), the same as before the move.

### The kubelet delay

`tools/kubelet-delay.sh` changes the ConfigMap and reads
`/etc/config/demo.configVersion` in every pod every 0.5 s until it shows the new
value. Each read is a `kubectl exec` of 100 to 300 ms, so the values are accurate to
about 1 s. I ran it for 10 changes (`v11` to `v20`), about 2 minutes apart.

| Pod | Node | Fastest | Median | Slowest |
|---|---|---|---|---|
| `4sm7h` | `thesis-worker` | 9.2 s | 26.8 s | 68.7 s |
| `74g85` | `thesis-worker2` | 26.5 s | 39.6 s | 67.5 s |
| `st95w` | `thesis-worker3` | 11.6 s | 32.1 s | 77.6 s |

The slowest pod per change took between 29.4 s and 77.6 s. No node was always the
slowest. The delay depends on where each kubelet is in its own sync cycle when the
change arrives, so it changes from change to change and from pod to pod.

The slowest value plus a 10 s margin would be 88 s. That saves 2 s against the
current 90 s and leaves less room, so `refreshDelay` stays at 90 s, 12.4 s above
the slowest measurement.

## What I learned

A component that cannot find its targets does not always fail loudly. The Watcher
logged the same line as when everything worked. Only the runner, which checks the
version the pods actually serve, showed that Way B was broken.

The kubelet delay is random per change and per pod, so only its maximum matters for
`refreshDelay`. Thirty values show where the maximum lies, but do not prove it. A
pod that is still on the old version at the end of a Way B run is the first sign
that the delay was exceeded.

`kubectl delete -f` and `kubectl apply -f` act on the namespace written in the
file. When a manifest moves to another namespace, delete the old resources before
editing the file.

## Decisions made in this phase

| Topic | Decision |
|---|---|
| Trigger location | Both triggers in `platform`, as planned in the 2026-10-18 entry |
| Watcher discovery | `SPRING_CLOUD_KUBERNETES_DISCOVERY_NAMESPACES_0=thesis`, limited to `thesis` instead of all namespaces |
| `refreshDelay` | Stays at 90 s, now measured: 12.4 s above the slowest of 30 kubelet delays |

The `refreshDelay` update is recorded in
[docs/DECISIONS.md](../../docs/DECISIONS.md), in the 2026-10-17 entry.

## Next

1. Repeat both Ways about 10 times each, Way A with `preStop`, so the thesis can
   report a spread instead of single values.
2. Watch the coverage gap: if more runs go over 1 000 ms at 50 req/s, raise the rate
   or the limit and record why.
3. Start Way C: Config Server and Spring Cloud Bus.
