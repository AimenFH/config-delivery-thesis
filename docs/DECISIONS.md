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

## 2026-11-15: The block order seed is in the campaign file, the bootstrap seed in the protocol file

**Problem.** The plan names two seeds. The block order "comes from a seed
stored in the campaign file", and the bootstrap interval uses 10 000 resamples
with "seed stored", without saying where.

**Rejected.** One seed for both, which would tie the statistics of a campaign
to its run order. The bootstrap seed in the campaign file, which would let two
campaigns of the same protocol version compute their intervals differently.

**Chosen.** `seed` in the campaign file is used only by `BlockPlan.draw`.
`bootstrapSeed` and `bootstrapResamples` are fields of the protocol file. The
campaign folder keeps a copy of both files in `campaign.json`, so
`--evaluate` reads the bootstrap seed from there. For every row of
`summary.csv` the stored seed is combined with the approach name and the column
name (`Statistics.seed`).

**Cost.** Changing the bootstrap seed means changing the protocol file, which
is a new protocol version.

---

## 2026-11-15: The baseline ends at the start of the first step of a scenario

**Problem.** The plan defines the baseline `B` as `[t_cs − 60 s, t_cs)`. In S3
the first step is `infraDown` at 0 s and the commit follows at 30 s. With `t_cs`
as the end of `B`, the first 30 s of the outage would count as baseline.

**Rejected.** `B` ending at `t_cs` in every scenario. A baseline at a fixed
90 s on the clock, which ignores that a step starts a few milliseconds late.

**Chosen.** `B` ends at the measured start time of the first step
(`RunRecord.reference()`), and `H` starts there. In S1 and S2 the first step is
the commit, so this is `t_cs`, as the plan says. In S3 it is the start of
`infraDown`. `t_cs` and `T0` still come from the first commit in every
scenario.

**Cost.** In S3 the baseline and `t_cs` are 30 s apart, which has to be said
wherever S3 numbers are explained.

---

## 2026-11-15: The scenario decides which metrics are reported

**Problem.** The plan lists the five criteria for S1, E1 to R1 for S2 and U1 to
U5 for S3, and says the metric functions take an interval so that S2 and S3 can
reuse them. It does not say whether an S2 or S3 run also reports P1 to O5.

**Rejected.** All 27 metrics in every scenario. P1 and C1 assume one change
from v1 to v2, which S2 (three versions) and S3 (outage first) do not have, so
the columns would hold numbers with another meaning.

**Chosen.** `Scenario.reportedMetrics()` reads the structure of the scenario
file. A scenario with an `infraDown` step reports U1 to U5. Otherwise a scenario
with invalid versions reports E1 to E4 and R1. Otherwise it reports P1 to O5.
`NOT_CONVERGED` is set only where P1 is reported, so an S2 or S3 run never
carries it. `FAILED_REQUESTS` looks at `H` where A1 is reported and at the whole
run after the baseline elsewhere. `REGRESSION` and `LOW_RESOLUTION` look at the
whole run after the baseline in every scenario.

**Cost.** `runs.csv` has different columns per scenario. The S2 and S3 smoke
results have no latency or resource columns, although the raw data for them is
on disk.

---

## 2026-11-15: Every block contains every approach

**Problem.** The plan says a block contains one run of every approach and that
blocks continue until every approach has its valid runs. If one approach needs
more blocks than the others, it is open whether the others still run in those
blocks.

**Rejected.** Leaving out approaches that already reached their number. The
later blocks would then no longer be complete blocks, and the approach that
needed them would run under other conditions than before.

**Chosen.** Every block runs every approach. The campaign checks the target
only before a new block starts. An approach can therefore end with more valid
runs than `validRuns`, and all of them enter the statistics.

**Cost.** Up to one extra run per approach and extra block, about 6.7
minutes each in S1 and 8.7 minutes in S2 and S3. The number of runs per approach can differ, so `n` is reported in
every row of `summary.csv`.

---

## 2026-11-15: A failed core step stops the campaign

**Problem.** The plan's failure table says what happens when `commit` or
`trigger` fail (the run is INVALID) and when reset or deploy fail (the campaign
stops). It does not name a failing `infraDown`, `infraUp` or `deletePod`.

**Rejected.** Marking such a run INVALID and repeating it. These steps are done
by the core with `kubectl`, the same way for every approach. If one fails, the
cluster is in an unknown state and the next run would start from it.

**Chosen.** `StepExecutor` throws `CampaignStopped` when a step other than
`commit` ends with an exit code that is not 0. The load is stopped, the
infrastructure is scaled back, and the campaign ends with exit code 1. A failed
`commit` or `trigger` stays a property of the run and makes it INVALID.

**Cost.** One failing `kubectl` call ends a campaign of several hours. The run
in which the step failed is not written to disk, because it has no complete
timeline.

---

## 2026-11-15: Rollouts and the restore wait at most steadyTimeoutSeconds

**Problem.** The plan gives one time limit, 120 s, for the steady check in step
5. It gives none for the rollout after deploy, for the pods going away, or for
the infrastructure coming back after S3.

**Rejected.** `kubectl` defaults, which can wait for a very long time on a
rollout that never finishes. A second constant in the protocol file, which the
plan does not have.

**Chosen.** `kubectl rollout status` and `kubectl wait --for=delete` are called
with `--timeout=120s`, taken from `steadyTimeoutSeconds`. This covers the
rollout after deploy, the rollout after the gate variable is set, the pods of
the deleted Deployment, and the rollout of every scaled infrastructure
Deployment in the restore. A timeout stops the campaign.

**Cost.** The name of the constant now covers more than the steady check.
`kubectl delete deployment --wait=true` itself has no timeout.

---

## 2026-11-15: The delivery components of every approach must be up before every run

**Problem.** The plan says all delivery components of all approaches keep
running in `platform` during every run, so the background load is the same for
everyone. `InvariantCheck` as described reads only the workload Deployment and
the Service. After a failed S3 restore a component could stay at 0 replicas
without anyone noticing.

