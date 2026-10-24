# Design Decisions

One entry per decision, written the day it is made. Four lines each:
**Problem** / **Rejected** / **Chosen** / **Cost**.

These entries become chapters 5 and 6 of the thesis. Do not write them later —
the reasoning is only cheap to record while it is fresh.

---

## 2026-09-16 — Measure client-side, not inside the pods

**Problem.** The inconsistency window is measured in seconds or less. Replicas run
on different nodes whose clocks are only loosely synchronised.

**Rejected.** Timestamps taken inside each pod, synchronised via NTP. The residual
offset between nodes can be of the same order as — or larger than — the window being
measured, and the error is not observable from the data itself.

**Chosen.** All authoritative timestamps are taken by the experiment runner, which
runs outside the cluster and therefore has a single clock. Each response carries its
config version and pod name; the runner records the receive time.

**Cost.** A replica is only observed when a request happens to reach it. With N
replicas and rate r, each replica is sampled r/N times per second, so the measured
window is a lower bound on the true one. This bound is derived and reported rather
than ignored. Pod-side `servedAt` is retained as a secondary cross-check.

---

## 2026-09-16 — The runner is external to the cluster

**Problem.** A load generator running inside the cluster competes for CPU and
network with the pods it measures.

**Rejected.** Running the generator as a pod, which would have been simpler to
deploy and would have avoided ingress setup.

**Chosen.** The runner is an ordinary Java process outside the cluster.

**Cost.** The measurement path now includes the hop into the cluster. This adds a
constant offset to latency figures, which is acceptable because the primary metric
is a *difference* between two timestamps on the same clock, not an absolute latency.

---

## 2026-09-16 — PodIdentity is deliberately not @RefreshScope

**Problem.** `startedAt` must identify when the *process* started, so that a replica
created during a configuration change can be distinguished from one that already
existed (the scale-out scenario).

**Rejected.** Making everything `@RefreshScope` for consistency.

**Chosen.** `PodIdentity` is a plain singleton. Only configuration-carrying beans
are refresh-scoped.

**Cost.** None for the measurement. It is also the first concrete example of a bean
that a refresh does not touch — material for the intra-JVM refresh discussion.

---

## 2026-09-16 — No subPath mounts in Way B

**Problem.** A `subPath` volume mount is never updated by the kubelet.

**Rejected.** Mounting individual keys with `subPath`, which produces cleaner paths
inside the container.

**Chosen.** Mount the whole ConfigMap directory and read it with
`spring.config.import: configtree:`.

**Cost.** Slightly less tidy file layout. In exchange, the reload path works at all.
The `subPath` behaviour is documented in the thesis as a production trap rather than
being silently avoided.

---

## 2026-10-11 — Local kind cluster with three worker nodes

**Problem.** Several effects under study only exist when replicas run on different
nodes: kubelet sync skew in Way B, per-node file updates, and how traffic is spread
across nodes. A single-node cluster cannot show them. A cloud cluster costs money
for every hour of a campaign of 180 runs.

**Rejected.** Single-node clusters (minikube, Docker Desktop Kubernetes), which hide
all per-node effects. A managed cloud cluster (AKS, EKS, GKE), which is realistic but
expensive, slower to reset and harder for others to reproduce.

**Chosen.** kind (Kubernetes in Docker), Kubernetes v1.37.0, one control-plane node
and three worker nodes, defined in `deploy/kind/kind-cluster.yaml`. The cluster can
be deleted and recreated in about a minute, so every run can start from the same
state. Anyone with Docker can reproduce the setup.

**Cost.** All nodes are containers on one machine. They share one kernel, one clock
and the same CPUs, and there is no real network latency between them. Clock offset
between nodes is therefore not reproduced. This is acceptable because the primary
metric is measured on the runner's single clock, but it limits how far absolute
numbers transfer to production clusters. Recorded as a threat to validity.

---

## 2026-10-11 — One replica per node, enforced by topology spread constraints

**Problem.** The scheduler may place several replicas on the same node. Per-node
effects then disappear from the data, and results differ between runs depending on
where pods happened to land.

**Rejected.** Leaving placement to the scheduler (non-deterministic placement).
Pod anti-affinity, which achieves the same result with a more verbose rule that
breaks when there are more replicas than nodes.

**Chosen.** `topologySpreadConstraints` on `kubernetes.io/hostname` with
`maxSkew: 1` and `whenUnsatisfiable: DoNotSchedule`, plus two settings that were
found to be necessary during the first manual tests:

