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

**Update 2026-11-06.** I measured the kubelet delay with
`tools/kubelet-delay.sh`, which patches the ConfigMap and reads the mounted file
in every pod every 0.5 s until it changes. Ten changes on three pods gave 30
values between 9.2 s and 77.6 s, with a median of 31.8 s. The slowest pod per
change took between 29.4 s and 77.6 s. The data is in `results/kubelet-delay.txt`.
The slowest value plus a margin of 10 s is 88 s, which saves 2 s against 90 s and
leaves a smaller margin. The value stays at 90 s, now 12.4 s above the slowest
measurement instead of a guess. Thirty values do not prove an upper bound, so a
pod still on the old version at the end of a Way B run is the first thing to
check against this delay.

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

## 2026-10-25 — Raw data as NDJSON per run, with times relative to the run start

**Problem.** Every result in the thesis has to be traceable to the observations
behind it, and the analysis must be repeatable without running the experiment
again. Until stage 2 the runner printed a summary and discarded the observations.

**Rejected.** One JSON array per run, which becomes unreadable as a whole if the run
is interrupted before the closing bracket. CSV, which has no clean representation
for missing values and loses types. Absolute epoch timestamps in every line, which
Jackson writes as `1.792878637013595E12` and which every analysis would first have
to convert.

**Chosen.** Two files per run in `results/raw/`, named `<run id>.ndjson` and
`<run id>.meta.json`. The NDJSON file holds one observation per line. Its times are
milliseconds since the run started, on the runner's clock. The meta file holds the
absolute start time (`runStartedAt`, UTC), all settings and the coverage verdict.
Invalid runs are written as well, marked `"valid": false` with their reasons.
`results/raw/` is not committed.

**Cost.** Raw data lives only on the local machine and needs a separate backup. Two
files belong together and must not be separated. Absolute times have to be
reconstructed by adding `runStartedAtEpochMillis`.

---

## 2026-11-03 — The runner applies the change itself, after a warm-up

**Problem.** The window can only be measured against the moment of the change, and
that moment has to be on the runner's clock. The first seconds of every run also
show the runner's own JVM warming up, which must not overlap with the change.

**Rejected.** Applying the change by hand in a second terminal, whose time is not on
the runner's clock and which cannot be repeated exactly. Passing the command as one
string and splitting it on spaces, which breaks the quoted JSON of a `kubectl patch`.

**Chosen.** The runner probes for `--warmup` seconds (default 5) and then runs the
command given after a bare `--` as a child process, while the load continues. It
records `startedAt` and `finishedAt` of that process on its clock. A command with a
non-zero exit code makes the run invalid. Without `--` the run has no change, which
gives baseline runs.

**Cost.** `finishedAt` includes starting `kubectl` and its round trip to the API
server, so the moment of the change is only known to within
`finishedAt - startedAt`. The warm-up timer starts after the runner has set itself
up, so the change fires about 200 ms later than `--warmup` says. The recorded times
are the real ones.

---

## 2026-11-03 — Inconsistency window: last old answer minus first new answer

**Problem.** The thesis needs one definition of the window that works for both a
refresh in place (Way B) and a rolling restart that replaces pods (Way A), and that
says how precise each number is.

**Rejected.** Averaging per-pod switch times, which hides the overlap the thesis is
about. Measuring from the change to the last old answer, which mixes the delivery
delay with the inconsistency itself. Using `sentAt` for some boundaries and
`receivedAt` for others, which makes the numbers harder to explain for a gain
smaller than the p99 latency.

**Chosen.** All boundaries use `receivedAt`. The old version is the only version
seen before the change, the new version the first other version after it.

| Quantity | Definition |
|---|---|
| Window | `max(0, last old - first new)`: how long both versions were served |
| Window, upper bound | Window plus the unobserved time before the first new answer on its pod and after the last old answer on its pod |
| Propagation | `first new - change finishedAt` |
| Convergence | `max(last old, first new) - change finishedAt` |

A pod that has no earlier or later observation, because it is new or was removed,
counts with `--max-gap-ms` as its unobserved time. A run is invalid if the warm-up
shows more than one version, if the new version never appears, if a pod still
serving at the end of the run (observed within the last `--max-gap-ms`) answers with
the old version, or if a pod answers with the old version on a request sent after it
had already answered with the new one. All failed requests after the change are
counted, up to the end of the run. A first Way A run showed the last burst of
failures 2 s after the last old answer, outside any limit tied to convergence.

**Cost.** The measured window is a lower bound. The upper bound for new and removed
pods rests on the assumption that the coverage rule holds for them too.

---

## 2026-11-06 — Way A pods wait 5 seconds before shutting down

**Problem.** During a Way A rollout, about a third of the requests sent in the
0.7 s after an old pod was told to stop got no answer and timed out after 2 s:
41 and 33 failed requests in two runs. kube-proxy still routed new connections
to the pod while it was already shutting down.

**Rejected.** Measuring Way A without a shutdown delay as the main
configuration. The failures come from how pods are removed, not from how the
configuration is delivered, and a production deployment would not accept them.
Increasing the runner's timeout, which only hides the failures.