**Rejected.** Checking only the components of the approach that runs next.
Trusting the restore of the run before.

**Chosen.** Together with the invariants, the campaign reads every Deployment
named under `infrastructure` in any descriptor. Each needs at least 1 desired
replica and all desired replicas ready. Otherwise the campaign stops before the
load starts.

**Cost.** A few more `kubectl get` calls per run. A component that is slow to
become ready stops the campaign instead of delaying it.

---

## 2026-11-15: Instrument limits and fixed workload fields live in the protocol file

**Problem.** The plan's constants table does not hold every number the code
needs: the schedule lag limit of 100 ms, the 1 % limit for unsent requests, the
15 s resource sampling gap, the steady state numbers, and the fixed fields that
`InvariantCheck` compares. The table also names valid runs and blocks, which
differ between a smoke campaign and a full one.

**Rejected.** Constants in the Java code, which a reader of the protocol file
would not see. Valid runs in the protocol file, which would need a second
protocol for a smoke campaign.

**Chosen.** The protocol file holds the instrument limits
(`scheduleLagLimitMillis`, `unsentLimitRatio`, `resourceGapLimitSeconds`,
`coverageLimitMillis`, the four `steady*` fields, `retriesPerBlock`) and a
`workload` block with image, container name, requests and limits, readiness
probe, topology spread and Service fields. `validRuns` and `maxBlocks` are in
the campaign file. The protocol's `maxBlocks` of 25 is the upper limit, and a
campaign file that asks for more is rejected.

**Cost.** Two files have to be read to know a campaign. Both are copied into
`campaign.json` of the campaign folder.

---

## 2026-11-15: "Still served at the end", the length of A2 and short buckets

**Problem.** Three formulas of the plan leave a detail open. "v1 is still
served at the end of `H`" does not say how long the end is. A2 is a run of
failed requests "as a time span". A3 and F3 use buckets of 2 s and 1 s, and an
interval need not be a multiple of them.

**Rejected.** The single last answer as "the end", which depends on one random
request. Receive times for the span of A2, which would add the 2 s timeout to
every outage. A shorter last bucket, in which fewer pods answer only because
fewer requests fall into it.

**Chosen.** A version is still served when a successful answer with it was
intended in the last `coverageLimitMillis` (1 000 ms) of the interval. O4 uses
the same last second for "pods answering at the end of `H`". A2 is the number
of consecutive failed scheduled requests times the period of 20 ms. Buckets
start at the start of the interval, and a last bucket shorter than the bucket
length is left out.

**Cost.** An outage of n failed requests is reported as n times 20 ms, which
is a lower bound of its real length. Up to 2 s at the end of an interval are
not covered by A3.

---

## 2026-11-15: Raw data is saved before the campaign stops

**Problem.** Two errors can happen after a run has been measured completely:
the evaluator throws, or the infrastructure does not come back after an S3 run.
The plan says to stop and "keep what exists", but at that moment the run exists
only in memory.

**Rejected.** Stopping at once, which loses up to 360 s of measured data that
could show what went wrong.

**Chosen.** If the evaluation throws, the run is saved with the verdict
INVALID and the reason "the evaluation failed", then the campaign stops with
exit code 1. If the restore fails after the timeline was completed, the run is
evaluated and saved first, then the campaign stops. `runs.csv` and
`summary.csv` are written in both cases.

**Cost.** A folder can hold a run whose INVALID verdict comes from an error in
the evaluator. The reason says so, and `--evaluate` judges the run again.

---

## 2026-11-15: Smoke results are copied to results/aggregated

**Problem.** `results/raw/` is not committed, and one smoke campaign is 14 to
24 MB on disk. Without any committed file the numbers in the journal cannot be
checked from the repository.

**Rejected.** Committing the raw folders. Committing nothing.

**Chosen.** For each smoke campaign the small files are copied by hand to
`results/aggregated/<campaign id>/`: `runs.csv`, `summary.csv`,
`campaign.json`, `fingerprint.json`, the `approaches/` folder and the
`<run id>.meta.json` files. The observations, the resource samples and the
cluster files stay in `results/raw/` only. The runner does not make this copy.

**Cost.** The committed files cannot be re-evaluated, because `--evaluate`
needs the raw files beside them. The copy can be forgotten after a campaign.

---

## 2026-11-19: The unconfigured version is accepted after the baseline and sets a flag

**Problem.** probe-service answers `v0` when no external configuration reaches
it, because `demo.configVersion` defaults to `v0` in its `application.yml`. The
failure table of the plan makes a run INVALID when an answer carries a version
that no payload declares. A pod that starts without configuration during a run
is behaviour of the approach, and the rule would exclude such a run and repeat
it.

**Rejected.** Keeping the rule. Adding `v0` to the declared versions of every
scenario, which would also accept `v0` in the baseline and give it a place in
the version order that C3 uses. Changing probe-service so that it cannot start
without configuration, which would hide the case for every approach.

**Chosen.** The protocol file names the version: `unconfiguredVersion` is `v0`.
An answer with this version after the baseline leaves the run VALID and sets
the flag `UNCONFIGURED`. In the baseline `B` it still makes the run INVALID.
Every other version that no payload declares makes the run INVALID as before. A
campaign folder saved before this change has no `unconfiguredVersion` in its
`campaign.json` and is evaluated with the old rule.

**Cost.** P1, C1 and U4 follow v1 and v2 only. A pod that stays on `v0` does
not delay convergence in these metrics, so a run with the flag has to be read
together with U2 and the raw data. C3 does not count a pod that falls back from
v1 or v2 to `v0`. No approach produced `v0` in the smoke campaigns, so the rule
has only run in unit tests.

---

## 2026-11-19: P2 and C1 are censored when the change has not finished at the end of H

