# 03: Experiment runner, stages 1 and 2

*October 2026, milestone 3 (in progress)*

## Summary

The curl loop from the first two milestones could show the inconsistency window but
not measure it. I replaced it with the first stage of the experiment runner: a small
Java program that sends requests on a fixed schedule, timestamps every answer on one
clock, and checks at the end whether it actually observed every replica often
enough. Two of its first test runs changed decisions. Without a new connection per
request, the runner saw only one of three pods. At 20 requests per second, one pod
went unobserved for 1.25 seconds even though the shares looked even, so the default
rate is now 50 requests per second.

In stage 2 the runner started saving every observation to a file, so each result
can be traced back to its raw data and analysed again later. It does not yet change
the configuration or detect the window. Those are stages 3 and 4.

Usage and file formats are in [document/runner.md](../runner.md).

Reference material: [architecture](../architecture.md),
[decisions](../../docs/DECISIONS.md), [previous entry](02-way-b-and-automatic-triggers.md).

## Environment

Unchanged from [entry 01](01-setup-and-first-experiment.md#environment). The runner
is compiled for Java 21 and runs on the host JDK (OpenJDK 27), outside the cluster.

## What I built

The runner lives in `tools/experiment-runner/`. It is plain Java with one library,
Jackson, for reading the JSON answers. I kept Spring Boot out of it on purpose: the
runner is the measuring instrument, and it should do nothing I did not write. The
shade plugin packs everything into one runnable `experiment-runner.jar`.

| Class | Job |
|---|---|
| `RunnerClock` | The single clock for every timestamp |
| `Observation` | One request and its answer, one row of data |
| `ProbeClient` | Sends one request and turns any outcome into an `Observation` |
| `LoadGenerator` | Starts requests at a constant rate and collects the results |
| `CoverageCheck` | Decides whether the run observed every replica often enough |
| `Runner` | Reads the arguments, wires the parts together, prints the summary |

A few design points matter for the thesis:

- `RunnerClock` reads the wall clock once at start and then advances with
  `System.nanoTime()`. The timestamps are real dates but cannot jump if macOS
  corrects its clock during a run.
- `LoadGenerator` starts a request every 1/rate seconds with `scheduleAtFixedRate`,
  and `ProbeClient` sends it with `sendAsync`, so the schedule never waits for an
  answer. The curl loop did wait, and would have stopped sampling exactly when a
  pod was slow.
- Every `Observation` keeps three times: when the request was scheduled, sent and
  answered. Latency is measured from the scheduled time, so any delay in the runner
  itself counts against the system instead of disappearing.
- Failures are rows too. A timeout or a refused connection becomes an `Observation`
  with an error, because in the invalid-configuration scenario the errors are part
  of the result.

## What I observed

The first run at 30 requests per second for 10 seconds lost no requests and spread
them over all three pods. Its p99 latency was 777 ms, which looked wrong. A 30-second
run gave a p99 of 15 ms: the high values came from the runner's own JVM warming up in
its first seconds. The measured part of a run will need a warm-up period before it.

Then I removed the `Connection: close` header as an experiment. The JDK client reused
its connection, kube-proxy only chooses a pod when a connection is opened, and 300 of
301 requests went to the same pod. The third replica was never observed. With that
default, the runner would have reported a perfectly consistent service during any
configuration change.

The coverage check came from an outside review of the runner, which pointed out that
kube-proxy's choice is random and not guaranteed to be even. I first planned to
check the share of requests per pod. The data showed why that is not enough:

| Rate | Shares per pod | Longest time a pod went unobserved | Result |
|---|---|---|---|
| 20 req/s | 29.5 / 36.3 / 34.3 % | 1 251 ms | Invalid |
| 50 req/s | 31.5 / 34.6 / 33.8 % | 397 ms | Valid |
| 50 req/s, second run | 31.2 / 32.0 / 36.7 % | 460 ms | Valid |

At 20 requests per second the shares looked fine, but one pod was skipped about 25
times in a row by chance. A version switch on that pod could have been detected more
than a second late. The check now looks at the longest gap per pod instead. That also
works for Way A, where old pods disappear halfway through a run and their share is
naturally small.

## Stage 2: saving the data

Until stage 2 the runner printed a summary and then lost every observation. For the
thesis every number has to be traceable to the measurements behind it, and the
window detector in stage 4 needs input to read. Each run now writes two files to
`results/raw/`, named after a run id made of the start time and a label:

- `<run id>.ndjson` holds one JSON object per line, one line per request;
- `<run id>.meta.json` holds the settings of the run and the coverage verdict.

I chose NDJSON over one large JSON array or a CSV file. If a run is interrupted, a
broken last line costs one row, while a missing closing bracket would make a whole
JSON array unreadable. CSV has no clean way to carry missing values and loses types.

The first version wrote absolute timestamps, and Jackson printed them as
`1.792878637013595E12`. Correct, but unreadable, and every analysis would have had
to subtract the start time first. `RunnerClock` now returns milliseconds since the
run started, so a line reads `"scheduledAt":193.19`, and the absolute start time is
stored once in the meta file. I also named that field `runStartedAt` instead of
`startedAt`, because every observation already has a `startedAt` that means
something else: when the pod started.

Invalid runs are saved too, with `"valid": false` and the reasons. A test with
`--replicas 4` against three pods was written to disk and marked
`only 3 of 4 replicas were observed`. Throwing such runs away silently would hide how
often the measurement failed.

Raw data is not committed. A two-minute run at 50 requests per second produces 6 000
lines, and the campaign will have 180 runs. `results/raw/` is in `.gitignore`; the
aggregated tables and figures will be committed instead.

## What went wrong

The `pom.xml` ended up inside the Java package folder instead of next to `src`,
probably moved by accident in the IDE. Maven then reported that there was no POM in
the project directory.

The shade plugin's `<goals>` element was empty after pasting, so the jar was built
without a start class and `java -jar` failed with "no main manifest attribute".

`Observation.java` was never saved the first time. The build still passed because
there was nothing new to compile, so a passing build alone did not prove the file
existed.

The shade plugin writes a `dependency-reduced-pom.xml` on every build. It is now in
`.gitignore`.

The run id uses the local time of the machine, while `runStartedAt` is in UTC. A run
started at 00:58 local time shows 22:58 in the meta file. Both are correct, but it is
easy to misread, so the runner documentation points it out.

## What I learned

An even share of requests per pod does not mean every pod is observed regularly.
The longest gap is the number that matters, because it sets how late a version
switch can be noticed.

The instrument has to check itself. Both test results above would have produced
plausible numbers without any error message, which is the same silent failure the
thesis studies in configuration delivery.

The runner needs a warm-up phase before the configuration change, or its own JVM
start will show up as latency in the first seconds of every run.

## Decisions made in this phase

| Topic | Decision |
|---|---|
| Runner technology | Plain Java 21 with the JDK HTTP client and Jackson, no framework |
| Connection handling | `Connection: close` on every request over HTTP/1.1 |
| Coverage rule | Every expected replica observed, longest gap per pod below 1 000 ms, otherwise exit code 2 |
| Request rate | 50 req/s by default |
| Raw data format | NDJSON per run plus a meta file, times in ms since run start, not committed |

Recorded in full in [docs/DECISIONS.md](../../docs/DECISIONS.md).

## Next

1. Stage 3: let the runner apply the configuration change and record its time on the
   runner's clock, after a warm-up period.
2. Stage 4: detect the window from the data and test the detector with known inputs.
3. Finish the move of both triggers into the `platform` namespace and measure the
   kubelet delay before tuning `refreshDelay`.
