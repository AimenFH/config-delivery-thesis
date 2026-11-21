# 11: Two scenarios from incident reports

*21 November 2026, milestone 8*

## Summary

I wanted the scenarios to rest on what went wrong in practice, so I compared
them with public incident reports of 2025 and 2026. S1, S2 and S3 each match a
pattern from those reports. Two patterns had no scenario, and there are now two
more: S4 for changes that follow each other closely and S5 for a pod that starts
while an invalid configuration is still in the source.

| | Result |
|---|---|
| S4 rapid successive changes | Scenario file, two campaign files, metrics Q1 to Q7 |
| S5 invalid configuration and pod restart | Scenario file, two campaign files, the new step `deleteReadyPod`, metrics of S2 |
| Unit tests of the runner | 541, all pass. 30 are new: 21 for S4 and 9 for S5 |
| S4 smoke campaign | 4 runs with four approaches, all VALID, no flags |
| S5 smoke campaign | 4 runs with four approaches, all VALID |
| Way D through the runner | First time. 2 runs, both VALID at the first attempt |
| Fairness rule | Written down: an approach is tested as teams run it, nothing is built for it |

Claude Code searched and read the incident reports, wrote the scenario files, the
code and the tests, and ran the two smoke campaigns. I reviewed the changes and
committed them (`7466f59`) before the campaigns started.