**Problem.** When v2 never appeared, P1 was censored at the length of `H` and
P2 and C1 were empty. `summary.csv` leaves empty values out, so the same run
counted for P1 and was missing for P2 and C1. When v2 appeared and v1 was still
served at the end of `H`, C1 was written as an exact number although the window
was still open.

**Rejected.** Leaving both empty. Writing C1 as 0 when v2 never appeared: no
mixed state was observed in such a run, and a 0 would rank a run without
delivery as the most consistent one.

**Chosen.** P2 follows the rule of P1. When v2 never appeared, P2 is the length
of `H` and censored. C1 is censored in every run in which P1 is censored. Its
value is the window seen so far, `t_last_old − t_first_new`, or 0 when v2 never
appeared, which `runs.csv` shows as `>0`. `C1_max` is empty for a censored C1,
because an open window has no upper bound. A censored value enters the
statistics as larger than every number, as for P1.

**Cost.** A run that never delivers counts as the worst case for C1, although
its replicas never disagreed. The median of C1 is censored when half of the
runs of an approach are. This changes the definitions of P2 and C1 in the plan
of 2026-11-15.

---

## 2026-11-19: A failed trigger during an outage sets a flag, and a trigger gets 15 seconds

**Problem.** In S3 the trigger of Way C is `kubectl exec` into the Config
Server pod, which the scenario has scaled to 0. The command failed after 60.0 s
with exit code 1. The failure table makes a run with a failed trigger INVALID,
so all three Way C attempts of the S3 smoke campaign were INVALID and the
campaign ended with exit code 2. The step executor waits for a step to finish,
so the 60 s also moved `deletePod` from 150 s to about 182 s, for Way C only.

**Rejected.** Keeping the rule, which leaves S3 without a result for Way C.
Removing the trigger from Way C in S3, which would measure a different approach
in one scenario. Adding `--pod-running-timeout` to the trigger command of
Way C, which puts a duration into a descriptor, where the plan allows none.
Running the trigger in the background, which lets it overlap with `deletePod`
and `infraUp`.

**Chosen.** The verdict rule and the trigger call both change. A failed trigger
makes the run INVALID only while the delivery components are up. If the commit
lies between `infraDown` and `infraUp`, the run stays VALID and gets the flag
`TRIGGER_FAILED`. A failed `commit` makes the run INVALID in every case. Every
trigger gets at most `triggerTimeoutSeconds`, 15 s in the protocol file. After
that the runner stops the command and records exit code 124. The `busrefresh`
calls of the S1 smoke campaign took about 2.4 s. The slowest commit of that
campaign, 5.1 s, plus 15 s still ends before the next step of S3, which is 30 s
after the commit.

**Cost.** A trigger that fails during the outage for a reason of the instrument
is counted as behaviour of the approach. A trigger slower than 15 s outside an
outage makes the run INVALID. The three Way C runs of 2026-11-15 evaluate as
VALID now. They ran without the timeout, so their `deletePod` is 32 to 35 s
late and they cannot be compared with new runs. On the cluster the timeout has
run once, in the Way C run of the S3 smoke campaign of 2026-11-19.

---

## 2026-11-20: Loki runs in its own namespace on the control-plane node

**Problem.** To find the cause of the Way B deadlock I had to switch on debug
logging by hand and save the Watcher's log to a file. The pods of a run are
deleted before the next run, and their logs go with them. I want the logs of
every run kept and searchable, without changing what the runner measures.

**Rejected.** Putting Loki into `platform`, which holds the delivery components
that the approaches are compared on. Installing it with a Helm chart: Helm is
not installed, the repository uses plain manifests, and a chart hides the
objects I want to understand. The distributed mode of Loki, which needs object
storage and several pods. Starting Loki only for a diagnosis, which keeps no
logs of campaign runs and makes the cluster of a diagnosis differ from the
cluster of a campaign.

**Chosen.** A namespace `monitoring` with the manifests in `deploy/monitoring/`.
Loki 3.7.10 runs as one process in a StatefulSet with a 5 Gi volume and stores
on the local filesystem, without authentication and with usage reporting off. A
`nodeSelector` and a toleration place it on the control-plane node, away from
the three workers that run the probe pods. It stays up during campaigns.

**Cost.** The control-plane node also runs the API server and etcd, which every
approach uses, so Loki's load is not free of effect on the measurement. I have
not compared a smoke campaign with and without it yet. The logs live on one
volume of one kind node and are gone when the cluster is deleted. No retention
is set, so the volume can fill up.

---

## 2026-11-20: Alloy reads pod logs through the Kubernetes API, with rights in two namespaces

**Problem.** Loki stores what it is sent and collects nothing itself. Something
has to read the logs of the pods in `thesis` and `platform`, add labels that
say where a line comes from, and send it on. The pods of a run are on the three
workers, which should carry as little extra load as possible.

**Rejected.** Promtail, which Grafana Labs replaced with Alloy. A DaemonSet
that reads the log files on every node, which puts a collector on each worker
and needs a mount of the node's file system. A ClusterRole, which would let the
collector read the logs of every namespace.

**Chosen.** One Alloy pod, version 1.22.0, on the control-plane node. Its
component `loki.source.kubernetes` reads the logs through the API server. Two
Roles give its ServiceAccount `get`, `list` and `watch` on pods and their logs
in `thesis` and in `platform`, and nowhere else. Every line gets the labels
`namespace`, `app`, `pod`, `container` and `node`.

**Cost.** Every log line passes through the API server and the kubelet of its
node, which the approaches use too. The read positions are kept in an
`emptyDir`. When the Alloy pod is replaced it reads the logs of the running
containers from their start and sends them again. A container that ends before
Alloy has seen it leaves no log in Loki. The label `pod` makes new streams in
every run, because every run has new pod names.

---

## 2026-11-20: Grafana is built from files and has no login

**Problem.** I want to read logs and results in a browser. A data source or a
dashboard that is clicked together in the browser lives in the database of the
Grafana pod and is gone with the pod.

