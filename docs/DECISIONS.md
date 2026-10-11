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

<!-- Template for new entries:

## YYYY-MM-DD — <decision in one line>

**Problem.**

**Rejected.**

**Chosen.**

**Cost.**

-->