**Chosen.** A `preStop` sleep of 5 s on the Way A container, using the built-in
sleep action. Two runs with it had no failed requests. The runs without it stay
in the journal as a variant.

**Cost.** Each old pod now answers for 0.6 to 1.0 s after its replacement
appears, with the old version, so the window grows by up to about 1 s per
handover (14.9 s and 16.9 s against 13.9 s). Each pod also takes 5 s longer to
stop.

---

## 2026-11-07 — Coverage stays strict for the whole run; campaigns repeat invalid runs

**Problem.** In a smoke campaign, one new Way A pod stopped answering for 1.3 s at
72 s into the run, about 45 s after all pods had switched. Its answers were slow,
not lost. The coverage rule marked the run invalid although the gap came after
convergence and could not hide a version switch. With a fixed number of runs per
Way, every invalid run also leaves the campaign one result short.

**Rejected.** Checking coverage only up to convergence plus 1 s. It would have kept
that run, but it makes the rule depend on the detector's result and is harder to
explain. Accepting fewer valid runs than planned, which makes the sample size differ
between Ways by chance.

**Chosen.** The coverage rule stays as it is: a gap over `--max-gap-ms` anywhere in
the run makes it invalid. A campaign repeats runs until each Way has `repetitions`
valid runs, with at most `maxAttempts` attempts per Way (15 and 20 for scenario 1).
Invalid runs stay on disk and are counted in the summary.

**Cost.** Longer campaigns, by one run of up to about 3 minutes per rejected
attempt. If invalid runs cluster in one Way, the rejection rate itself is a
result and has to be reported.

---

## 2026-11-15: Fresh deployment at v1 before every run

**Problem.** Scenario 1 chained versions on a long-lived deployment. Way A pods
were replaced in every run, while Way B and Way C pods aged over the campaign, so
the approaches started each run from different states.

**Rejected.** Keeping the deployment and chaining versions (`v<epoch+n>`), which
is faster but lets pod age differ between approaches. Resetting only the
configuration source and keeping the pods, which still leaves old JVMs in Way B
and Way C.

**Chosen.** Before every run the Deployment is deleted, the source is reset to
v1 and the manifests are applied again. The change is always v1 to v2. The source
is reset before the workload is deployed, so no trigger fires on a fresh
Deployment.

**Cost.** About 2.7 minutes per run for reset, rollout and the 100 s settle time.

---

## 2026-11-15: Way C keeps reading from the Git repository on GitHub

**Problem.** Way C's Config Server reads from a remote repository on GitHub.
Every Way C number therefore contains internet latency that Way A and Way B do
not have.

**Rejected.** A Git server inside the cluster or a local file backend. Both
remove the latency, but a Config Server is normally run against a remote Git
host, so the result would describe a setup nobody uses.

**Chosen.** The remote stays, permanently. The network latency is part of Way
C's result and is recorded as a limitation. An approach descriptor may name an
optional `sourceCheck` command. The runner times it before every run and stores
the duration in the meta file. Way C uses `git ls-remote`. The core does not
know which approach uses the field. P3 is reported next to P1.

**Cost.** Way C's propagation and commit times vary with the connection and
with GitHub. Runs with a slow remote are visible in the data and are not
excluded.

---

## 2026-11-15: T0 is the end of commit, the trigger counts as delivery time

**Problem.** Scenario 1 counted propagation from the end of the whole change
command. In run r01 that command took 164 ms for Way A, 116 ms for Way B and
4 082 ms for Way C, because the Way C script also pushed to GitHub and called
`busrefresh`. Way C's trigger time was excluded, while the reaction times of
Reloader and the Watcher were included.

**Rejected.** `T0` at the start of the commit, which adds the commit duration
to every propagation time. `T0` at the end of the trigger, which keeps the
unfair treatment of scenario 1.

**Chosen.** The change is split into `commit` and `trigger`. `t_cs` is the
start of `commit`, `T0` is its end with exit code 0. Everything after `T0`,
whether a controller or a trigger call, is delivery time.

**Cost.** Way C's figures grow by the duration of the `busrefresh` call
compared with scenario 1. `T0` is only known to within the commit duration,
which is reported per run as P3.

---

## 2026-11-15: Fixed phase lengths and repetitions for every approach

**Problem.** `campaigns/scenario-1.json` set duration, settle and pause per
Way (90, 30 and 150 s of observation), and the warm-up was 5 s with no baseline.
Failed requests and latency were counted over windows of different length, and
performance impact had nothing to be compared with.

**Rejected.** Per-approach durations sized to each approach's propagation
time. An observation shorter than 150 s, which would cut off Way B at 90.2 s.

**Chosen.** Observation 150 s, baseline 60 s, warm-up 30 s, settle 100 s. S1
needs 20 valid runs per approach, S2 and S3 need 10. A campaign has at most 25
blocks. The values live in the protocol file only.

**Cost.** A run takes about 6.7 minutes for every approach, also for Way C,
which converges in seconds. The S1 campaign takes about 6.7 hours.

---

## 2026-11-15: Resource data from the kubelet endpoint, no metrics-server

