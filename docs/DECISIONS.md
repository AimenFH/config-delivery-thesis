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

<!-- Template for new entries:

## YYYY-MM-DD — <decision in one line>

**Problem.**

**Rejected.**

**Chosen.**

**Cost.**

-->
