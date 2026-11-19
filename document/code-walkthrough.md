# Code walkthrough

This document is for learning the code of the experiment runner and of
probe-service well enough to defend it. It has four parts:

1. [The classes](#the-classes): what each one does, what goes in and out, which
   section of the plan it implements, and the one thing that is easy to get wrong.
2. [The metrics](#the-metrics): the formula of all 27 metric ids and one worked
   example each, taken from a unit test with hand-calculated values.
3. [The scenarios](#the-scenarios): the timeline of each and what every approach
   did in the smoke run of 2026-11-15.
4. [One S1 run through the code](#one-s1-run-through-the-code), from `Runner.main`
   to `summary.csv`.

"Plan" means [docs/experiment-plan.md](../docs/experiment-plan.md). File formats
and arguments are in [runner.md](runner.md) and are not repeated here.

Runner code: `tools/experiment-runner/src/main/java/at/technikum/thesis/runner/`.
Runner tests: `tools/experiment-runner/src/test/java/at/technikum/thesis/runner/`.
probe-service code: `services/probe-service/src/main/java/at/technikum/thesis/probe/`.

## Four intervals to know first

Almost every metric is "something counted over an interval". The evaluator builds
four intervals per run (`Evaluator.Run`, constructor). All are half-open: the
start belongs to the interval, the end does not. A request belongs to an interval
by its intended send time.

| Name in the code | Interval | Length in S1 | Used for |
|---|---|---|---|
| `baseline`, `B` | 60 s before the start of the first step | 60 s | Instrument checks, the threshold of F1, the baseline columns of F2, "before" in O4 |
| `observation`, `H` | 150 s from the start of the first step | 150 s | P1, A1 to A3, F1 to F3, O4 |
| `afterBaseline` | From the start of the first step to the end of the run | 150 s | C2, C3, E1, E2, R1, U3, U4, U5, most flags |
| `measured`, `M` | From the start of `B` to the end of the run | 210 s | Instrument checks 4 to 7, E4 |

`overheadInterval` is `B` and `H` together and is used for O1 to O3.

In S1 the run ends 150 s after its only step, so `H` and `afterBaseline` are the
same. In S2 and S3 the run lasts 270 s after the first step, and `afterBaseline`
is that whole time.

"The start of the first step" is `RunRecord.reference()`. It is the time the step
really started, for example 90 003.9 ms, and not the planned 90 000 ms.

## The classes

### Entry and input files

#### `Runner`

- Does: reads the arguments and chooses one of three modes: campaign, evaluate,
  single run. The nested class `Wiring` creates the real `Evaluator`,
  `ResultWriter` and `ResourceSampler`.
- In and out: the command line goes in. Console output and the exit code come
  out.
- Plan: "Raw data and outputs" (the `--evaluate` argument), "Failure handling"
  (exit code 1).
- Easy to get wrong: after a campaign, `Runner.campaign` calls `Wiring.evaluate`
  on the folder. `runs.csv` and `summary.csv` are therefore always computed from
  the files on disk and never from the objects in memory. This also happens after
  a stopped campaign.

#### `CampaignConfig`

- Does: holds the six fields of a campaign file and loads everything a campaign
  needs.
- In and out: `read(file)` gives a checked `CampaignConfig`. `load()` reads the
  protocol, the scenario and every descriptor named in the protocol and gives a
  `CampaignConfig.Loaded`.
- Plan: "Campaign design", "Constants" (valid runs, blocks at most).
- Easy to get wrong: `load()` resolves paths against the working directory. The
  runner has to be started from the repository root. `validRuns` may not be
  larger than `maxBlocks`, and `maxBlocks` not larger than the 25 of the protocol.

#### `Protocol`

- Does: holds every constant of the protocol file, including the nested
  `Workload` record with the fixed fields of the Deployment and Service.
- In and out: `read(file)` gives a `Protocol` or throws with the list of problems.
- Plan: "Test protocol", "Constants", "What an approach may vary".
- Easy to get wrong: `targetVersion` is checked (it must differ from
  `initialVersion`) and is read nowhere else. The versions a run commits come from
  the payloads of the scenario file.

#### `Scenario`

- Does: holds name, gate, invalid versions and steps of a scenario file. The
  nested types are `Action` (four values) and `Step`.
- In and out: `read(file)`, then `problems(protocol)` for the checks.
  `payloads()`, `declaredVersions(protocol)` and `reportedMetrics()` are used by
  the evaluator.
- Plan: "Scenarios".
- Easy to get wrong: `reportedMetrics()` decides by the structure of the file and
  not by its name. `declaredVersions` returns `v1` first and then the payload
  versions in step order. C3 uses that order to know which version is the later
  one.

#### `Payload`

- Does: a `configVersion` and a `timeoutMs`.
- In and out: a record with two strings.
- Plan: "The adapter contract", "Scenarios".
- Easy to get wrong: `timeoutMs` is a string on purpose. S2 commits the value
  `abc`.

#### `ApproachDescriptor`

- Does: holds the fields of `approach.json`, with the nested records
  `Infrastructure` and `Declared`.
- In and out: `read(file)` gives a checked descriptor.
- Plan: "The adapter contract".
- Easy to get wrong: `trigger` and `sourceCheck` may be missing but may not be an
  empty list. The check on `commit` and `reset` only looks for the placeholders.
  Whether the command is atomic cannot be checked by the code.

#### `CampaignRecord`

- Does: the content of `campaign.json`.
- In and out: written by `Campaign.run`, read by `Evaluator.evaluateCampaign`.
- Plan: "Raw data and outputs".
- Easy to get wrong: it holds the whole protocol and scenario. An evaluation
  later uses these copies and not the files in the repository.

### The adapter boundary

#### `DeliveryAdapter`

- Does: the only interface between the core and an approach.
- In and out: `deploy`, `reset`, `commit(payload)`, `trigger(payload)`,
  `sourceCheck`, `infrastructure`, `declared`. Every command returns a
  `ChangeTrigger.Result` with times and exit code.
- Plan: "The adapter contract".
- Easy to get wrong: a failing command does not throw. The caller decides what an
  exit code means: `Campaign` stops on a failed `reset` or `deploy`, the evaluator
  marks a run INVALID on a failed `commit`. A failed `trigger` makes the run
  INVALID while the delivery components are up. Between `infraDown` and `infraUp`
  it sets the flag `TRIGGER_FAILED`.

#### `CommandAdapter`

- Does: implements the adapter by running the commands of a descriptor.
- In and out: descriptor, protocol and a `CommandRunner` go in. `fill` replaces
  `{initialVersion}`, `{initialTimeoutMs}`, `{version}` and `{timeoutMs}`. Only
  `trigger` is run with a timeout, the `triggerTimeoutSeconds` of the protocol.
- Plan: "The adapter contract" ("one implementation that executes the commands of
  a descriptor file").
- Easy to get wrong: commands are argument lists and run without a shell, so the
  JSON of a `kubectl patch` needs no shell quoting. `deploy` applies the manifests
  in order and stops at the first one that fails.

#### `CommandRunner`

- Does: runs one command.
- In and out: an argument list and a clock go in, a `ChangeTrigger.Result` comes
  out. A second `run` takes a timeout in milliseconds. `CommandRunner.process()`
  is the real one. A fake that implements only the first `run` ignores the
  timeout.
- Plan: none directly. It is the seam that lets the tests run without a cluster.
- Easy to get wrong: every `kubectl` and `git` call of a campaign goes through
  this one interface. The tests replace it with a fake that records the commands.

#### `ChangeTrigger`

- Does: starts a child process, waits for it, and records start time, end time,
  exit code and output on the runner clock.
- In and out: a command goes in, `Result(command, startedAt, finishedAt, exitCode,
  output)` comes out. A process that cannot be started gives exit code −1.
- Plan: "The five criteria" (`t_cs` and `T0` are `startedAt` and `finishedAt` of
  the commit).
- Easy to get wrong: the name is from the old single-run mode. Today it runs every
  command, not only triggers. `fire()` blocks until the command ends, so a command
  that hangs holds up whatever comes next. `fire(timeoutMillis)` stops the
  command and its child processes after the timeout and returns exit code 124
  with `timed out after … ms` as the last line of the output. The output goes to
  a temporary file while the command runs.

### Running a campaign

#### `Campaign`

- Does: the campaign loop (blocks, positions, attempts) in `run()` and the
  procedure of one run in `runOnce()`.
- In and out: a `Loaded` configuration and factories for adapter, sampler, judge
  and store go in. Files in the campaign folder and the exit code 0, 1 or 2 come
  out.
- Plan: "Procedure for one run", "Campaign design", "Failure handling".
- Easy to get wrong: a run has two clocks. `preparation` times the steps before
  the load (delete, reset, deploy, settle, checks). A new `RunnerClock` starts
  just before the load, and all measured times are on it. That is why the
  `sourceCheck` in a meta file has negative times.

#### `BlockPlan`

- Does: draws the order of every block.
- In and out: approach names, number of blocks and seed go in. A list of blocks
  comes out, each a shuffled copy of the names.
- Plan: "Campaign design".
- Easy to get wrong: all blocks are drawn up front from one `Random`. A smoke
  campaign with the same seed therefore has the same first blocks as the full
  campaign. The order of `approaches` in the protocol file is the input of the
  shuffle, so changing it changes every block.

#### `ClusterControl`

- Does: every `kubectl` call of the core (delete, wait, rollout status, set env,
  scale, delete pod, get as JSON) and the steady check.
- In and out: protocol, `CommandRunner`, `ServiceProbe` and `Pacer` go in.
  Results, JSON trees and `Snapshot` records come out.
- Plan: "Procedure for one run" steps 1, 3 and 5, "Scenarios" (the core actions),
  "The readiness gate" (`setGate`).
- Easy to get wrong: `awaitSteady` compares each snapshot with the one before it,
  10 s apart. Both must be steady and have the same pod names. A snapshot is 60
  requests sent one after the other, not at 50 per second. "Under 200 ms" is
  strict: an answer of exactly 200 ms is not steady.

#### `InvariantCheck`

- Does: compares the live Deployment, Service and pods with the `workload` block
  of the protocol and with the gate of the scenario.
- In and out: three JSON trees, the protocol and the gate value go in. A list of
  differences comes out, empty when everything matches.
- Plan: "What an approach may vary", "The readiness gate".
- Easy to get wrong: values are compared as text. `250m` in the protocol does not
  match `0.25` in a manifest. Only pods in phase `Running` are counted. The rule
  "same image id as in the first run" is in `Campaign.checkInvariants`, which
  calls `InvariantCheck.imageIds`.

#### `Fingerprint`

- Does: collects the environment and writes `fingerprint.json`.
- In and out: protocol, descriptors and a `CommandRunner` go in, a record with 12
  fields comes out.
- Plan: "Raw data and outputs".
- Easy to get wrong: it is taken once, before the first run. `workloadImages`
  shows the pods that were deployed before the campaign. A command that fails
  gives `null` and never stops the campaign.

#### `StepExecutor`

- Does: executes the steps of a scenario at their offsets, keeps the load running
  for 150 s after the last step, then stops the measurement and restores the
  infrastructure.
- In and out: `run(scenario, stopMeasurement)` gives a `Timeline` with one
  `StepRecord` per step and `runEndAt`.
- Plan: "Scenarios", "Procedure for one run" steps 7 and 8.
- Easy to get wrong: steps run one after the other. A step that is late because
  the one before it took long is not skipped, it starts as soon as the earlier
  one returns. `runEndAt` is the start of the last step plus 150 s. A failed
  `commit` is recorded and the run goes on. A failed core step throws
  `CampaignStopped`. The `trigger` runs only after a commit with exit code 0.

#### `Pacer`

- Does: tells the time and sleeps until a time on a run clock.
- In and out: `now(clock)` and `sleepUntil(clock, millis)`.
- Plan: none directly. Tests use a fake pacer so that a 360 s run takes no time.
- Easy to get wrong: `sleepUntil` with a time that has already passed returns at
  once.

#### `CampaignStopped`

- Does: the exception that ends a campaign with exit code 1.
- In and out: a reason text.
- Plan: "Failure handling", class "Campaign stopped".
- Easy to get wrong: it is the only way a campaign stops early on purpose.
  `Campaign.run` catches it, prints the reason and returns 1.

### Measuring

#### `RunnerClock`

- Does: gives milliseconds since its creation.
- In and out: `millisSinceStart()`, `startEpochMillis()`, `startIso()`.
- Plan: "The five criteria" ("All times are on the runner's clock").
- Easy to get wrong: it reads the wall clock once and then uses
  `System.nanoTime()`, so it cannot jump. Times of two runs are comparable only
  after adding `runStartedAtEpochMillis`.

#### `ServiceProbe`

- Does: sends the probes of the steady check and starts the load.
- In and out: `probe(count, clock)` gives `count` observations.
  `startLoad(clock)` gives a `Load` that can be stopped.
- Plan: "Procedure for one run" steps 5 and 6.
- Easy to get wrong: it exists so that `Campaign` can be tested with a fake
  service. The real one is `ServiceProbe.http(protocol)`.

#### `LoadGenerator`

- Does: starts one request every 20 ms and never waits for an answer.
- In and out: `start()`, then `stop()` gives all observations sorted by `seq`.
  `scheduledCount()` is the number of requests that were started.
- Plan: "The five criteria" (intended send time), finding 9 of "What exists today
  and what has to change".
- Easy to get wrong: `intendedAt` is `start + seq × period`, a pure calculation.
  `scheduledAt` is when the ticker really fired. If the JVM stalls, the ticker
  catches up in a burst, and the difference between the two fields shows it.
  Latency always counts from `intendedAt`.

#### `ProbeClient`

- Does: sends one `GET /probe` on a new connection and turns any outcome into an
  `Observation`.
- In and out: `probe(seq, intendedAt, scheduledAt)` gives a future. A timeout, a
  refused connection and an unreadable body all become observations with an
  `error` text.
- Plan: "The five criteria" (2 s timeout, failed request).
- Easy to get wrong: the header `Connection: close` only works because
  `Runner.main` sets the system property
  `jdk.httpclient.allowRestrictedHeaders` first. Without a new connection per
  request kube-proxy would keep sending to the same pod.

#### `Observation`

- Does: one request and its answer.
- In and out: a record with 10 fields. `latencyMillis()` is `receivedAt −
  intendedAt`. `ok()` is "no error and status 200".
- Plan: "The five criteria".
- Easy to get wrong: `ok()` is not the definition of a successful request in the
  metrics. `Availability.failed` also counts an answer without pod or without
  version as failed.

#### `Sampler` and `ResourceSampler`

- Does: reads `/metrics/resource` of every node every 5 s through
  `kubectl get --raw`.
- In and out: `start(clock)`, then `stop()` gives the list of `ResourceSample`.
- Plan: decision 5, "Constants" (resource sampling every 5 s).
- Easy to get wrong: it never throws. A failed read, a read that takes longer
  than 5 s, or a node that is still being read when the next round starts simply
  leaves no samples. The evaluator then sees the hole as a resource sampling gap
  and marks the run INVALID. `at` is the start of the round and is the same for
  all nodes of that round.

#### `ResourceMetricsParser`

- Does: parses the Prometheus text of the kubelet endpoint.
- In and out: text and node name go in. One `Reading` per container comes out,
  with CPU seconds and working set bytes.
- Plan: decision 5.
- Easy to get wrong: it reads two metric families only,
  `container_cpu_usage_seconds_total` and `container_memory_working_set_bytes`,
  and a container needs both lines to be included. Lines it cannot read are
  skipped without an error.

#### `ResourceSample`

- Does: CPU counter and working set of one container at one sampling time.
- In and out: a record with 8 fields.
- Plan: "Raw data and outputs" (`<run id>.resources.ndjson`).
- Easy to get wrong: `cpuSeconds` is a counter since the container started, not
  a rate.

### The run as data

#### `RunRecord`

- Does: everything one run produced before evaluation. Nested: `StepRecord`,
  `ClusterState`, `PodInfo`, `InfraState`.
- In and out: built by `Campaign.runOnce`, saved and read back by
  `ResultWriter`. `reference()`, `commits()`, `tCs()` and `t0()` are derived.
- Plan: "Raw data and outputs", "The five criteria" (`t_cs`, `T0`).
- Easy to get wrong: `reference()` is the start of the first step, `tCs()` the
  start of the first commit. They are the same in S1 and S2 and 30 s apart in S3.
  `t0()` is `null` when the first commit failed.

#### `RunMetrics`, `Metric`, `MetricId`

- Does: `MetricId` lists the 27 ids with criterion, title, unit and whether the
  metric is primary. `Metric` is one value of one column. `RunMetrics` is the
  result of one run: verdict, reasons, flags, `commitSucceeded`, metrics.
- In and out: produced by `Evaluator.evaluate`.
- Plan: "The five criteria", "Metrics of S2", "Metrics of S3", "Failure
  handling".
- Easy to get wrong: a censored `Metric` carries its bound as `value` and
  `censored` true. One id can have several columns, for example F2 has nine. The
  order of the enum matters, because `Scenario.reportedMetrics` uses ranges of
  it.

#### `RunJudge` and `RunStore`

- Does: two small interfaces, "evaluate a record" and "save a record".
- In and out: implemented by `Evaluator` and `ResultWriter`.
- Plan: "Summary" ("Collection and evaluation are separate").
- Easy to get wrong: they exist so that `Campaign` knows neither the evaluator
  nor the file format, and so that tests can pass fakes.

#### `ResultWriter`

- Does: writes the four files of a run and reads them back into a `RunRecord`.
- In and out: `save(record, metrics)`, `read(runId)`, `runIds()`. The old
  `write(RunResult, observations)` serves the single-run mode.
- Plan: "Raw data and outputs".
- Easy to get wrong: the meta file is the record without its three large parts,
  plus five fields that belong to the evaluation (`tCs`, `t0`, `verdict`,
  `invalidReasons`, `flags`). `read` removes these five again. A stored verdict
  therefore never influences a later evaluation.

### Evaluation

#### `Evaluator`

- Does: judges one run (`evaluate`) and a whole folder (`evaluateCampaign`).
  The inner class `Run` holds the four intervals, the instrument checks
  (`instrumentProblems`), the flags (`flags`) and the dispatch to the metric
  classes (`metrics(id)`).
- In and out: a `RunRecord` goes in, a `RunMetrics` comes out.
  `evaluateCampaign(dir)` writes `runs.csv` and `summary.csv`.
- Plan: "Failure handling", "The five criteria", "Metrics of S2", "Metrics of
  S3".
- Easy to get wrong: which interval a metric uses, see the table at the top.
  Metrics and flags are computed for INVALID runs too. They appear in `runs.csv`
  and are left out of the statistics in `summary.csv`.

#### `Phases` and `Interval`

- Does: `Interval` is a half-open time span. `Phases.of` places warm-up, `B` and
  `H` around the start of the first step.
- In and out: protocol and a time go in, three intervals come out.
- Plan: "The five criteria" (`B`, `H`), "Timeline of one run".
- Easy to get wrong: the end of an interval is excluded. A request intended
  exactly at the start of the first step belongs to `H` and not to `B`.

#### `WindowDetector`

- Does: finds the first answer with the new version and the last answer with the
  old one, the window between them and its upper bound (`window`), and decides
  whether the old version is still served at the end of an interval
  (`stillServed`). `detect` is the older method of the single-run mode.
- In and out: observations, the two versions and `t_cs` go in, a `Window` comes
  out.
- Plan: "The five criteria" (`t_first_new`, `t_last_old`, C1).
- Easy to get wrong: both times are receive times, and only answers received at
  or after `t_cs` count. When the new version never appears, `firstNewAt` is
  `null`, the window has no value, and `stillServed` is true.

#### `CoverageCheck`

- Does: computes, per pod, the longest time between two of its observations.
- In and out: observations and optionally an interval go in. A `Result` with one
  `PodCoverage` per pod and a list of problems comes out.
- Plan: "Failure handling" (coverage gap, `LOW_RESOLUTION`).
- Easy to get wrong: the gap is measured between a pod's own observations. A pod
  that disappears in a rolling restart has no gap after its last answer. A gap of
  exactly 1 000 ms is allowed.

#### `Propagation`, `Consistency`, `Availability`, `Performance`, `Overhead`, `ResourceUsage`

- Does: the metrics of criteria 1 to 5 as static functions.
- In and out: observations or resource samples and an interval go in, numbers or
  `Metric` values come out.
- Plan: "The five criteria".
- Easy to get wrong: `Availability` counts scheduled ticks, not observations. A
  tick without an observation is a failed request. `Performance` uses the
  nearest-rank percentile. `ResourceUsage` divides by the time between the first
  and the last sampling round in the interval, whichever pods were sampled then.

#### `InvalidConfiguration` and `InfrastructureOutage`

- Does: the metrics of S2 (E1, E2, R1) and of S3 (U1 to U4). E3, E4 and U5 reuse
  `Availability` with another interval.
- In and out: as above.
- Plan: "Metrics of S2", "Metrics of S3".
- Easy to get wrong: R1 looks at the last 10 s of the run
  (`InvalidConfiguration.TAIL_MILLIS`) to decide about censoring. U1 and U2 use
  receive times and ignore the interval, U3 uses the interval.

#### `Statistics`

- Does: percentiles, quartiles, bootstrap intervals, Cliff's delta.
- In and out: sorted arrays of numbers go in.
- Plan: "Statistics".
- Easy to get wrong: there are two percentile rules. `percentile` is nearest
  rank and is used for latencies. `quantile` interpolates linearly and is used
  for quartiles and medians across runs. A censored value is positive infinity.

#### `Summary`

- Does: builds the rows of `summary.csv`.
- In and out: all `RunMetrics` of a campaign, the approach order, the number of
  resamples and the seed go in.
- Plan: "Statistics", "Failure handling" (sensitivity check).
- Easy to get wrong: `metric` and `difference` rows use VALID runs only.
  `sensitivity` rows use all runs with a successful commit. Flag counts are over
  VALID runs. Every row has its own bootstrap seed, made from the stored seed,
  the approach and the column.

#### `Csv`

- Does: formats numbers and cells and writes CSV files.
- In and out: lists of strings go in, a file comes out.
- Plan: "Raw data and outputs".
- Easy to get wrong: numbers are written without exponent and without rounding.
  A censored metric of a run is `>` plus its bound. A censored statistic is the
  word `censored`.

### Single-run mode

#### `RunSettings` and `RunResult`

- Does: the settings and the result of the old single-run mode.
- In and out: filled from the arguments in `Runner.main`, written by
  `ResultWriter.write`.
- Plan: none. The plan keeps the old instrument, and this mode is what is left of
  it.
- Easy to get wrong: nothing in a campaign uses these two records.

### probe-service

#### `ProbeServiceApplication`

- Does: starts Spring Boot.
- In and out: none.
- Plan: "What exists today" (one image for all approaches).
- Easy to get wrong: nothing. All behaviour comes from the other classes and from
  `application.yml`.

#### `ProbeController`

- Does: answers `GET /probe` with value, version, pod name, start time and serve
  time.
- In and out: reads `demo.value` and `demo.configVersion`, returns a
  `ProbeResponse`.
- Plan: the object under study. "Scenarios" ("the version in an answer tells
  which payload the answering pod runs").
- Easy to get wrong: it is `@RefreshScope`. After a refresh it is rebuilt on the
  next request, so the new version shows on the first request after the refresh
  and not at the moment of the refresh. Without external configuration it answers
  `v0`.

#### `ProbeResponse`

- Does: the JSON body of `/probe`.
- In and out: a record with five fields.
- Plan: as above.
- Easy to get wrong: `servedAt` and `startedAt` are on the pod's clock. The
  runner does not use them for timing.

#### `PodIdentity`

- Does: remembers the pod name and the start time of the process.
- In and out: reads `HOSTNAME` once.
- Plan: none directly, see the decision of 2026-09-16.
- Easy to get wrong: it is deliberately not `@RefreshScope`. `startedAt` has to
  survive a refresh so that a restart can be told from a refresh.

#### `ConfigRules`

- Does: the two validation rules: `timeoutMs` an integer from 100 to 30 000,
  `configVersion` matching `v<number>`.
- In and out: reads the two properties as strings, `violations()` gives a list of
  texts.
- Plan: "The readiness gate".
- Easy to get wrong: it is `@RefreshScope`, so it sees refreshed values, and it
  reads `timeoutMs` as a string, so the value `abc` reaches the rule and is
  reported as "not an integer".

#### `ConfigHealth`

- Does: answers `GET /config-health` with 200 and `VALID`, or 503 and the
  violations.
- In and out: uses `ConfigRules`.
- Plan: "The readiness gate" (the rules of `/config-health`).
- Easy to get wrong: it answers the same with the gate on and off. It is not the
  gate.

#### `ConfigGateHealthIndicator`

- Does: the readiness gate. A health indicator named `configGate` that is down
  when `ConfigRules` has violations.
- In and out: uses `ConfigRules`, returns `Health.up()` or `Health.down()` with
  the violations as detail.
- Plan: "The readiness gate".
- Easy to get wrong: the bean exists only when `CONFIG_GATE_ENABLED` is `true`
  (`@ConditionalOnProperty`). `application.yml` always lists `configGate` in the
  readiness group and sets `validate-group-membership: false`, so the service
  also starts when the bean is missing. Liveness does not include it.

## The metrics

Each entry gives the formula as the code computes it and one example from a unit
test. In the examples, requests are 20 ms apart unless said otherwise.

### Criterion 1: propagation time

#### P1 convergence time (primary)

`max(t_last_old, t_first_new) − T0`. If v1 is still served at the end of `H`, or
v2 never appeared, the value is 150 000 and censored.

Example, `PropagationTest.p1_is_the_later_of_t_last_old_and_t_first_new_minus_T0`:
`T0` is 5 100 ms. Three pods are observed every 100 ms and switch at 6 000, 6 200
and 6 500 ms. The first v2 answer is at 6 000, the last v1 answer at 6 400 (the
third pod, one observation before its switch). P1 = 6 400 − 5 100 = 1 300 ms.

#### P2 time to first activation

`t_first_new − T0`. When v2 never appeared, the value is 150 000 and censored, as
for P1.

Example, `PropagationTest.p2_is_t_first_new_minus_T0`: same data, P2 = 6 000 −
5 100 = 900 ms.

#### P3 commit duration

`T0 − t_cs`. Empty when the commit failed.

Example, `PropagationTest.p3_is_T0_minus_t_cs`: `t_cs` 5 000, `T0` 5 100, P3 =
100 ms.

### Criterion 2: consistency

#### C1 inconsistency window (primary)

`max(0, t_last_old − t_first_new)`. The second column `C1_max` adds the
unobserved time before the first v2 answer and after the last v1 answer, each on
its own pod. Where a pod has no earlier or later answer, 1 000 ms is used.

C1 is censored in every run in which P1 is censored. Its value is then the window
seen so far, or 0 when v2 never appeared, and `C1_max` is empty. The reason is in
the [decision of 2026-11-19](../docs/DECISIONS.md).

Example, `WindowDetectorTest.c1_is_t_last_old_minus_t_first_new` and
`c1_upper_bound_adds_the_unobserved_time_…`: same three pods. C1 = 6 400 − 6 000
= 400 ms. The pod with the first v2 answer was last seen with v1 at 5 900, so 100
ms are unobserved. The pod with the last v1 answer is next seen at 6 500, another
100 ms. C1_max = 400 + 100 + 100 = 600 ms.

#### C2 client-visible reversions

The answers after the baseline, sorted by receive time over all pods. C2 counts
how often a v2 answer is directly followed by a v1 answer.

Example,
`ConsistencyTest.c2_counts_how_often_a_v2_answer_is_directly_followed_by_a_v1_answer_…`:
the versions in receive order are v1, v2, v1, v2, v2, v1, v2. A v1 follows a v2
twice. C2 = 2.

#### C3 pod regressions

Per pod: answers with an earlier version whose request was sent after the pod's
first answer with a later version had been received.

Example, `ConsistencyTest.c3_counts_v1_answers_from_a_pod_that_had_already_answered_v2`:
pod a answers v1, v2, v1, v1, v2. Two v1 answers come after its first v2. Pod b
answers v1, v2. C3 = 2.

### Criterion 3: availability

#### A1 failed-request ratio (primary)

Failed scheduled requests in `H` divided by scheduled requests in `H`. A request
is failed when it has no observation, is not a 200, has an error, or has no pod
or no version.

Example, `AvailabilityTest.a1_is_failed_in_H_divided_by_scheduled_in_H`: 100
scheduled requests. Two answer 503, one times out, one has no pod, one has no
version, one has no observation at all. A1 = 6 / 100 = 0.06.

#### A2 longest outage

The longest run of consecutive failed scheduled requests in `H`, times the period
of 20 ms.

Example,
`AvailabilityTest.a2_is_the_longest_run_of_consecutive_failed_requests_as_count_times_period`:
in the same data requests 10 to 14 fail in a row. A2 = 5 × 20 = 100 ms.

#### A3 lowest serving replicas

`H` is cut into 2 s buckets from its start. A3 is the lowest number of distinct
pods that answered in any bucket. A last bucket shorter than 2 s is left out.

Example,
`AvailabilityTest.a3_is_the_lowest_number_of_distinct_answering_pods_in_any_2_s_bucket`:
300 requests over 6 s. Requests 100 to 199, which are the second bucket, are
answered by two pods only. A3 = 2.

### Criterion 4: performance impact

#### F1 excess slow requests (primary)

θ is the p99 latency of the successful requests in `B`. F1 = (successful requests
in `H` slower than θ) − 0.01 × (successful requests in `H`). It can be negative.

Example,
`PerformanceTest.f1_is_the_ok_requests_in_H_slower_than_the_p99_of_B_minus_one_percent_…`:
the baseline has latencies 1 to 100 ms, so θ = 99 ms. `H` has 190 requests of
5 ms, 9 of 150 ms, one of 99 ms (not slower than θ) and one that failed. That is
200 successful requests, 9 of them slow. F1 = 9 − 0.01 × 200 = 7.

#### F2 latency percentiles

p50, p95, p99 and max of the successful requests in `B` and in `H`, and the ratio
of the two p99 values. Nine columns. Percentiles use the nearest rank: the value
at position `ceil(p / 100 × n)`.

Example, `PerformanceTest.f2_reports_p50_p95_p99_and_max_for_B_and_H_…`: `B` has
latencies 1 to 100 ms, giving 50, 95, 99 and 100. `H` has 20 requests with 10,
20, … 200 ms, giving 100, 190, 200 and 200. The ratio is 200 / 99 = 2.02.

#### F3 worst second

`H` is cut into 1 s buckets. F3 is the highest p95 of any bucket.

Example, `PerformanceTest.f3_is_the_highest_p95_of_any_1_s_bucket_of_H`: bucket 1
has latencies 1 to 20 ms, p95 = 19. Bucket 2 has ten requests of 5 ms and one of
80 ms, 11 values, so the p95 is the 11th value, 80. Bucket 3 has only 3 ms. F3 =
80 ms.

### Criterion 5: operational overhead

O1 to O3 are computed over `B` and `H` together.

#### O1 infrastructure CPU (primary)

For the pods of the approach's infrastructure Deployments: the sum of all
positive increases of the CPU counter between consecutive samples of a container,
divided by the time between the first and last sampling round, in millicores.

Example,
`OverheadTest.o1_and_o2_cover_the_pods_of_the_infrastructure_deployments_of_the_approach`:
Way C, two samples 100 s apart. The Config Server counter goes from 10.0 to 12.0
s, RabbitMQ from 50.0 to 51.0 s. O1 = (2.0 + 1.0) / 100 × 1000 = 30 mCPU.

#### O2 infrastructure memory (primary)

Per sampling round, the working sets of those pods are added. O2 is the highest
sum, in MiB.

Example, same test: first round 300 + 150 = 450 MiB, second round 280 + 150 =
430 MiB. O2 = 450 MiB.

#### O3 workload cost

The same two figures for the probe-service pods. Two columns, `O3_cpu` and
`O3_memory`.

Example, `OverheadTest.o3_is_the_same_two_figures_for_the_probe_service_pods`:
two pods use 4.0 and 6.0 CPU seconds in 100 s, O3_cpu = 100 mCPU. Their working
sets add up to 250 MiB in the first round and 510 MiB in the second, O3_memory =
510 MiB.

#### O4 pods replaced

Pods that answered in the last 1 000 ms of `H` and did not answer in `B`.

Example,
`OverheadTest.o4_counts_pods_answering_in_the_last_second_of_H_that_did_not_answer_in_B`:
three old pods answer in `B`. In the last second of `H` one old pod and two new
ones answer. O4 = 2.

#### O5 declared facts

Taken from the descriptor and from the infrastructure state before the run. Six
columns: number and names of the extra components, operator actions per change,
cluster-wide RBAC as 1 or 0, the coupling text, and the requested CPU and memory
of each infrastructure Deployment.

Example,
`OverheadTest.o5_reports_the_declared_facts_and_the_requests_of_the_infrastructure_as_columns`:
Way C gives 2 components (`Config Server; RabbitMQ`), 2 operator actions, RBAC 0,
`config-server=100m; rabbitmq=none` and `config-server=256Mi; rabbitmq=512Mi`.

### Scenario S2

The S2 examples use the run of
`EvaluatorTest.s2_reports_E1_to_R1_and_the_hand_calculated_values_of_a_bad_commit_and_its_fix`
unless another test is named: bad commit from 90 000 to 90 100 ms, fix from
210 000 to 210 080 ms, run end 360 000 ms, 18 000 requests spread evenly over
pods A, B and C.

#### E1 exposed replicas (primary)

Distinct pods that answered with an invalid version after the baseline.

Example: only pod A answers v2, from 100 000 to 102 000 ms. E1 = 1.

#### E2 exposed requests (primary)

Successful answers with an invalid version after the baseline.

Example: pod A gets every third request. In those 2 s, 100 requests are sent and
33 of them reach pod A. E2 = 33.

#### E3 availability under the bad configuration

A1 and A2 over the interval from the start of the bad commit to the start of the
fix commit. Two columns, `E3_ratio` and `E3_outage`.

Example: the interval is 120 s long and holds 6 000 scheduled requests. Five
requests in a row fail. E3_ratio = 5 / 6 000 = 0.00083, E3_outage = 5 × 20 =
100 ms.

#### E4 lowest serving replicas

A3 over the whole measured run, from the start of `B` to the end.

Example: for a part of the run pod A's requests are answered by pod B, so only
two pods answer in those buckets. E4 = 2.

#### R1 recovery time (primary)

From `T0` of the fix to the later of the last answer that is not v3 and the last
failed request, by receive time. If a request fails or an answer is not v3 in the
last 10 s of the run, R1 is the time from `T0` of the fix to the end of the run,
censored, and the flag `NOT_RECOVERED` is set.

Example: `T0` of the fix is 210 080. Pods B and C switch to v3 at 216 000, so the
last answer that is not v3 was intended at 215 980 and received at 215 982. R1 =
215 982 − 210 080 = 5 902 ms. In
`s2_a_pod_that_never_gets_the_fix_sets_the_flag_NOT_RECOVERED_…` pod C stays on
v1 and R1 is 360 000 − 210 080 = 149 920 ms, censored.

### Scenario S3

The S3 examples use the run of
`EvaluatorTest.s3_reports_U1_to_U5_and_the_hand_calculated_values_of_an_outage_with_a_replaced_pod`
unless another test is named: `infraDown` at 90 000, commit at 120 000,
`deletePod` at 150 000, `infraUp` from 210 000 to 212 000, run end 360 000.

#### U1 delivery during the outage

Distinct pods that answered v2 before `infraUp` started.

Example: the replacement pod D answers v2 from 156 000 on, the others still
answer v1 at 210 000. U1 = 1.

#### U2 replacement pod

Time from the start of `deletePod` to the first answer of a pod that had not
answered before, and the version of that answer in `U2_text`. Censored at the
time from `deletePod` to the run end when no new pod answers.

Example: pod D's first answer is received at 156 002. U2 = 156 002 − 150 000 =
6 002 ms, version v2.

#### U3 mixed-version time (primary)

For every pod, the time between two of its consecutive answers with the same
version counts as "this pod serves this version". U3 is the total time, after the
baseline, in which pods serve at least two different versions.

Example: v2 is served from 156 002 (pod D). v1 is served until 215 982, the last
v1 answer of pod C. U3 = 215 982 − 156 002 = 59 980 ms. A small case is
`InfrastructureOutageTest.u3_for_one_switch_equals_t_last_old_minus_t_first_new`,
where U3 is 50 ms.

#### U4 catch-up time (primary)

From the end of `infraUp` to convergence on v2, at least 0. If v1 is still served
in the last 1 000 ms of the run, or v2 never appeared, U4 is the time from the
end of `infraUp` to the run end, censored, and the flag `LOST_UPDATE` is set.

Example: `infraUp` ends at 212 000, the last v1 answer is at 215 982. U4 =
3 982 ms. In `s3_v1_still_served_at_the_run_end_sets_the_flag_LOST_UPDATE_…` it
is 360 000 − 212 000 = 148 000 ms, censored.

#### U5 failed-request ratio

A1 over the whole run after the baseline.

Example,
`EvaluatorTest.s3_u5_is_the_failed_request_ratio_over_the_whole_run_after_the_baseline`:
the run has 13 500 scheduled requests after the baseline and three of them fail.
U5 = 3 / 13 500 = 0.00022.

### One hand calculation over a whole S1 run

`EvaluatorTest.the_five_criteria_of_a_staggered_switch_match_the_hand_calculation`
is the test to read first. It builds a complete S1 run (12 000 requests, commit
from 90 000 to 90 100 ms, pods switching at 95 000, 95 200 and 95 500 ms) and
checks every column: P1 5 342, P2 4 942, P3 100, C1 400, C1_max 520, C2 7, C3 0,
A1 0, A2 0, A3 3, F1 −75 (no slow request, minus 1 % of 7 500), all latency
columns 2 ms, O1 10 mCPU, O2 64 MiB, O3 150 mCPU and 768 MiB, O4 0.

## The scenarios

The numbers in this part come from `runs.csv` and the meta files in
`results/aggregated/` and from the NDJSON files in `results/raw/` of the smoke
campaigns of 2026-11-15. They are one or two runs per approach and are not
results. The same numbers with more context are in
[journal entry 06](journal/06-experiment-protocol.md#what-the-smoke-runs-showed).

### S1 valid change

```
0 s        30 s          90 s                                   240 s
|  warm-up  |  baseline B  |  observation H                       |
                           ^ commit v2 (timeoutMs 1000), then trigger
```

File `scenarios/s1-valid-change.json`, gate off, metrics P1 to O5.

| | Way A | Way B | Way C |
|---|---|---|---|
| Runs | 2 VALID | 2 VALID | 2 VALID |
| What the pods did | Three new pods, first v2 answers at 99.1, 110.3 and 119.2 s in run 1 | The same three pods switched within 138 ms at 180.6 s in run 1 | The same three pods switched between 95.0 and 96.2 s in run 1 |
| P1 | 30.16 s, 30.03 s | 90.50 s, 90.33 s | 4.43 s, 4.24 s |
| P2 | 9.07 s, 9.37 s | 90.50 s, 90.33 s | 3.31 s, 3.30 s |
| P3 | 61 ms, 73 ms | 67 ms, 63 ms | 1 689 ms, 5 058 ms |
| C1 | 21.09 s, 20.66 s | 0, 0 | 1 125 ms, 938 ms |
| C2 | 229, 242 | 0, 0 | 12, 10 |
| A1 | 0, 0 | 0, 0 | 0, 0 |
| O4 | 3, 3 | 0, 0 | 0, 0 |

How to read Way A: P2 is the 9 s until the first new pod answered. P1 is the
30 s until the last old pod had answered for the last time, after three pods were
replaced one after the other. C1 is the 21 s in between, in which old and new
pods answered side by side, and C2 counts the 229 times a client saw v2 and then
v1 again.

How to read Way B: P1 and P2 are the same, because all pods switch almost at the
same moment, 90 s after the commit. That is the Watcher's `refreshDelay`. C1 is 0
because the last v1 answer came before the first v2 answer. The upper bound was
237 ms and 131 ms.

How to read Way C: P3 is large because the commit is a push to GitHub. P1 counts
from the end of that push and includes the `busrefresh` call, which took 2.4 s in
both runs.

### S2 invalid configuration

```
0 s        30 s          90 s                 210 s                        360 s
|  warm-up  |  baseline B  |  bad config       |  after the fix             |
                           ^ commit v2 (abc)   ^ commit v3 (1000)
```

File `scenarios/s2-invalid-config.json`, gate on, `invalidVersions` `["v2"]`,
metrics E1 to E4 and R1.

| | Way A | Way B | Way C |
|---|---|---|---|
| Run | VALID | VALID, `FAILED_REQUESTS`, `NOT_RECOVERED` | VALID, `FAILED_REQUESTS`, `LOW_RESOLUTION` |
| What the pods did | v2 was never served. v1 pods answered until 220 to 238 s, new pods answered v3 from 219.5 s | All three served v2 from 180.3 s. From 185.9 s every request timed out until the end. v3 was never served | All three served v2 from 94.9 s. From 101.7 s every request failed until v3 arrived at 218.7 s |
| E1 | 0 | 3 | 3 |
| E2 | 0 | 276 | 315 |
| E3 ratio | 0 | 0.201 | 0.903 |
| E3 outage | 0 | 24.16 s | 108.36 s |
| E4 | 3 | 0 | 0 |
| R1 | 28.33 s | more than 149.94 s, censored | 4.53 s |
| Failed requests in the whole run | 0 | 8 707 of 18 000 | 5 797 of 18 001 |

Why Way B did not recover was not verified in the Watcher's logs. The data only
shows that no successful answer arrived after 185.8 s.

How the numbers fit together for Way B: E3 covers the 120 s between the two
commits, which are 6 000 requests. The failures start at 185.86 s, so 1 208
requests fail before the fix commit at 210 s. 1 208 / 6 000 = 0.201, and 1 208 ×
20 ms = 24.16 s.

### S3 infrastructure outage

```
0 s        30 s          90 s        120 s      150 s        210 s           360 s
|  warm-up  |  baseline B  |  outage                           |  after infraUp |
                           ^ infraDown ^ commit v2 ^ deletePod   ^ infraUp
```

File `scenarios/s3-infra-outage.json`, gate off, metrics U1 to U5. The baseline
ends at `infraDown`, 30 s before `t_cs`.

| | Way A | Way B | Way C, three attempts |
|---|---|---|---|
| Run | VALID, `LOST_UPDATE` | VALID | INVALID three times: `trigger after the commit of v2 failed with exit code 1` |
| Taken down | `reloader-reloader` | `config-watcher` | `config-server`, `rabbitmq` |
| What the pods did | The replacement pod answered v2 from 159.1 s. The two others served v1 to the end | The replacement pod answered v2 from 159.7 s. The two others switched to v2 at 313.8 s | The trigger blocked for 60 s. `deletePod` started at 181.8, 185.3 and 181.8 s. The replacement pod answered v2 from 240.4, 245.6 and 243.2 s. The two others served v1 to the end |
| U1 | 1 | 1 | 0, 0, 0 |
| U2 | 9.05 s, v2 | 9.67 s, v2 | 58.65 s, 60.29 s, 61.38 s, v2 |
| U3 | 200.9 s | 154.0 s | 119.5 s, 114.4 s, 116.8 s |
| U4 | more than 149.9 s, censored | 103.66 s | more than 149.9 s, censored |
| U5 | 0 | 0 | 0, 0, 0 |

In Way A and Way B the commit goes to the ConfigMap, which does not need the
delivery component. A pod that starts during the outage therefore reads v2, and
the running pods stay on v1. That is where the mixed-version time comes from.

The Way C values are from INVALID runs and are not in `summary.csv`.

## One S1 run through the code

The command is
`java -jar … --campaign campaigns/s1-smoke.json`. The numbers in the steps are
from the first run of the S1 smoke campaign, `20261115-155727_way-a_b01_a1`.

### Before the first run

1. `Runner.main` sets the system property for the `Connection` header, finds
   `--campaign` with `Runner.arg` and calls `Runner.campaign(file)`.
2. `CampaignConfig.read` parses the campaign file and runs
   `CampaignConfig.problems`.
3. `CampaignConfig.load` calls `Protocol.read`, `CampaignConfig.problems(protocol)`,
   `Scenario.read`, `Scenario.problems(protocol)` and `ApproachDescriptor.read`
   for each of the three descriptors. The result is a `CampaignConfig.Loaded`.
4. `Runner.campaign` creates a `Campaign` with `Wiring.sampler`, `Wiring.judge`
   (an `Evaluator`) and `Wiring.store` (a `ResultWriter`). The `Campaign`
   constructor creates one `ClusterControl`.
5. `Campaign.run` calls `BlockPlan.draw(names, 2, 20261115)`, which gives
   `[[way-a, way-b, way-c], [way-b, way-a, way-c]]`.
6. `Campaign.run` creates the folder `results/raw/20261115-155726_s1-smoke/`,
   writes `campaign.json` from a `CampaignRecord`, writes one file per descriptor
   under `approaches/`, creates a `CommandAdapter` per approach, and calls
   `Fingerprint.collect(…).write(…)`.

### Preparing the run

7. The loop over blocks, positions and attempts calls `Campaign.runOnce` for
   `way-a`, block 1, position 1, attempt 1. It builds the run id and a
   `RunnerClock` named `preparation`.
8. `ClusterControl.deleteDeployment` and `ClusterControl.waitUntilPodsGone`.
   Each result goes through `Campaign.must`, which throws `CampaignStopped` on an
   exit code other than 0.
9. `CommandAdapter.reset` runs the `kubectl patch` that sets both keys to `v1`
   and `1000`. `CommandAdapter.sourceCheck` returns empty for Way A.
10. `CommandAdapter.deploy` runs `kubectl apply -f deploy/way-a-envvar`.
    `ClusterControl.rolloutStatus` waits for the rollout.
11. `ClusterControl.setGate(false, …)` runs `kubectl set env
    deployment/probe-service CONFIG_GATE_ENABLED=false`, and
    `ClusterControl.rolloutStatus` waits again.
12. `Pacer.sleepUntil` waits the 100 s of `settleSeconds`.
13. `Campaign.checkInvariants` reads the Deployment, the Service and the pods
    through `ClusterControl`, calls `InvariantCheck.check`, then
    `Campaign.infrastructureDown` for the four infrastructure Deployments, then
    `InvariantCheck.imageIds` and remembers the image id of this first run.
14. `ClusterControl.awaitSteady` takes snapshots with `ClusterControl.snapshot`
    (60 requests through `ServiceProbe.probe`) until two in a row pass
    `ClusterControl.isSteady`.
15. `Campaign.podList` reads the pods of `thesis` and `platform`.
    `Campaign.infrastructure` reads replica count and requests of
    `reloader-reloader` with `ClusterControl.infraState`.

### Measuring

16. `runOnce` creates the run's `RunnerClock`, a `Measurement` with a new
    `ResourceSampler`, and a `StepExecutor`.
17. `Measurement.start` calls `ServiceProbe.startLoad`, which creates a
    `ProbeClient` and a `LoadGenerator` and calls `LoadGenerator.start`. From now
    on the ticker calls `ProbeClient.probe` every 20 ms, and every answer becomes
    an `Observation`. Then `ResourceSampler.start` begins its rounds: `round`
    starts one `read` per node, and `read` calls `ResourceMetricsParser.parse`.
18. `StepExecutor.run` calls `Pacer.sleepUntil` for 90 000 ms, then
    `StepExecutor.execute`, which calls `StepExecutor.commit`.
19. `StepExecutor.commit` calls `CommandAdapter.commit(payload)`. `fill` puts
    `v2` and `1000` into the patch command, and `ChangeTrigger.fire` runs it. In
    this run it started at 90 003.9 ms and ended at 90 065.2 ms with exit code 0.
    `CommandAdapter.trigger` returns empty for Way A. The result is a
    `RunRecord.StepRecord`.
20. `StepExecutor.run` computes `runEndAt` as 90 003.9 + 150 000 = 240 003.9 ms
    and sleeps until then.
21. In its `finally` block `StepExecutor.run` calls the `stopMeasurement`
    callback, which is `Measurement.stop`: `LoadGenerator.stop` returns the
    observations (12 001 requests were scheduled in this run) and
    `ResourceSampler.stop` returns the samples. Then `StepExecutor.restore` runs.
    It has nothing to do in S1.
22. `Campaign.podList` and `Campaign.events` read the cluster again.
23. `runOnce` builds the `RunRecord`.

### Judging and saving the run

24. `Evaluator.evaluate(record)` asks `Scenario.reportedMetrics`, which gives P1
    to O5 for S1, and creates an `Evaluator.Run`.
25. The `Run` constructor calls `Phases.of(protocol, 90 003.9)`: `B` is
    [30 003.9, 90 003.9) and `H` is [90 003.9, 240 003.9). It creates the
    `Availability` object and calls `WindowDetector.window`, which finds the first
    v2 answer and the last v1 answer.
26. `Run.instrumentProblems` runs the checks: `CoverageCheck.check` on `B`,
    `Availability.failedIn(B)`, the versions in `B`, `Evaluator.commitProblems`,
    `Run.scheduleLagProblems`, `Availability.missingIn`, the undeclared versions
    and `Run.samplingProblems`. In this run the list is empty.
27. `Run.metrics(id)` is called for each of the 17 ids and dispatches to
    `Propagation`, `Consistency`, `Availability`, `Performance`, `Overhead` and
    `ResourceUsage`. For this run: `T0` is 90 065.2, P1 is 30 160.6 ms, so the
    last v1 answer was received at 120 225.8 ms. P2 is 9 069.4 ms, C1 is 21 091.2
    ms.
28. `Run.flags` checks the six flags. None is set.
29. `evaluate` returns a `RunMetrics` with the verdict VALID.
30. `ResultWriter.save` writes the four files of the run. The meta file gets
    `tCs`, `t0`, `verdict`, `invalidReasons` and `flags`.
31. Back in `Campaign.run`, the line `20261115-155727_way-a_b01_a1 VALID` is
    printed, the counter of `way-a` goes to 1, and the attempt loop ends with
    `break`. The next position starts at step 7.

### After the last run

32. When the blocks are done, `Campaign.run` prints `Valid runs: {…}` and returns
    0, because `Campaign.reached` is true.
33. `Runner.campaign` calls `Wiring.evaluate(directory)`, which calls
    `Evaluator.evaluateCampaign`.
34. `evaluateCampaign` reads `campaign.json` into a `CampaignRecord`, reads the
    descriptors from `approaches/`, and builds a new `Evaluator` from these
    copies.
35. For every id from `ResultWriter.runIds` it calls `ResultWriter.read`, then
    `Evaluator.evaluate` again, then `Evaluator.row`. `Csv.write` writes
    `runs.csv` with the header from `Evaluator.header`.
36. `Summary.of` builds the rows: counts per approach, then
    `Summary.statistics` per approach and column (with
    `Statistics.medianInterval` and a seed from `Statistics.seed`), then
    `Summary.difference` per pair and primary column (with
    `Statistics.medianDifferenceInterval` and `Statistics.cliffsDelta`), then the
    sensitivity rows for A1 and F1.
37. `Summary.writeCsv` writes `summary.csv`.
38. `Runner.main` ends the process with `System.exit(0)`.

Every run is evaluated twice: once in step 24 from memory, for the verdict that
decides about a repeat, and once in step 35 from the files, for the CSV files.
Both use the same `Evaluator.evaluate`.
