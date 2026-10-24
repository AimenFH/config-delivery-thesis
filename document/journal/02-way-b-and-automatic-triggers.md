# 02: Way B and automatic triggers

*October 2026, milestone 2*

## Summary

Way B now works. The configuration is mounted into the pods as files, and a change
reaches the running service without a restart: the same three pods, with the same
start time, switched from `v1` to `v2`. After doing the refresh by hand once, I added
an automatic trigger to both approaches, Stakater Reloader for Way A and the Spring
Cloud Kubernetes Configuration Watcher for Way B. A ConfigMap change is now the only
manual step in either approach.

The first automatic test of Way B took 90 seconds from change to new version, longer
than the rolling restart of Way A. Almost all of that time was a safety delay that I
chose myself, so the next task is to measure how short it can safely be.

Reference material: [architecture](../architecture.md),
[decisions](../../docs/DECISIONS.md), [previous entry](01-setup-and-first-experiment.md).

## Environment

Unchanged from [entry 01](01-setup-and-first-experiment.md#environment), plus:

| Component | Version | Image architecture |
|---|---|---|
| Stakater Reloader | v1.4.22 | arm64 available, runs natively |
| Spring Cloud Kubernetes Configuration Watcher | 3.1.6 | amd64 only, runs under emulation |

## Part 1: Way B with a manual refresh

### What had to change

The old Way B manifest could never have worked. `configtree:` turns each file in
`/etc/config/` into a property and uses the file name as the property name. The
ConfigMap keys were `value` and `configVersion`, so Spring would have seen properties
called `value` and `configVersion`, while the code reads `demo.value` and
`demo.configVersion`. Every replica would have answered `UNSET` and `v0`. Way A never
had this problem, because Spring translates the environment variable `DEMO_VALUE`
into `demo.value` on its own. Way B therefore got its own ConfigMap with the keys
`demo.value`, `demo.configVersion` and `demo.timeoutMs`.

The Deployment also lost an annotation that did nothing
(`spring.cloud.kubernetes.configmap.reload`), and gained the same topology spread
rule as Way A, a named port and a NodePort Service. The spread rule matters even more
here: each node's kubelet updates the mounted files on its own schedule, and that
difference only shows up when the replicas run on different nodes.

### What I observed

After deploying, every replica answered `"value":"hello"`, which confirmed that Spring
was reading the mounted files. Inside a pod, each key is a link into a hidden folder:

```
demo.configVersion -> ..data/demo.configVersion
..data -> ..<timestamp>
```

The kubelet writes a new timestamped folder and then switches the `..data` link in a
single step, so all keys change together.

I changed the ConfigMap to `v2` and waited for the files in the pods to change. The
service still answered `v1`: the files had changed, but Spring had not read
them again. Then I sent `POST /actuator/refresh` to each pod. I could not send it
through `localhost:30080`, because the Service picks one pod per connection, so a
temporary `curlimages/curl` pod inside the cluster called each pod by its IP address.

All three pods switched to `v2` at once. The pod names and the `startedAt` values
stayed exactly the same as before the change, so this was a refresh inside the
running JVM and not a restart.

## Part 2: automatic triggers for both approaches

### Why a trigger is needed

The manual refresh showed the gap clearly. Without something that calls the refresh,
Way B changes the files and then nothing happens, and the service keeps the old values
indefinitely. Way A has the same gap in a different place: changing the ConfigMap does
nothing until someone restarts the pods. In both cases I was the trigger.

In practice both gaps are closed by a controller. For Way A I chose Stakater Reloader,
which watches ConfigMaps and starts a rolling restart of the Deployments that ask for
it. The proposal defines Variant A as activation through a rolling restart, and
Reloader still does a rolling restart, so this is still Variant A with the restart
started automatically. For Way B I chose the Configuration Watcher, which is Spring's
own answer: it watches labelled ConfigMaps and calls `/actuator/refresh` on every
instance of the target application. The alternative inside the application, Spring
Cloud Kubernetes reload, is deprecated.

### Way A with Reloader

I saved the Reloader manifest for v1.4.22 in the repository instead of applying it
from GitHub, so the setup can be rebuilt without depending on a URL. Way A's
Deployment only needed one annotation:

```yaml
annotations:
  configmap.reloader.stakater.com/reload: "demo-config"
```

After a plain `kubectl patch` of the ConfigMap, the rolling restart started on its
own. Reloader logged:

> Changes detected in 'demo-config' of type 'CONFIGMAP' in namespace 'thesis';
> updated 'probe-service' of type 'Deployment' in namespace 'thesis'

I did not time this run. The restart followed the same one-pod-at-a-time pattern as
the manual restart in milestone 1.

### Way B with the Configuration Watcher

Every published tag of the Watcher image on Docker Hub is built for amd64 only, and
my kind nodes are arm64. Before writing any manifests I ran the image on my machine
with `--platform linux/amd64`. Spring Boot started and only failed because it was not
inside a cluster (`Unable to load config data from 'kubernetes:'`), which showed that
emulation works. Inside kind I loaded the image with `kind load docker-image`, and the
Watcher started in 13.4 seconds.

The Watcher needed three things from me:

- the label `spring.cloud.kubernetes.config: "true"` on the ConfigMap, because it
  ignores unlabelled ConfigMaps;
- the annotation `spring.cloud.kubernetes.configmap.apps: "probe-service"`, because by
  default it refreshes the application named after the ConfigMap, which would have
  been `demo-config`;
- read permissions on ConfigMaps, pods, Services, endpoints and Secrets, granted
  through a ServiceAccount and a Role.

I set `refreshDelay` to 90 seconds as a first, careful value.

The run, in UTC:

| Time | Event |
|---|---|
| 20:44:03.7 | Watcher logs `will schedule remote refresh based on apps : [probe-service]` |
| 20:45:31 | The service still answers `v1` |
| 20:45:34 | All replicas answer `v2`, with the same pod names and `startedAt` values |

The Watcher also logged two warnings about missing ConfigMaps named after itself. It
is a Spring Cloud Kubernetes application, so it looks for its own configuration that
way, and finding none it falls back to defaults. They do not affect the refresh.

## What I learned

The restart-free approach was the slow one. Way B took 90 seconds, and the delay
almost entirely came from `refreshDelay`. That delay cannot simply go to zero: if the
refresh arrives before the kubelet has rewritten the files, Spring reads the old
files again and the pod stays on the old version until the next change. The lowest
safe value depends on how long the slowest node's kubelet takes, and I have not
measured that yet.

Both approaches need a component that reacts to the change. The difference is what
that component does: Reloader replaces the pods, while the Watcher asks the existing
pods to read their files again.

Where the triggers run affects the measurement. Reloader runs in `default` and stays
up across experiments. The Watcher ran in `thesis`, the namespace I delete to reset
every run, so it would start cold in every Way B run. In this test I changed the
ConfigMap about two minutes after the Watcher had started. With a quicker change it
could still have been starting up. Comparing a warm trigger with a cold one would
partly measure startup time instead of configuration propagation.

An image that exists only for another CPU architecture is easy to miss. The emulated
Watcher worked, but its timing may differ from a native run, and I have to state that
when I report Way B's numbers.

## Decisions made in this phase

| Topic | Decision |
|---|---|
| Way A trigger | Stakater Reloader v1.4.22, manifest pinned in the repository |
| Way B trigger | Spring Cloud Kubernetes Configuration Watcher 3.1.6, amd64 image under emulation |
| `refreshDelay` | 90 s for now, to be tuned after measuring the kubelet delay |
| Experiment fairness | The runner only changes the ConfigMap; activation is left to the trigger in both approaches |
| Location of the triggers | Both move to a separate `platform` namespace that is never reset (in progress) |
| Watcher permissions | ClusterRole instead of a Role, so a reset of `thesis` does not remove them; it can read ConfigMaps and Secrets in every namespace, which is acceptable on a lab cluster only |
| Fourth variant | Not needed for now: Reloader keeps Way A within the proposal's Variant A |

Recorded in full in [docs/DECISIONS.md](../../docs/DECISIONS.md).

## Next

1. Finish the move to `platform`: move Reloader, remove the old copies, install both
   triggers once, and test each approach with only a ConfigMap change.
2. Measure how long the kubelet takes to update the files on each node, several
   times, and set `refreshDelay` just above the slowest observation.
3. Write the open entries in `docs/DECISIONS.md`.
4. Start the experiment runner.