**Rejected.** An admin password in a Secret, which is one more secret to keep
for a page that only a port-forward can reach. A volume for Grafana's database,
which would be a second copy of the dashboards that can differ from the files
in git. A NodePort Service: the kind cluster maps only port 30080 to the
machine, and another port needs a new cluster.

**Chosen.** Grafana 13.3.1 runs as one pod on the control-plane node. The Loki
data source and the two dashboards come from ConfigMaps in
`deploy/monitoring/grafana.yaml` and `deploy/monitoring/grafana-dashboards.yaml`.
Anonymous access has the role Admin and the login form is off. Grafana is
reached with `kubectl port-forward`. Usage reporting and update checks are off.

**Cost.** Everyone who can reach the port is an administrator. That is
acceptable only as long as no Service exposes Grafana outside the cluster. A
change made in the browser is lost at the next restart, so a dashboard has to
be changed in its file. The dashboards are JSON inside a YAML file, which is
hard to read and to compare between versions.

---

## 2026-11-20: The results of a campaign are loaded into Loki after it has ended

**Problem.** The raw results are NDJSON files with one line per request. I
want to see which version each pod answered over time, next to the pod logs of
the same minutes, without a second data store.

**Rejected.** Prometheus, which would have to collect in the cluster during a
run, while the results are complete in files afterwards. Letting the runner
send to Loki during a run, which adds work to the measuring process and makes a
run depend on Loki. The labels `pod` and `configVersion` on every request,
which multiplies the streams of a run. The two values stay in the line and are
read with `| json` in the query.

**Chosen.** The runner has the mode `--loki <campaign dir>` in the class
`LokiExport`. `tools/loki-load.sh` opens a port-forward to Loki, starts that
mode and closes the port-forward. A run becomes three streams with the label
`kind`: `request`, `step` and `run`. The other labels are `job="runner"`,
`campaign`, `scenario`, `approach` and `run_id`. Every line carries the wall
clock time of its event, the start of the run plus the offset the runner
measured. Before a run is loaded the exporter asks Loki for it and skips it if
it is there. Loki needs three settings for this: `reject_old_samples: false`,
higher ingestion limits, and `query_store_max_look_back_period: -1` in the
ingester.

**Cost.** Loading is a step by hand after a campaign. The question to Loki is
the only protection against loading a run twice, and Loki does store a run
twice: that happened once, before the third setting was in. The results exist
in two places. The files stay the source of `runs.csv` and `summary.csv`, and
Loki is only for looking. The times of the results come from the clock of the
machine and the times of the pod logs from the nodes. In kind both are the same
machine.

---

## 2026-11-21: Way D delivers through Argo CD and a Helm chart in Git

**Problem.** The three approaches so far change a ConfigMap or call a refresh
endpoint. Many teams do neither by hand. They commit to Git and a GitOps
controller makes the cluster follow. I want a fourth approach that is built the
way such a team would build it, so that its numbers say something about that
practice.

**Rejected.** Plain manifests in Git without Helm, which works. I chose Helm
because I want the setup close to what a team would run. A ConfigMap in Git with
Reloader for the
restart, which is Way A with another source and would measure Reloader a second
time. It stays the fallback in case the Helm design breaks a fixed field. The
full Argo CD install with API server, web page, Dex and notifications, which
adds four components that no step of a run uses. A namespace `argocd`, because
the delivery components of every approach live in `platform`.

**Chosen.** Argo CD 3.5.8 with three components in `platform`: application
controller, repo server and Redis. `deploy/platform/argocd/kustomization.yaml`
takes them from the official repository and is applied with
`kubectl apply --server-side -k`. The Helm chart of probe-service is on the
branch `way-d` of the configuration repository, in the folder `probe-service`.
A copy is in `deploy/way-d-gitops/chart/`. The chart renders the ConfigMap
`demo-config-gitops`, the Deployment and the Service. The Deployment is the one
of Way A with the same fixed fields, without the Reloader annotation, and with
the annotation `checksum/config` on the pod template. That annotation is the
hash of the rendered ConfigMap, so a new value in `values.yaml` changes the pod
template and Kubernetes starts a rolling restart. A configuration change is one
commit that edits `values.yaml`.

**Cost.** The application controller runs as a Deployment. The official install
uses a StatefulSet. I changed it because the runner scales and reads
Deployments only. The chart exists in two places, the configuration repository
and this repository, and nothing checks that they are the same. Argo CD has
cluster-wide read rights. After the sync the delivery is a rolling restart, so
Way D shares the inconsistency window of Way A and differs only in the time
before the restart starts and in the components it needs. Every Way D number
contains a round trip to GitHub, as for Way C. No pilot runs were made for a
tuning rule. The values are the defaults of Argo CD and the rollout settings of
Way A.

---

## 2026-11-21: Argo CD syncs without self-heal and reads its own branch

**Problem.** There is one test system for all approaches. Argo CD keeps running
while Way A, B and C are measured, and their runs delete and create the same
Deployment `probe-service` that Argo CD manages for Way D. The runner also sets
`CONFIG_GATE_ENABLED` on that Deployment with `kubectl set env`, for every
approach in the same way. Argo CD must not undo either.

**Rejected.** `selfHeal: true`, which makes Argo CD correct every difference
it sees. It would put the Deployment of the chart back during a run of another
approach. Removing
the Application after every Way D run, which needs a step in the core that only
one approach uses. A second Deployment name for Way D, because the protocol
fixes the name for all approaches. The branch `main` for the chart, because
every Way C commit would then be a new revision for Argo CD.

**Chosen.** The Application has `automated` sync with `selfHeal: false` and
`prune: false`. Argo CD then acts once per new commit and leaves later changes
in the cluster alone. It reads the branch `way-d`, which only Way D commits to.
The AppProject `thesis` allows one repository, the namespace `thesis` and the
kinds ConfigMap, Service and Deployment. I checked both conditions by hand on
2026-11-21. After a sync of a new commit the Deployment still had
`CONFIG_GATE_ENABLED=false`, and Argo CD showed `Synced` with the variable on.
With Way A deployed, Argo CD showed `OutOfSync` for four minutes and after one
forced refresh, started no sync, and the Way A pods were the same at the end.