The reasons are in the three entries of 2026-11-21 at the end of
[docs/DECISIONS.md](../../docs/DECISIONS.md). The scenarios are described in the
[architecture document](../architecture.md#the-five-scenarios).
[Previous entry](10-way-d-argo-cd-and-helm.md).

## From incident reports to scenarios

| Pattern | Incident reports | How they answered | Scenario |
|---|---|---|---|
| A bad configuration reaches every replica at once | [Cloudflare, 5 December 2025](https://blog.cloudflare.com/5-december-2025-outage/). [Google Cloud, 12 June 2025](https://status.cloud.google.com/incidents/ow5i3PPK96RduMcb1SsW) | Rollout in stages with health checks, keeping the last good configuration | S2 |
| Replicas on different versions | [Cloudflare, 18 November 2025](https://blog.cloudflare.com/18-november-2025-outage/): a file generated every five minutes was sometimes good and sometimes bad | Checking generated configuration like user input | S1 |
| The delivery components are down | [Roblox, October 2021](https://about.roblox.com/newsroom/2022/01/roblox-return-to-service-10-28-10-31-2021). Google Cloud, 12 June 2025: restarting tasks overloaded the database | A second data centre, randomized backoff | S3 |
| An older change and a newer one are applied at the same time | AWS DynamoDB, October 2025, which I know from the [InfoQ summary](https://www.infoq.com/news/2025/11/aws-dynamodb-outage-postmortem) only | Automation switched off, rate limits | S4, new |
| A stopped rollout leaves the bad value at the source | [Azure Front Door, 29 October 2025](https://techcommunity.microsoft.com/blog/azurenetworkingblog/-/4479416). [Cloudflare, 20 February 2026](https://blog.cloudflare.com/cloudflare-outage-february-20-2026/) | Revert to any earlier snapshot | S5, new |

The reports come from very large operators, and only the worst cases are
published. I use them to say why a scenario exists. They say nothing about what
the result should be with three replicas.

The Way B deadlock of [entry 07](07-way-b-gate-deadlock.md) fits a pattern of its
own in these reports: the fix has to travel through the part that is broken.

## What was built

| Part | What | Files |
|---|---|---|
| S4 | Commit v2 at 0 s, commit v3 at 10 s, gate off | `scenarios/s4-rapid-changes.json`, `campaigns/s4.json`, `campaigns/s4-smoke.json` |
| S4 metrics | Q1 to Q7. Q2 and Q3 share one sweep with U3 | `SuccessiveChanges`, `InfrastructureOutage`, `Evaluator`, `MetricId`, `Scenario` |
| S5 | Commit v2 with `timeoutMs` `abc` at 0 s, `deleteReadyPod` at 60 s, commit v3 at 120 s, gate on | `scenarios/s5-bad-config-restart.json`, `campaigns/s5.json`, `campaigns/s5-smoke.json` |
| S5 step | `deleteReadyPod` deletes the first pod by name among the ready pods | `ClusterControl`, `StepExecutor`, `Scenario`, `LokiExport` |

S5 needs no new metric. It reports E1 to E4 and R1, so each run can be put next
to an S2 run.

## What the smoke campaigns showed

Both campaigns ran on 21 November on a clean working tree at commit `7466f59`,
with the block order `[way-d, way-b, way-a, way-c]`. Docker had 10 CPUs and
7.7 GiB of memory. S4 ran from 14:48 to 15:17 and S5 from 15:17 to 15:52. Each
value below is from one run. They show that the scenarios work. They are not
results.

### S4

| | Way A | Way B | Way C | Way D |
|---|---|---|---|---|
| Q1 final convergence time | 30.3 s | 80.5 s | 3.7 s | 31.1 s |
| Q2 mixed-version time | 31.0 s | 0.1 s | 2.0 s | 19.9 s |
| Q3 most versions at once | 3 | 2 | 2 | 2 |
| Q4 pods that answered v2 | 1 | 0 | 3 | 0 |
| Q4 answers with v2 | 505 | 0 | 464 | 0 |
| Q5 pod regressions | 0 | 0 | 0 | 0 |
| Q6 failed-request ratio | 0 | 0 | 0 | 0 |
| Q7 commit gap | 10.0 s | 10.0 s | 10.1 s | 9.8 s |

- Every approach ended on v3. No pod went back to an older version.
- Ways B and D never served v2. The second commit was in the source before they
  had delivered the first, and they went from v1 to v3.
- Way A served three versions at the same time. The second rollout started while
  the first was running.
- Way C delivered the two changes one after the other. All three pods answered
  v2 for a short time.
- The gap was 10 s for every approach. The commit of Way C took 1.8 s and its
  trigger ended 2.7 s after that, so the second commit started on time.

### S5

| | Way A | Way B | Way C | Way D |
|---|---|---|---|---|
| Flags | none | `FAILED_REQUESTS`, `NOT_RECOVERED` | `FAILED_REQUESTS`, `LOW_RESOLUTION` | none |
| E1 exposed replicas | 0 | 2 | 3 | 0 |
| E2 exposed requests | 0 | 263 | 355 | 0 |
| E3 failed-request ratio, bad commit to fix | 0 | 0.202 | 0.896 | 0 |
| E3 longest outage, bad commit to fix | 0 | 24.2 s | 107.5 s | 0 |
| E4 lowest serving replicas | 2 | 0 | 0 | 2 |
| R1 recovery time | 34.6 s | more than 149.9 s, censored | 5.1 s | 33.5 s |

The S2 smoke run of [entry 06](06-experiment-protocol.md#s2-invalid-configuration)
had E4 3 and R1 28.3 s for Way A.

- In Ways A and D the deleted pod was one of the three pods of the baseline. Its
  replacement read the invalid value and did not become ready, so two replicas
  served until the fix. No request failed. The state that looked safe in S2 lost
  a third of its capacity with one pod start.
- Way B did not recover, as in S2. Two replicas were exposed where S2 had three.
  The replacement pod never became ready and so never answered with v2.
- Way C lost all replicas within seconds and recovered 5.1 s after the fix, as in
  S2.
- `deleteReadyPod` started at 60.0 s in all four runs.

## What broke, and what I had wrong

1. `deletePod` would have made S5 random. It deletes the first pod by name, ready
   or not. During a stopped rollout of Way A or Way D there are four pods, and the
   name order of two ReplicaSets follows their hashes. Claude Code found this by
   reading `ClusterControl` before the first run. S5 uses the new step
   `deleteReadyPod`, and `deletePod` and S3 are unchanged.
2. Docker Desktop was not running when the campaigns were to start, so the
   cluster did not answer. After the start of Docker the four nodes were `Ready`
   within seconds and all seven Deployments in `platform` were up.
3. The expectation for Way C in S4 was wrong. Claude Code expected its commit to
   push the second commit back. Commit and trigger together took 4.5 s, well
   inside the gap.
4. At first S4 was going to need protocol version 2. The plan names further
   scenario files on the same core as the way to cover more cases, and S4 and S5
   change nothing in S1 to S3, so both stay in version 1 for now. See open
   point 1.

## What was decided on the way

- A fairness rule. An approach is measured in the configuration that teams
  commonly run. What the mechanism does by itself is a result, and what I would
  have to build for it is not part of the test. Ways A and D stage a change
  because they restart pods, Ways B and C do not, and that difference stays.
- Rollback is the same for all approaches: a commit of good values, which is the
  v3 commit of S2 and S5. What each approach would need for an automatic rollback
  goes into the thesis text and is not measured.
- Realism comes before fairness. The official Watcher image stays, although it
  runs emulated on arm64. GitHub stays the source of Ways C and D.
- The setup stays on kind. The final campaigns run on another laptop with 48 GB
  of memory. After them the test runs once on Google Kubernetes Engine.

## What was not checked

- More than one run. Every number above is from a single run.
- S5 for Way D against S2. Way D has no S2 run yet.
- The comparison of S5 with S2 for Way A uses a run of 15 November, made before
  Argo CD ran in the background.
- S1, S2 and S3 with four approaches through the runner. Way D has run in S4 and
  S5 only.
- Whether the monitoring stack was up during the two campaigns, and whether the
  Grafana dashboards show the Q columns.
- Another gap in S4. 10 s is one value.
- The readiness that `deleteReadyPod` reads can have changed by the time the pod
  is deleted. In these four runs it had not.
- The code walkthrough has no worked examples for Q1 to Q7, and the experiment
  runbook has no section for S4 and S5.

## Open points

1. The protocol file has four approaches and five scenarios and still says
   `protocolVersion` 1. This is open point 1 of
   [entry 10](10-way-d-argo-cd-and-helm.md#open-points), now larger.
2. A second gate mode in probe-service that keeps the last good values where the
   gate now makes the pod unready. Cloudflare, Azure and Google all moved to this
   after their outages. It needs a new image, so the decision has to come before
   the full campaigns.
3. The full campaigns get longer. With four approaches: about 8.9 hours for S1,
   5.8 hours each for S2, S3 and S5, and 4.5 hours for S4, together about 31
   hours.
4. The final campaigns run on another machine. The kubelet delay, the settle
   time, the `preStop` delay of Way A and the pilot blocks have to be measured
   there again. Numbers from this MacBook cannot be mixed with them.
5. How large the run on Google Kubernetes Engine is. My starting point is S1
   with about 5 runs per approach and the triggers of today.
6. The smoke campaigns of S1, S2 and S3 with Way D. This is what is left of open
   point 2 of entry 10.
7. My supervisor has not seen the fairness rule, S4 or S5. The question of the
   database is still open.
8. Way B still does not recover in S2 and S5, see
   [entry 07](07-way-b-gate-deadlock.md#open-points).
