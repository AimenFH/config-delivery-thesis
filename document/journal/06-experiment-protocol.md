# 06: Experiment protocol version 1

*15 November 2026, milestone 5*

## Summary

The experiment now has one fixed protocol for every approach. A protocol file
holds every constant, a scenario file holds the timed steps, and an approach is a
folder with manifests and one descriptor. The runner runs campaigns in randomized
blocks from a fresh deployment, saves the raw data of every run and computes 27
metrics from the raw data. probe-service got a readiness gate and is now version
0.2.0.

The code was written by Claude Code agents in one day, from the frozen plan in
[docs/experiment-plan.md](../../docs/experiment-plan.md). I did not write this
code myself, so the [code walkthrough](../code-walkthrough.md) exists for me to
learn it.

The three smoke campaigns ran on the kind cluster. They are checks that the
instrument works, with one or two runs per approach. They are not results.

| Smoke campaign | Runs | Verdicts | Exit code |
|---|---|---|---|
| S1 valid change | 6 | 6 VALID | 0 |
| S2 invalid configuration | 3 | 3 VALID, two of them with flags | 0 |
| S3 infrastructure outage | 5 | 2 VALID, 3 INVALID (all Way C) | 2 |

The full campaigns have not been run. They wait for the [open points](#open-points).

Reference material: [runner](../runner.md),
[runbook](../runbook-experiment-1.md), [architecture](../architecture.md),
[decisions](../../docs/DECISIONS.md), [previous entry](05-platform-namespace-and-kubelet-delay.md).

## Environment

From `fingerprint.json` of the three campaigns:

| | |
|---|---|
| Git commit | `e507d44`, clean for S1 and S2, dirty for S3 |
| Kubernetes | client v1.36.1, server v1.37.0, 4 nodes |
| Java | OpenJDK 27.0.1 |
| Machine | Darwin 27.0.0, arm64 |
| Docker | 10 CPUs, 8 319 504 384 bytes of memory |
| probe-service | `probe-service:0.2.0`, the same image id in all three campaigns |
| Reloader | `ghcr.io/stakater/reloader:v1.4.22` |
| Configuration Watcher | `springcloud/spring-cloud-kubernetes-configuration-watcher:3.1.6` |
| Config Server, RabbitMQ | `config-server:0.1.0`, `rabbitmq:3.13` |

The S1 fingerprint lists three probe-service pods with the image 0.1.0. The
fingerprint is taken before the first run, so these are the pods that were still
deployed from before. The runs themselves used 0.2.0, which the pod lists in the
`cluster.json` files show.

## What was built

| Part | Files |
|---|---|
| Protocol, scenarios, descriptors, campaign files | `protocol/protocol-v1.json`, `scenarios/s1-valid-change.json`, `scenarios/s2-invalid-config.json`, `scenarios/s3-infra-outage.json`, `approaches/way-a`, `way-b`, `way-c`, six files in `campaigns/` |
| Adapter boundary | `DeliveryAdapter`, `CommandAdapter`, `ApproachDescriptor`, `Payload` |
| Campaign | `Campaign`, `BlockPlan`, `ClusterControl`, `InvariantCheck`, `Fingerprint`, `StepExecutor` |
| Resource data | `ResourceSampler`, `ResourceMetricsParser`, `ResourceUsage` |
| Evaluation | `Evaluator`, one class per criterion, `InvalidConfiguration`, `InfrastructureOutage`, `Statistics`, `Summary`, `Csv` |
| Raw data | `RunRecord`, `ResultWriter` with four files per run, `--evaluate` |
| Readiness gate | `ConfigGateHealthIndicator`, `ConfigRules` in probe-service, image 0.2.0 |
| Way C script | `tools/way-c-commit.sh` replaces `tools/way-c-change.sh`. The refresh call is now the trigger in the descriptor |

The old campaign files moved to `campaigns/legacy/`. The single-run mode of the
runner still exists.

The test sources hold 477 `@Test` methods in the runner and 18 in probe-service.
Before the build the runner had 27 tests. I counted the methods in the sources and
did not run them for this entry.

## How it was built

The build followed [docs/claude-code-build-prompt.md](../../docs/claude-code-build-prompt.md).
One orchestrating agent coordinated subagents. Three rules were in every brief:
design nothing new, do not change the plan, and collect ideas for the final report
instead of building them.

| Phase | What happened | Commit |
|---|---|---|
| 0 | The 27 old tests passed, the cluster was reachable, ten decision entries were written | `ecb21b3` |
| 1 | One agent, `contracts`, wrote the protocol file, the scenario files, the approach descriptors and the adapter interface with their unit tests | `ecb21b3` |
| 2 | Four agents in parallel, each in its own worktree: `campaign`, `evaluator`, `resources`, `gate` | `e8abbcb` |
| 3 | The parts were merged and wired together. An agent `test-audit` with a fresh context got the plan and the test sources and listed what had no test. An agent `reviewer` with a fresh context got the plan and the diff and looked for differences between code and plan | `e507d44` |
| 4 | The three smoke campaigns on the cluster | results in `results/aggregated/` |
| 5 | This documentation | not committed yet |

The expected values in the metric tests are calculated by hand from the formulas
of the plan. A test that only repeated what the code does was not accepted.

The lists of findings of the test audit and of the review are not in a file of
the repository. I cannot say from the repository how many findings there were or
which were rejected.

Where the plan's text left a detail open, the build chose a reading. I recorded
eleven of them as decision entries, see
[Decisions made in this phase](#decisions-made-in-this-phase).

## What the smoke runs showed

All numbers come from `runs.csv` and the meta files in `results/aggregated/` and,
where I say "raw data", from the NDJSON files in `results/raw/`. Every number is
from one or two runs.

### S1 valid change

Two blocks, order `[way-a, way-b, way-c]` and `[way-b, way-a, way-c]`. All 6 runs
are VALID and have no flag.

| Metric | Way A | Way B | Way C |
|---|---|---|---|
| P1 convergence | 30.16 s, 30.03 s | 90.50 s, 90.33 s | 4.43 s, 4.24 s |
| P2 first activation | 9.07 s, 9.37 s | 90.50 s, 90.33 s | 3.31 s, 3.30 s |
| P3 commit duration | 61 ms, 73 ms | 67 ms, 63 ms | 1 689 ms, 5 058 ms |
| C1 inconsistency window | 21.09 s, 20.66 s | 0, 0 | 1 125 ms, 938 ms |
| C1 upper bound | 23.09 s, 22.66 s | 237 ms, 131 ms | 1 323 ms, 1 107 ms |
| C2 client-visible reversions | 229, 242 | 0, 0 | 12, 10 |
| C3 pod regressions | 0, 0 | 0, 0 | 0, 0 |
| A1 failed-request ratio | 0, 0 | 0, 0 | 0, 0 |
| A3 lowest serving replicas | 3, 3 | 3, 3 | 3, 3 |
| F1 excess slow requests | 8, 53 | 1, −4.99 | −3, −60 |
| O1 infrastructure CPU | 0.38, 0.45 mCPU | 1.88, 1.45 mCPU | 8.97, 8.46 mCPU |
| O2 infrastructure memory | 13.3, 13.1 MiB | 409.6, 412.0 MiB | 483.8, 488.6 MiB |
| O3 workload CPU | 128.3, 127.2 mCPU | 66.3, 64.1 mCPU | 63.8, 74.5 mCPU |
| O3 workload memory | 1 095, 1 099 MiB | 768, 765 MiB | 832, 836 MiB |
| O4 pods replaced | 3, 3 | 0, 0 | 0, 0 |

The medians of P1 are 30.1 s for Way A, 90.4 s for Way B and 4.3 s for Way C. The
pilot data of scenario 1 had 28.2 s, 90.2 s and 1.6 s. Way A and Way B are close to
the pilot. Way C is higher, which was expected: `T0` is now the end of the commit,
so the trigger counts as delivery time. The `busrefresh` call took 2 362 ms and
2 450 ms in the two runs.

Way C's commit, a push to GitHub, took 1.7 s in one run and 5.1 s in the other.
The `git ls-remote` check before those runs took 505 ms and 643 ms.

In the raw data Way A shows the rolling restart: three new pod names, first v2
answers at 99.1 s, 110.3 s and 119.2 s in the first run. Way B's three pods
switched within 138 ms of each other at 180.6 s. Way C's three pods switched
between 95.0 s and 96.2 s.

In the second Way C run the baseline had a p99 of 22.3 ms and a maximum of 479 ms,
against a p99 of 7.6 ms in `H`. That is why F1 is −60 and the p99 ratio is 0.34 in
that run. I did not look for the cause of the slow requests in the baseline.

### S2 invalid configuration

One block, order `[way-a, way-b, way-c]`, gate on. The bad commit (v2 with
`timeoutMs` `abc`) is at 90 s, the fix (v3) at 210 s, the run ends at 360 s. All 3
runs are VALID.

| Metric | Way A | Way B | Way C |
|---|---|---|---|
| Flags | none | `FAILED_REQUESTS`, `NOT_RECOVERED` | `FAILED_REQUESTS`, `LOW_RESOLUTION` |
| E1 exposed replicas | 0 | 3 | 3 |
| E2 exposed requests | 0 | 276 | 315 |
| E3 failed-request ratio, bad commit to fix | 0 | 0.201 | 0.903 |
| E3 longest outage, bad commit to fix | 0 | 24.16 s | 108.36 s |
| E4 lowest serving replicas | 3 | 0 | 0 |
| R1 recovery time | 28.33 s | more than 149.94 s, censored | 4.53 s |

What the raw data shows per approach:

Way A never served v2. The three v1 pods answered until 220.4 s, 229.2 s and
238.4 s, and three new pods answered v3 from 219.5 s, 228.3 s and 237.5 s. No
request failed. I did not check in the events file whether a v2 pod was started
and stayed unready.

Way B served v2 from all three pods, starting at 180.3 s, which is 90.3 s after
the bad commit. The last successful answer came at 185.8 s. From 185.86 s to the
end of the run every request timed out: 8 707 of 18 000 requests failed. The fix
was committed at 210 s and v3 was never observed in the remaining 150 s. The cause
was not verified in the Watcher's logs. I do not know from the data whether the
Watcher did not send a refresh, could not reach the pods, or something else. The
cause was found on 19 November, see [entry 07](07-way-b-gate-deadlock.md).

Way C served v2 from all three pods, starting at 94.9 s. The last successful
answer came at 101.6 s, then every request failed until v3 arrived: 5 797 failed
requests. The fix commit ended at 215.0 s, its trigger ended at 217.2 s, the first
v3 answer came at 218.7 s and the last failed request at 219.6 s. After that all
three pods served v3 to the end.

In the Way B and Way C runs all three replicas got the invalid configuration,
and after a few seconds no pod answered through the Service any more (E4 is 0).
That is what the gate is built to do when every pod holds an invalid value. I
did not check the readiness of the pods in the events files. Way A never got into
that state in its run.

### S3 infrastructure outage

One block, order `[way-a, way-b, way-c]`, gate off. `infraDown` at 90 s, commit v2
at 120 s, `deletePod` at 150 s, `infraUp` at 210 s, the run ends at 360 s. No
request failed in any of the 5 runs.

| Metric | Way A | Way B | Way C, three attempts |
|---|---|---|---|
| Verdict | VALID | VALID | INVALID, INVALID, INVALID |
| Flags | `LOST_UPDATE` | none | `LOST_UPDATE` in each |
| U1 pods on v2 before `infraUp` | 1 | 1 | 0, 0, 0 |
| U2 replacement pod | 9.05 s, v2 | 9.67 s, v2 | 58.65 s, 60.29 s, 61.38 s, all v2 |
| U3 mixed-version time | 200.9 s | 154.0 s | 119.5 s, 114.4 s, 116.8 s |
| U4 catch-up time | more than 149.9 s, censored | 103.66 s | more than 149.9 s, censored |
| U5 failed-request ratio | 0 | 0 | 0, 0, 0 |

Way A, with Reloader down: the commit changed nothing in the running pods. The
replacement for the deleted pod started with v2 and answered 9.05 s after the
delete. The two other pods served v1 to the end of the run, also after Reloader
was back. The update was lost for them, and for 200.9 s the service answered with
two versions.

Way B, with the Watcher down: the replacement pod also started with v2, after
9.67 s. The two other pods switched to v2 at 313.8 s, which is 103.66 s after
`infraUp` had finished. [Entry 07](07-way-b-gate-deadlock.md#the-watcher-refreshes-when-it-starts)
explains where the 103.66 s come from.

Way C, with Config Server and RabbitMQ down: the commit to GitHub worked. The
trigger is `kubectl exec` into the Config Server pod, which did not exist. It
ended after 60.0 s with exit code 1 and the output `error: timed out waiting for
the condition`. A failed trigger makes a run INVALID, so the campaign repeated the
run twice with the same outcome, and Way C ended with 0 valid runs.

The step executor waits for a step to finish before it starts the next one.
`deletePod` was due at 150 s and started at 181.8 s, 185.3 s and 181.8 s in the
three attempts, when the trigger had returned. The replacement pod gave its first
answer at 240.4 s, 245.6 s and 243.2 s, after `infraUp` at 210 s, and with v2. The
two other pods served v1 to the end.

The Way C numbers in this table come from INVALID runs. They are in `runs.csv` and
are left out of `summary.csv`, where every Way C metric has `n` 0.

After each S3 run the infrastructure was scaled back by the `infraUp` step. The
meta files show `0 -> 1` for every Deployment.

## What I learned

A VALID run can describe bad behaviour. The verdict only says that the
instrument worked. Way B's S2 run, in which every request failed for the last
174 s, is VALID with two flags. The flags are in the `flags` column of `runs.csv`,
and the console does not print them.

A check on one approach can be a result about the protocol. Way C's INVALID runs
in S3 are caused by a rule of the failure table meeting the way Way C's trigger
is built. The data of those runs still describes what Way C did.

Timing between steps is not independent of the approach as long as a step can
block. The 60 s trigger moved `deletePod` by more than 30 s for Way C only.

The fingerprint describes the cluster before the campaign. The image of a run is
in its `cluster.json`.

## Decisions made in this phase

The ten decisions of the plan were recorded in phase 0. During the build, eleven
readings were chosen where the plan's text was open. Each has an entry dated
2026-11-15 in [docs/DECISIONS.md](../../docs/DECISIONS.md).

| Topic | Reading |
|---|---|
| Seeds | Block order seed in the campaign file, bootstrap seed in the protocol file |
| Baseline | Ends at the start of the first step: `t_cs` in S1 and S2, `infraDown` in S3 |
| Reported metrics | The scenario's structure decides: S1 P1 to O5, S2 E1 to E4 and R1, S3 U1 to U5. `NOT_CONVERGED` only where P1 is reported |
| Blocks | Every block contains every approach, so an approach can end with more valid runs than the target |
| Core steps | A failed `infraDown`, `infraUp` or `deletePod` stops the campaign |
| Waiting | Rollouts and the restore wait at most `steadyTimeoutSeconds`, 120 s |
| Infrastructure | Before every run the delivery components of every approach must be up |
| Constants | Instrument limits and fixed workload fields in the protocol file, valid runs and blocks in the campaign file |
| Formula details | "Still served at the end" means an answer intended in the last 1 000 ms. A2 is count times period. A last bucket shorter than the bucket length is left out |
| Saving | Raw data is saved before the campaign stops when the evaluation or the S3 restore fails |
| Committed results | Smoke results are copied to `results/aggregated/`, raw data stays out of git |

## Open points

1. In S3, Way C's trigger fails while its Config Server is down. The plan's
   failure table makes a run with a failed trigger INVALID, so every Way C run of
   S3 is INVALID and the S3 campaign cannot reach its valid runs for Way C.
2. P2 and C1 are empty when the new version never arrives. P1 is censored in that
   case, the two others have no value.
3. probe-service answers `v0` when no configuration reaches it. `v0` is not
   declared by any payload, so the rule on undeclared versions would make such a
   run INVALID.
4. The professor asked for a database. What that means for the experiment is not
   clarified yet.
5. The full campaigns wait for these four points.

## Next

1. Read the code with the [code walkthrough](../code-walkthrough.md) until I can
   explain every metric without it.
2. Clarify the open points with my supervisor.
3. Commit the documentation and the files in `results/aggregated/`.