**Cost.** Correcting drift is one of the reasons to use GitOps, and it is off
here, so the experiment says nothing about it. Argo CD shows `OutOfSync`
whenever another approach is deployed, which looks like an error and is none.
Argo CD compares the cluster with Git every few minutes during every run of
every approach. The period is the default of Argo CD, I did not set it. This
load is the same for all, but the numbers of the campaigns before 2026-11-21
were measured without it. The three Argo CD containers have no CPU or memory
requests. `kubectl apply` of Way A
now answers `service/probe-service configured`. Type, ports and selector are
unchanged, and I have not confirmed which field differs.

---

## 2026-11-21: Way D's trigger is a refresh annotation and its reset creates the Application again

**Problem.** Argo CD looks at Git every few minutes. In practice a
webhook from GitHub tells it about a commit at once. GitHub cannot reach a kind
cluster on my machine, and the install has no API server that could receive a
webhook. The second problem is the start of a run. The runner deletes the
Deployment and expects the approach to bring three replicas at v1 back. Without
self-heal Argo CD does not create a deleted Deployment again when the commit is
the same as before.

**Rejected.** For the trigger: waiting for the poll, which would measure a
timer of some minutes that a team with a webhook does not have. A
reconciliation period of a few seconds, which no one runs and which loads the
cluster. The Argo CD API server behind a tunnel, a new component and a service
on the internet only for this. For the reset: writing a sync operation into the
Application, which can meet a sync that Argo CD has just started by itself.

**Chosen.** The `trigger` of the descriptor is
`kubectl -n platform annotate application way-d argocd.argoproj.io/refresh=normal --overwrite`.
It does what the webhook does: it tells the controller to look at Git now.
`operatorActionsPerChange` is 2, commit and trigger, as for Way C. The `reset`
is `tools/way-d-reset.sh`. It pushes v1 with `tools/way-d-commit.sh`, deletes
the Application, applies `deploy/way-d-gitops/application.yaml` again and waits
until the Application is `Synced` at the pushed commit, the Deployment exists
and the ConfigMap holds v1. A new Application has no sync history, so Argo CD
syncs it once. The Application has no finalizer, so deleting it leaves the
objects in `thesis`. The `manifests` entry `deploy/way-d-gitops` applies the
same Application file, which changes nothing after the reset.

**Cost.** With a webhook a change is one operator action. Here it counts two.
The trigger writes to the Kubernetes API and succeeds while Argo CD is scaled
to 0, so in S3 I expect no `TRIGGER_FAILED` and a delivery after `infraUp`.
That is not tested. The reset holds logic that the core has for no other
approach, and it needs GitHub, so a run cannot start when GitHub is not
reachable. The sync history of the Application is lost at every run. The first
creation of the pods is done by Argo CD inside `reset`, not by the `deploy`
step of the runner. Way D was added to `protocol/protocol-v1.json` while
`protocolVersion` is still 1. Whether the number changes is open, see journal
entries 08 and 10.

---

## 2026-11-21: S4 commits two valid changes 10 seconds apart