- `nodeTaintsPolicy: Honor`. Without it, the constraint also counts the
  control-plane node, which can never run application pods because of its taint.
  During the first `rollout restart` the surge pod would have created a skew of
  2 against the empty control-plane node, so every worker was rejected. The pod
  stayed `Pending` with "3 node(s) didn't match pod topology spread constraints",
  and the rollout stalled indefinitely. Because of `maxUnavailable: 0` the old
  pods kept serving `v1`. There was no error in the responses and no alert, only
  stale configuration.
- `matchLabelKeys: [pod-template-hash]`. Without it, the constraint counts the pods
  of all versions, including old pods that are about to be stopped. During a
  rollout, new pods were therefore placed according to a distribution that was
  about to disappear. In one observed rollout, two new pods ended up on
  `thesis-worker3` and none on `thesis-worker2`. With the template hash as a key,
  only pods of the same revision are counted.

**Cost.** Two non-obvious settings that a reader must understand. When there are
more replicas than worker nodes, some nodes will host two replicas, which has to be
taken into account in the scale-out scenario. Both failure modes are documented in
the thesis as production traps: the first is an example of a configuration rollout
that fails safely but silently.

---

## 2026-10-11 — Service exposed as NodePort to the external runner

**Problem.** The runner lives outside the cluster (see "The runner is external to
the cluster") and needs a stable entry point. That entry point must spread requests
across all replicas, because a replica that receives no requests cannot be observed.

**Rejected.**
- `kubectl port-forward`: it forwards every request to one single pod chosen when
  the tunnel opens. The runner would only ever see one replica, and the
  inconsistency window would be invisible by construction. The tunnel also breaks
  when that pod is replaced, which happens in every Way A rollout.
- Ingress controller: an extra component with its own latency, buffering and
  connection handling, which adds a variable to the measurement.
- Service of type `LoadBalancer`: needs an external load balancer implementation,
  which kind does not provide without extra tools.

**Chosen.** A `NodePort` Service on port 30080, mapped to the host through
`extraPortMappings` in the kind configuration. kube-proxy spreads connections across
all ready pods behind the Service.

**Cost.** kube-proxy balances connections, not individual requests. A client that
reuses one HTTP connection (keep-alive) keeps reaching the same pod. The runner must
therefore open a new connection per request, or use a sufficiently large connection
pool, and must check that every pod received a fair share of requests in each run.
Opening a new connection per request also adds a constant connection set-up cost to
the measured latency.

---

## 2026-10-15 — Way B ConfigMap keys are full property names

**Problem.** `configtree:` turns every file in the mounted folder into a property
and uses the file name as the property name. With Way A's keys (`value`,
`configVersion`, `timeoutMs`), Spring would see properties called `value` and
`configVersion`, while the service reads `demo.value` and `demo.configVersion`.
Every replica would fall back to `UNSET` and `v0`.

**Rejected.** Sharing one ConfigMap between Way A and Way B. Way A's environment
variable names are translated by Spring's relaxed binding, but file names are not.
Nested folders (`demo/value`), which would also map to `demo.value` but need an
`items` list in the volume definition for every key.

**Chosen.** Way B has its own ConfigMap whose keys are the full property names:
`demo.value`, `demo.configVersion`, `demo.timeoutMs`.

**Cost.** The two approaches use different ConfigMap files with different keys for
the same configuration, and every scenario script has to patch the right key.

---

## 2026-10-17 — Way A activation automated with Stakater Reloader

**Problem.** A ConfigMap change never reaches running pods in Way A, because
environment variables are only read at process start. Without a trigger, the new
configuration waits until someone restarts the pods. In Way B a controller
triggers activation, so a manual restart in Way A would make the comparison unfair.

**Rejected.** A manual `kubectl rollout restart`, which measures human reaction
time. A Helm checksum annotation or a Kustomize `configMapGenerator` hash, which
are common but tie the trigger to the deployment tool instead of to the ConfigMap
change itself. A fourth variant "Way A plus Reloader" next to plain Way A.

**Chosen.** Stakater Reloader v1.4.22, with the annotation
`configmap.reloader.stakater.com/reload: "demo-config"` on the Way A Deployment.
Reloader watches the ConfigMap and starts a rolling restart on change. The
proposal defines Variant A as activation through a rolling restart, which this
still is, so no fourth variant is needed. The manifest is stored in the repository
instead of being applied from a URL.

**Cost.** One more component in the cluster, with a ClusterRole that can read and
update Deployments in every namespace. Its own reaction time becomes part of Way
A's measured window.

---

## 2026-10-17 — Way B activation by the Spring Cloud Kubernetes Configuration Watcher

**Problem.** In Way B the kubelet rewrites the mounted files, but Spring does not
notice. Something has to call `/actuator/refresh` on every replica.

**Rejected.** The reload feature inside the application (Spring Cloud Kubernetes
reload), which is deprecated. A script that calls the refresh endpoint after every
change, which is a manual trigger with extra steps. A self-written watcher, which
would be hard to justify against the official tool.