**Problem.** Criterion 5 needs CPU and memory of the delivery components and
of the workload. The runner has no resource data so far.

**Rejected.** Installing metrics-server, which adds a component to the cluster
and only gives sampled rates. `kubectl top`, which depends on metrics-server.

**Chosen.** The kubelet endpoint `/metrics/resource`, read with
`kubectl get --raw /api/v1/nodes/<node>/proxy/metrics/resource` every 5 s. It
gives cumulative CPU seconds and the working set per container. If the endpoint
does not work on the kind cluster, criterion 5 is reported from the declared
facts only and the build stops for a new decision.

**Cost.** One `kubectl` call per node every 5 s during a run. The runner has to
parse the Prometheus text format itself.

---

## 2026-11-15: No pass or fail thresholds on the metrics

**Problem.** A threshold such as "at most 0.1 % failed requests" would turn the
profile of an approach into a verdict.

**Rejected.** Thresholds per metric, which have no source other than my own
judgement at this stage.

**Chosen.** Protocol version 1 reports the metrics and the outcome flags. It
computes no verdict and no total score. The decision model in the thesis weighs
the criteria.

**Cost.** The results do not say by themselves whether an approach is
acceptable.

---

## 2026-11-15: A coverage gap inside the observation sets a flag

**Problem.** The entry of 2026-11-07 made a run invalid when a pod went
unobserved for more than 1 000 ms anywhere in the run. A gap after the change
may be the approach making a pod unresponsive. Excluding such runs removes bad
behaviour from the data.

**Rejected.** Keeping the strict rule for the whole run. Dropping the coverage
rule completely, which would also accept a baseline in which a pod was not
observed.

**Chosen.** A coverage gap over 1 000 ms in the baseline `B`, or fewer than 3
pods seen in `B`, still makes the run invalid. A gap inside the observation
interval `H` sets the flag `LOW_RESOLUTION` and the run stays valid. Its timing
metrics get the wider upper bound, its availability and latency figures count in
full. This replaces that part of the entry of 2026-11-07. Repeating invalid runs
stays.

**Cost.** Timing metrics of flagged runs are less precise, and the flag has to
be reported per approach.

---

## 2026-11-15: Three scenarios on the same core

**Problem.** A valid change alone does not show what an approach does with a
bad configuration or when its delivery components are down.

**Rejected.** A separate runner per scenario. Scenario steps written into the
approach descriptors, which would let an approach shape its own test.

**Chosen.** A scenario is a file with timed steps that are the same for every
approach, executed by the core. S1 valid change: commit v2 at 0 s, 20 valid
runs. S2 invalid configuration: commit v2 with `timeoutMs` `abc` at 0 s, commit
v3 with `timeoutMs` 1000 at 120 s, 10 valid runs. S3 infrastructure outage:
`infraDown` at 0 s, commit v2 at 30 s, `deletePod` at 60 s, `infraUp` at 120 s,
10 valid runs. Offsets count from the end of the baseline, and the observation
lasts 150 s after the last step.

**Cost.** One S2 or S3 run takes about 8.7 minutes. All three campaigns need
about 15 hours. The 120 s before the fix in S2 are a fixed detection time and
are not a result. S3 takes all delivery components of an approach down at once.

---

## 2026-11-15: Readiness gate in probe-service behind CONFIG_GATE_ENABLED

**Problem.** An invalid `timeoutMs` changes nothing in the service today.
`/probe` answers 200 and the pod stays ready, so S2 would repeat the
propagation numbers of S1.

**Rejected.** A gate in CI, in an admission controller or in the Config Server,
which sits at a different place for each approach. S2 with the gate both on and
off, which doubles the campaign for a variant that shows nothing new.

**Chosen.** A health indicator with the rules of `/config-health`, part of the
readiness group only when `CONFIG_GATE_ENABLED` is `true`. The image becomes
`probe-service:0.2.0` and is used in all three scenarios. The core sets the
variable on the Deployment after `deploy`, for every approach alike. S1 and S3
run with the gate off, S2 runs with the gate on only.

**Cost.** probe-service changes once, so the scenario 1 data is pilot data and
is not compared with the new results. The results of S2 hold for a readiness
gate, other gate positions are not measured.

---

## 2026-11-15: A commit carries configVersion and timeoutMs in one atomic change

**Problem.** S2 needs a commit that changes the version and an invalid
`timeoutMs` together. If an approach wrote the two values in two steps, a pod
could run a mix of both.

**Rejected.** Two commit commands per change. A free-form payload, which would
let scenarios differ per approach.

**Chosen.** A payload is a `configVersion` and a `timeoutMs`. Every adapter
applies both in one atomic change: Way A and Way B patch both keys of their
ConfigMap in one `kubectl patch`, Way C edits both values and makes one Git
commit. Every payload has its own version, so the version in an answer tells
which payload the pod runs.

**Cost.** Every descriptor needs the placeholders `{version}` and
`{timeoutMs}`, and `tools/way-c-change.sh` is split into a commit script and a
trigger command.

---

<!-- Template for new entries:

## YYYY-MM-DD — <decision in one line>

**Problem.**

**Rejected.**

**Chosen.**

**Cost.**

-->