**Problem.** S1 changes the configuration once and waits. In operation changes
also follow each other closely: a correction right after a change, or a
generator that writes a new version every few minutes. Public incident reports
show what can go wrong then. In the
[Cloudflare outage of 18 November 2025](https://blog.cloudflare.com/18-november-2025-outage/)
a file that was generated every five minutes was sometimes good and sometimes
bad, and the network failed and recovered in turn. In the AWS DynamoDB outage
of October 2025 two workers applied DNS plans of different age at the same
time and the endpoint ended with an empty record. I know that one from the
[InfoQ summary](https://www.infoq.com/news/2025/11/aws-dynamodb-outage-postmortem)
and have not read the [AWS report](https://aws.amazon.com/message/101925)
itself. No scenario so far shows whether an approach ends on the last version
when a second change arrives while the first is still on its way, or which
versions the replicas serve in between.

**Rejected.** A gap shorter than the fastest delivery, so that the second
commit lands inside the first delivery for every approach. Way C converged in
a median of 1.6 s in the pilot runs and its commit alone took about 4 s, so no
fixed gap does that. A gap per approach, which would let an approach shape its
own test. Three or more commits, which make a run longer before one pair has
been looked at. P1 and C1 of S1, which compare v1 with one new version and
have no place for a third. A new protocol version: S4 adds a scenario file and
changes no constant, step or metric of S1 to S3, and the plan names further
scenario files on the same core as the way to cover more cases.

**Chosen.** `scenarios/s4-rapid-changes.json`: commit v2 at 0 s, commit v3 at
10 s, both with `timeoutMs` 1000, gate off, load for 150 s after the second
commit, 10 valid runs. The gap is the same operator behaviour for every
approach. Whether the second commit meets a delivery in progress is a property
of the approach and is read from the data. 10 s lies inside Way A's rollout
(median 28.2 s in the pilot runs) and inside Way B's 90 s `refreshDelay`. A
scenario with more than one commit, no invalid version and no `infraDown`
reports seven metrics over the time after the baseline:

| Id | Metric | Definition | Unit |
|---|---|---|---|
| Q1, primary | Final convergence time | From `T0` of the last commit to the last answer that does not carry the last version. Censored with the flag `NOT_CONVERGED` if such an answer arrives in the last 10 s of the run | ms |
| Q2, primary | Mixed-version time | Total time in which more than one version was served, computed as U3 | ms |
| Q3 | Most versions at once | Highest number of versions served at the same time | count |
| Q4 | Intermediate version exposure | Distinct pods that answered with v2, and the number of those answers | count |
| Q5 | Pod regressions | C3 over the three versions | count |
| Q6 | Failed-request ratio | A1 over the whole run after the baseline | ratio |
| Q7 | Commit gap | `T0` of the last commit minus `T0` of the first | ms |

Q7 is there because the steps run one after the other. A commit and trigger
that take longer than 10 s push the second commit back, and Q7 shows by how
much. The code is in `SuccessiveChanges`, and Q2 and Q3 share one sweep in
`InfrastructureOutage`.

**Cost.** One run takes about 6.8 minutes (250 s measured). Four approaches
and 10 blocks need about 4.5 hours. For Way C the two changes most likely
arrive one after the other, so S4 may say little about it that two S1 runs do
not. Failed requests do not delay Q1 as they delay R1. They are counted in Q6
only. One gap is measured, and another gap may give another order of the
approaches. The metric ids grow from 27 to 34, and `NOT_CONVERGED` has a
second meaning in S4. The scenario has unit tests and has not run on the
cluster yet.

---

## 2026-11-21: Each approach is tested as teams run it, with nothing built for it

**Problem.** The incident reports of 2025 end with the same fixes: staged
rollout with health checks, and automatic rollback. None of the four
approaches rolls back by itself. Ways A and D stage a change, because they
restart pods one at a time (`maxSurge: 1`, `maxUnavailable: 0`) and the
readiness gate stops the rollout at the first bad pod. Ways B and C refresh
all replicas within seconds. I had to decide whether every approach gets
staging and rollback before I compare them, and what a fair comparison is if
they do not.

**Rejected.** A script for Ways B and C that refreshes one pod, checks it and
then continues or reverts. The numbers would describe my script, which no team
runs. Argo Rollouts for Way D only, which would let D win S2 through a tool
the others did not get. Argo Rollouts for all four: it replaces the Deployment
of Ways A and D and offers nothing for a refresh in place, so Ways B and C
would still need code of my own. A rollback command per approach in the
descriptor, which would let each approach take its own shortest path and make
R1 hard to compare.

**Chosen.** One rule decides what belongs in the test. An approach is measured
in the configuration that teams commonly run, tuned by the same rule as
before. What the mechanism does by itself is a result. What I would have to
build for it is not part of the test. The staging of Ways A and D counts,
because it comes from the rolling update, and its absence in Ways B and C is a
finding. Rollback is the same for every approach: a commit of good values at
the source, issued by the core at the same offset. In S2 that is the v3 commit
after 120 s. It stands for a `git revert` by an operator, and R1 is the
rollback time. What each approach would need for an automatic rollback is
discussed approach by approach in the thesis text and is not measured. If Argo
Rollouts is added later, it is a fifth approach with its own descriptor and
its own control campaign.

**Cost.** The thesis has no measured result for automatic rollback, which is
what the large operators built after their outages. The 120 s of detection
time stay a protocol constant, so the gain from faster detection is not shown.
"Commonly run" rests on tool documentation and on adoption figures such as the
[CNCF end user survey of 2025](https://cncf.io/announcements/2025/07/24/cncf-end-user-survey-finds-argo-cd-as-majority-adopted-gitops-solution-for-kubernetes).
I found no data on how teams roll back. The pods of Ways A and D read
ConfigMaps with fixed names (`demo-config`, `demo-config-gitops`), so going
back to the old pods would not bring the old values back. That point belongs
in the text on rollback. Three inequalities remain and are only stated: Ways C
and D read from GitHub, the Watcher image of Way B runs emulated, and the 10 s
gap of S4 falls inside the delivery of some approaches and after it for
others.

---

## 2026-11-21: S5 deletes a ready pod while the invalid configuration waits at the source

**Problem.** In S2 a rolling restart stops at the first pod that does not
become ready, and the old pods keep serving. In the smoke run Way A exposed 0
replicas and kept 3 serving. That looks safe, but the invalid value is still
in the source. Incident reports describe the same state. At
[Azure Front Door on 29 October 2025](https://techcommunity.microsoft.com/blog/azurenetworkingblog/-/4479416)
the bad configuration overwrote the last known good snapshot, and loading the
configuration again took about 4.5 hours. In the
[Cloudflare outage of 20 February 2026](https://blog.cloudflare.com/cloudflare-outage-february-20-2026/)
there was no snapshot to go back to, and about 300 prefixes were restored by
hand. The pods of Ways A and D read ConfigMaps with fixed names, so a pod that
starts in this state reads the invalid value. S2 has no pod start between the
bad commit and the fix and cannot show this.

**Rejected.** `deletePod` as in S3. It deletes the first pod by name, ready or
not. During a stopped rollout of Way A or Way D there are four pods, three old
and ready and one new and unready, and the name order of two ReplicaSets
follows their hashes. The step would sometimes delete the pod that serves
nothing. Changing `deletePod` itself: in S3 all pods are ready, so its runs
would not change, but the step belongs to the frozen S3. A scale-out step in
place of a deletion, which needs a replica count other than the fixed 3. A new
set of metrics: E1 to E4 and R1 already hold what the scenario asks, and with
the same metrics S5 can be compared with S2 directly.

**Chosen.** `scenarios/s5-bad-config-restart.json` has the steps of S2 with
one step between them: commit v2 with `timeoutMs` `abc` at 0 s,
`deleteReadyPod` at 60 s, commit v3 with `timeoutMs` 1000 at 120 s, gate on,
10 valid runs. The new step `deleteReadyPod` deletes the first pod by name
among the pods that are ready and not being deleted. If no pod is ready, it
deletes the first by name, as `deletePod` does. The rule is the same for every
approach. S5 reports the metrics of S2. The number to read is E4, the lowest
number of serving replicas, next to the E4 of S2.

**Cost.** One run takes about 8.7 minutes. Four approaches and 10 blocks need
about 5.8 hours. I expect a difference to S2 only for Ways A and D: 2 serving
replicas where S2 had 3. In Ways B and C the replicas become unready by the
refresh anyway, so S5 will mostly repeat S2 for them. That is an expectation
and not a result. The core has a fifth step. The readiness of a pod is read
from the Kubernetes API when the step starts and can have changed by the time
the pod is deleted. E4 counts answering pods in buckets of 2 s, so a short dip
can be missed. The scenario has unit tests and has not run on the cluster yet.

---

## 2026-11-23: probe-service writes one row to PostgreSQL per request

**Problem.** My professor asked for a database. On 2026-11-22 he confirmed by
email what he means: the service under test accesses the database while it
processes requests ("Der zu testende Dienst greift bei der Verarbeitung von
Anfragen auf die Datenbank zu"). He did not say which database, and not
whether the service reads or writes. Until now `/probe` touched nothing
outside the pod.

**Rejected.** The database as a configuration source. That would be a fifth
delivery approach, and the email describes a database that the service uses
while it answers requests. Keycloak, which I considered and
turned down; reason not recorded. An in-memory database inside each pod: the
tests and the cluster use the PostgreSQL 17.2 image only, and no in-memory
database appears in the code; reason not recorded. Reads per request: the
code only writes, and `findByPod` is used by the tests; reason not recorded.

**Chosen.** One PostgreSQL 17.2 in its own namespace `data`, as a StatefulSet
with one replica and a 1 Gi volume, on the control-plane node like Loki
(`deploy/data/`). Flyway creates the table `probe_request` (`id`,
`received_at`, `pod`, `config_version`) when the service starts. Every
`GET /probe` inserts one row with the pod name and the configuration version
of the answer, inside a transaction that first sets the PostgreSQL
`statement_timeout` to the effective `demo.timeoutMs`. That value is
`timeoutMs` when it lies in [100, 30000], and 1000 when it is out of range or
not a number. A database error or a timeout answers 503 with
`{"error":"database"}`. A connection from the pool is awaited for at most
1000 ms. The image is `probe-service:0.3.0`.

**Cost.** `demo.timeoutMs` now changes what a request does, where before it
was only checked. An invalid value such as `abc` still writes the row with
1000 ms, so S2 and S5 behave as before for `/probe`. Each request is slower by
one round trip and one insert, and all replicas share one database, so
latency numbers of 0.3.0 should not be mixed with those of 0.2.0. The database is
not in the readiness group (`readinessState,configGate`), so a pod whose
database is down stays ready and answers 503. Flyway runs when a pod starts,
so I expect a pod that cannot reach the database not to start at all; not
verified. The table grows with every request and is never emptied. The
`postgres` StatefulSet is not in the `infrastructure` list of any descriptor,
so the runner does not check it before a run. The placement on the
control-plane node adds load next to the API server and etcd, as Loki does.

---

## 2026-11-23: probe-service and config-server move to Spring Boot 4.2

**Problem.** The database commit pinned Testcontainers 1.21.4 and Flyway
10.22.0 on top of Spring Boot 3.3.4. Seven minutes later both services moved
to Spring Boot 4.2.0 and Spring Cloud 2026.0.0, and the two pins were removed.
Why the pins were needed and why the upgrade came then: reason not recorded.

**Rejected.** Staying on Spring Boot 3.3.4 with the two pinned versions, which
is the state of commit `0f924ff`; reason not recorded.

**Chosen.** Spring Boot 4.2.0 and Spring Cloud 2026.0.0 for `probe-service`
and `config-server`. The code follows the new names: `spring-boot-starter-webmvc`,
`spring-boot-starter-flyway`, `spring-boot-starter-jdbc-test`, the health
classes in `org.springframework.boot.health.contributor`, Jackson 3
(`tools.jackson`, `asString()`) and Testcontainers 2
(`testcontainers-postgresql`, `org.testcontainers.postgresql`). The Config
Server image is `config-server:0.2.0`.

**Cost.** Both services changed their framework in the middle of the
experiment. Every smoke campaign before 2026-11-23 ran on Spring Boot 3.3.4,
so differences in start time, refresh or bus behaviour between old and new
runs can come from the upgrade and not from the database. Way C changes most,
because both of its Spring parts moved. I have no record of a run on the
cluster with Spring Boot 4 before the S1 smoke campaign of 2026-11-28.

---

## 2026-11-23: Every Way gets the database settings from one Secret through environment variables

**Problem.** The pods of all four Ways need the address, user and password of
the database. The four Ways deliver configuration in four different ways, and
the database settings are not the configuration under test.

**Rejected.** Delivering the database settings through each Way's own channel
(ConfigMap, mounted files, Config Server, Helm values); reason not recorded.
Relying on the
defaults in `application.yml` alone (`DB_URL`, `DB_USER`, `DB_PASSWORD` default
to the cluster address and `thesis`); reason not recorded. Reading the Secret
`postgres-credentials` of the namespace `data` directly: a pod can only refer
to a Secret in its own namespace.

**Chosen.** A Secret `probe-db` in `thesis` (`deploy/probe-db-secret.yaml`)
holds `username` and `password`. The four Deployments (Ways A, B and C, and the
template of the Helm chart of Way D) set `DB_URL` as a plain value and
`DB_USER` and `DB_PASSWORD` from `secretKeyRef`, in the same way for every Way.
For Way D the Secret is not part of the chart. The AppProject only allows
ConfigMaps, Services and Deployments, so Argo CD could not create it anyway.

**Cost.** The user and password are written twice, in `postgres-credentials`
and in `probe-db`, and both are in Git as plain `stringData` (`thesis` /
`thesis`). That is acceptable for a test cluster and not for production. A
change of the password needs both Secrets and a restart of the pods. The Secret
`probe-db` has to be applied by hand before any Way. No descriptor, script or
runbook applies it or `deploy/data/` yet. The Way D chart is in Git twice and
both copies have to carry the variables.

---

## 2026-11-28: The protocol file names image 0.3.0 and keeps protocol version 1 for the smoke runs

**Problem.** The database commits of 2026-11-23 moved every Way to
`probe-service:0.3.0`. `protocol/protocol-v1.json` still said `probe-service:0.2.0`.
`InvariantCheck` compares the two as text, so the first campaign with the database
stopped before its first run on 2026-11-28:
`image is probe-service:0.3.0, the protocol says probe-service:0.2.0`.
The check did its job. The protocol file was not updated when the image changed.

**Rejected.** Pointing the manifests back to 0.2.0: that image has no database,
and the database is what the professor asked for. Giving the database image the
tag 0.2.0: two different images would carry one name, and the fingerprint of old
and new campaigns could no longer be told apart by the tag. Raising
`protocolVersion` to 2 today: the gate mode that keeps the last good values is
still undecided and needs another image, so a version 2 today would be followed
by a version 3 before any full campaign.

**Chosen.** `workload.image` in the protocol file is `probe-service:0.3.0`.
`protocolVersion` stays 1 until the freeze planned for December, when the image,
the four approaches, the five scenarios and the gate decision become version 2
together. The same tag changed in the test fixtures of the runner
(`ProtocolTest`, `FingerprintTest`, `InvariantCheckTest` and the two files in
`src/test/resources/invariants/`).

**Cost.** Smoke campaigns from 2026-11-28 on carry `protocolVersion` 1 and a
different image than the campaigns of 15 to 21 November. Their numbers must not
be put in one table without saying so. The image is in `fingerprint.json` of
every campaign, so the two groups can be told apart. No full campaign may run
before version 2 is frozen.

---

## 2026-11-28: The runner empties the probe_request table before every run

**Problem.** Every `GET /probe` inserts one row into `probe_request`, and nothing
deleted rows. One run sends about 12,000 requests, so the planned full campaigns of
about 31 hours would leave about 5.6 million rows on a 1 GiB volume. Later runs
would also insert into a bigger table than earlier runs, and the order of the runs
would then be one more difference between them.

**Rejected.** A step in each approach descriptor: the table has nothing to do with
how a Way delivers configuration, and four copies of the same step could drift
apart. Emptying the table once per campaign: the runs inside a campaign would still
see different table sizes. `delete from probe_request`: it leaves dead rows behind
until vacuum runs, so the file on disk does not shrink. Emptying the table right
before the load, after the steady check: the probe-service pods are up then, and a
broken table would only show up during the measurement. Names written into the
Java code: the protocol file is where the runner keeps every fixed name of the
workload, and it is copied into `campaign.json` of every campaign.

**Chosen.** A core step in `Campaign.runOnce`, the same for all four Ways, right
after the old pods are gone and before the reset of the approach.
`ClusterControl.emptyTable` runs
`kubectl -n data exec postgres-0 -c postgres -- psql -U thesis -d probe -v ON_ERROR_STOP=1 -c "<sql>"`
with the steady timeout of the protocol (120 s) as its limit. The SQL is
`do $$ begin if to_regclass('probe_request') is not null then truncate table probe_request; end if; end $$`:
Flyway creates the table only when probe-service starts for the first time, so on
a new cluster the table does not exist before the first run, and a plain
`truncate` would stop that run. At that point no
probe-service pod exists, so no insert waits for the lock that `truncate` takes,
and the steady check that follows writes new rows and so shows that the table
works again. Like the other core steps, a non-zero exit code stops the campaign
with exit code 1. Namespace, pod, container, user, database and table are in a new
object `database` in `protocol/protocol-v1.json`. `protocolVersion` stays 1, as in
the entry before this one; the freeze as version 2 in December takes the new
fields with it.

**Cost.** One more `kubectl exec` per run, well under a second. The table is not
exactly empty when the load starts: the steady check writes 120 or more rows first,
about the same number in every run. A wrong table name in the protocol file would
be skipped without an error, because the step cannot tell it from a table Flyway
has not created yet; `ProtocolTest` pins the name. A jar built before this change cannot read the
protocol file any more, because the runner rejects unknown fields; the jar has to
be built again. The runner now needs PostgreSQL even for a Way that would work
without it, which is true for every Way since 0.3.0 anyway. The rows of a run are
lost when the next run starts; nothing in the evaluation reads them.

---

## 2026-11-28: probe-service tests load Mockito as a Java agent at JVM start

**Problem.** Every `mvn test` of probe-service printed three warnings: Mockito was
"self-attaching to enable the inline-mock-maker", the JDK said that dynamically
loaded agents will be disallowed in a future release, and the JVM said that class
data sharing works only for boot loader classes. No test uses Mockito directly;
Spring Boot's test support starts it. On a later JDK the self-attach would stop
working and the tests would fail.

**Rejected.** Leaving it: the warning is a dated failure. Switching Mockito to the
subclass mock maker: it cannot mock final classes, and it changes Mockito's
behaviour for a problem that is only about how it is loaded. Turning the JDK
warning off with `-XX:+EnableDynamicAgentLoading`: it hides the message and keeps
the mechanism the JDK is removing.

**Chosen.** The way Mockito's documentation describes: in
`services/probe-service/pom.xml` the `maven-dependency-plugin` goal `properties`
gives the path of the `mockito-core` jar as `${org.mockito:mockito-core:jar}`,
and `maven-surefire-plugin` starts the test JVM with
`-Xshare:off -javaagent:${org.mockito:mockito-core:jar}`. `-Xshare:off` turns off
class data sharing in the test JVM only, which removes the third warning. The
`@{argLine}` prefix that some guides show is left out, because no other plugin
here sets `argLine`, and with nothing to fill it the test JVM does not start.

**Cost.** Two plugin blocks in the pom. Without class data sharing the test JVM
might start a little slower; the whole build took 12.1 s against 12.4 s before.
If a coverage plugin such as JaCoCo is added later, its `argLine` has to be joined
with this one by hand. 29 tests, 0 failures, no warnings.

---

<!-- Template for new entries:

## YYYY-MM-DD — <decision in one line>

**Problem.**

**Rejected.**

**Chosen.**

**Cost.**

-->