**Chosen.** The official Configuration Watcher, version 3.1.6, matching Spring
Cloud 2023.0.x. The ConfigMap carries the label `spring.cloud.kubernetes.config:
"true"` and the annotation `spring.cloud.kubernetes.configmap.apps:
"probe-service"`, because by default the Watcher refreshes the application named
after the ConfigMap. It finds the pods through the Service and calls
`/actuator/refresh` on each.

**Cost.** Every published Watcher image is built for amd64 only. On this arm64
machine it runs under emulation, loaded into kind with `kind load docker-image`.
It started in 13.4 s, but its timing may differ from a native run, and this has to
be stated next to every Way B result.

---

## 2026-10-17 — Watcher refreshDelay of 90 seconds, provisional

**Problem.** If the Watcher calls refresh before the kubelet has rewritten the
files on a node, that replica re-reads the old files and stays on the old version
until the next change. The kubelet updates mounted ConfigMaps on its own sync
cycle, which differs per node.

**Rejected.** A delay of zero, which produces stale replicas. Lowering the kubelet
`syncFrequency` in the kind configuration, which would make Way B faster but
depends on cluster settings that managed Kubernetes services usually do not expose.
It stays a candidate for a separate parameter variation.

**Chosen.** `SPRING_CLOUD_KUBERNETES_CONFIGURATION_WATCHER_REFRESHDELAY=90000` as a
careful first value. In the first test the Watcher detected the change at
20:44:03.7 and all replicas answered `v2` at 20:45:34.

**Cost.** The delay dominates Way B's activation time, which made Way B slower than
Way A in the first test. The value will be replaced by the slowest measured kubelet
delay plus a margin, and this entry updated.

---

## 2026-10-18 — Activation triggers run in a separate platform namespace

**Problem.** Every experiment run starts by deleting and recreating the `thesis`
namespace. The Watcher lived in `thesis`, so it started cold in every Way B run,
while Reloader in `default` stayed warm for Way A. Comparing a warm trigger with a
cold one would partly measure JVM startup instead of configuration propagation.

**Rejected.** Keeping the Watcher in `thesis` and waiting for it before each change,
which still compares a fresh JVM with a long-running process. Leaving Reloader in
`default` and the Watcher elsewhere, which treats the two triggers differently.

**Chosen.** Both triggers run in a namespace `platform` that is installed once and
never reset. The Watcher keeps watching only `thesis` through
`SPRING_CLOUD_KUBERNETES_RELOAD_NAMESPACES_0`. Its permissions moved from a Role to
a ClusterRole, so deleting `thesis` does not remove them.

**Cost.** The ClusterRole lets the Watcher read ConfigMaps and Secrets in every
namespace. That is acceptable on an isolated lab cluster. In production,
per-namespace Roles would be the least-privilege choice.

---

## 2026-10-24 — The runner opens a new connection for every request

**Problem.** kube-proxy chooses a pod when a connection is opened, not for each
request. The JDK HTTP client keeps connections open and reuses them.

**Rejected.** The client's default connection reuse. In a 10 s test at 30 req/s
without `Connection: close`, 300 of 301 requests reached the same pod and the third
replica was never observed.

**Chosen.** Every request carries `Connection: close` over HTTP/1.1, so the server
closes the connection after answering and the next request opens a new one. The
JDK only allows this header with the system property
`jdk.httpclient.allowRestrictedHeaders=connection`.

**Cost.** A TCP connection set-up for every request, which is included in the
latency figures. This is acceptable because the primary metric is the difference
between two receive times on the runner's clock.

---

## 2026-10-24 — Runner coverage check and a rate of 50 requests per second

**Problem.** Even with a new connection per request, the pod is chosen at random.
A pod can go unobserved for a while although the overall shares look even, and a
version switch on that pod is then detected late.

**Rejected.** Checking only the share of requests per pod. At 20 req/s the shares
were 29.5, 36.3 and 34.3 %, yet one pod went unobserved for 1 251 ms. A share
check would also reject valid Way A runs, where old pods disappear halfway through.

**Chosen.** For each pod, the longest gap between two of its observations must stay
below 1 000 ms, and every expected replica must be observed. Otherwise the run is
marked invalid and the runner exits with code 2. The default rate is 50 req/s,
where the longest gaps in two 30 s runs were 397 ms and 460 ms.

**Cost.** More load on the probe service, although p99 latency stayed below 15 ms.
The gap also sets the timing resolution of every window measurement: at 50 req/s a
version switch is detected about half a second late at most.

---

<!-- Template for new entries:

## YYYY-MM-DD — <decision in one line>

**Problem.**

**Rejected.**

**Chosen.**

**Cost.**

-->
