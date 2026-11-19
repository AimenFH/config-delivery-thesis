# 08: Three open points closed in the runner

*19 November 2026, follow-up to milestone 5*

## Summary

Three of the four [open points of entry 06](06-experiment-protocol.md#open-points)
are now decided and built into the runner. The fourth, the database, is unchanged.

| Open point of entry 06 | Change |
|---|---|
| 1. Way C's trigger fails in S3, every run INVALID | A trigger that fails between `infraDown` and `infraUp` sets the flag `TRIGGER_FAILED`, the run stays VALID. Every trigger gets at most 15 s |
| 2. P2 and C1 empty when v2 never arrives | P2 and C1 are censored in the same runs as P1 |
| 3. `v0` is not declared by any payload | `v0` is named in the protocol file. After the baseline it sets the flag `UNCONFIGURED`, the run stays VALID |

Claude Code wrote the code and the tests in one session, on my request. I still
have to read them. The reasons, with the alternatives that were rejected, are the
three entries of 2026-11-19 in [docs/DECISIONS.md](../../docs/DECISIONS.md). My
supervisor has not seen these decisions yet.

The check had three parts: the unit tests, a new evaluation of the saved smoke
data, and one new S3 smoke campaign on the cluster. In that campaign the Way C run
is VALID, its trigger was stopped after 15.04 s, and `deletePod` started at
150.00 s.

Reference material: [runner](../runner.md#verdict-and-flags),
[runbook](../runbook-experiment-1.md), [code walkthrough](../code-walkthrough.md),
[previous entry](07-way-b-gate-deadlock.md).

## What changed

| Part | Files |
|---|---|
| Two new constants, `unconfiguredVersion` and `triggerTimeoutSeconds` | `protocol/protocol-v1.json`, `Protocol` |
| Two new flags, `UNCONFIGURED` and `TRIGGER_FAILED` | `RunMetrics` |
| Verdict rules for `v0` and for a failed trigger, and the two flags | `Evaluator` |
| P2 censored when v2 never appeared | `Propagation` |
| C1 censored when P1 is, `C1_max` empty in that case | `Consistency` |
| A command can be stopped after a timeout, exit code 124 | `ChangeTrigger`, `CommandRunner` |
| Only the trigger runs with the timeout | `CommandAdapter` |

The scenario files, the approach descriptors, the manifests and probe-service are
unchanged.

`runs.csv` keeps its columns. `summary.csv` has two more `count` rows per
approach, one for each new flag.

## How it was checked

### Unit tests

```bash
(cd tools/experiment-runner && mvn test | grep -E 'Tests run:|BUILD|ERROR')
```

The last lines are `Tests run: 498, Failures: 0, Errors: 0, Skipped: 0` and
`BUILD SUCCESS`. Before the change there were 477 tests, and all of them passed
on the same day. The 21 new ones are in `EvaluatorTest`, `PropagationTest`,
`ConsistencyTest`, `ProtocolTest`, `CommandAdapterTest` and the new
`ChangeTriggerTest`.

Expected values in existing tests changed for two reasons:

- `summary.csv` has two more count rows per approach. Three tests that count its
  lines expect 6 or 4 more, and the test that lists the counts of an approach
  names the two new flags.
- A run with `NOT_CONVERGED` now has a censored C1, and a run without v2 also a
  censored P2. `EvaluateCampaignTest` expects `>144980,` followed by an empty
  `C1_max` where it expected `144980,`. `EvaluatorTest` and `PropagationTest`
  expect censored values where they expected empty ones.

`ChangeTriggerTest` starts real `sh` commands. One of them sleeps 30 s and is
stopped after 300 ms.

### The saved smoke data

The new rules should change only the results they were meant to change. To see
that, Claude Code copied the three raw smoke folders to a temporary folder and ran
`--evaluate` on the copies. The folders in `results/` were not touched.

| Campaign | `runs.csv` before and after |
|---|---|
| S1 smoke, 6 runs | Identical |
| S2 smoke, 3 runs | Identical |
| S3 smoke, 5 runs | The three Way C runs changed, the two others are identical |

The three Way C runs of S3:

| | Before | After |
|---|---|---|
| Verdict | INVALID | VALID |
| Reason | `trigger after the commit of v2 failed with exit code 1` | none |
| Flags | `LOST_UPDATE` | `LOST_UPDATE TRIGGER_FAILED` |
| U1 to U5 | unchanged | unchanged |

S3 smoke now has 5 valid runs of 5. The saved `campaign.json` files have no
`unconfiguredVersion`, so these folders were evaluated with the old rule for
`v0`. No run of the three campaigns contains a `v0` answer.

### The S3 smoke campaign on the cluster

```bash
caffeinate -dims java -jar tools/experiment-runner/target/experiment-runner.jar --campaign campaigns/s3-smoke.json
```

Campaign `20261119-232500_s3-smoke`, from 23:25 to 00:09, one block in the order
`[way-a, way-b, way-c]`. It reached one valid run per approach with 5 runs. The
fingerprint says commit `f5a74e9` and dirty, because the changes of this entry
were not committed when it started.

| Run | Verdict | Reason or flags |
|---|---|---|
| Way A, attempt 1 | INVALID | `schedule lag over 100 ms for 10 requests`, highest 260.0 ms |
| Way A, attempt 2 | VALID | `LOST_UPDATE` |
| Way B, attempt 1 | INVALID | `schedule lag over 100 ms for 1 requests`, highest 101.6 ms |
| Way B, attempt 2 | VALID | none |
| Way C, attempt 1 | VALID | `LOST_UPDATE`, `TRIGGER_FAILED` |

The steps of the Way C run, from its `meta.json`:

| Step | Due | Started | Finished | Exit code |
|---|---|---|---|---|
| `infraDown` | 90 s | 90.00 s | 90.18 s | 0 |
| `commit` | 120 s | 120.01 s | 121.91 s | 0 |
| trigger | after the commit | 121.91 s | 136.95 s | 124, `timed out after 15000 ms` |
| `deletePod` | 150 s | 150.00 s | 150.10 s | 0 |
| `infraUp` | 210 s | 210.00 s | 210.11 s | 0 |

The trigger ran for 15.04 s. On 15 November it ran for 60.0 s and `deletePod`
started between 181.8 s and 185.3 s. In all five runs of the new campaign every
step started within 0.02 s of its time.

The metrics of the three valid runs:

| Metric | Way A | Way B | Way C |
|---|---|---|---|
| U1 pods on v2 before `infraUp` | 1 | 1 | 0 |
| U2 replacement pod | 10.81 s, v2 | 9.75 s, v2 | 101.85 s, v2 |
| U3 mixed-version time | 199.1 s | 154.1 s | 108.1 s |
| U4 catch-up time | more than 149.96 s, censored | 103.83 s | more than 149.89 s, censored |
| U5 failed-request ratio | 0 | 0 | 0 |

Way A and Way B are close to their runs of 15 November (U2 9.05 s and 9.67 s, U4
censored and 103.66 s).

In the raw data of the Way C run the replacement pod gave its first answer at
251.9 s, with v2. That is 41.8 s after `infraUp` had finished. On 15 November it
answered at 240.4 s, 245.6 s and 243.2 s, also after `infraUp`. U2 was 58.6 to
61.4 s then because `deletePod` was late. With `deletePod` on time it is 101.85 s.
The two other pods served v1 to the end of the run, and none of the 18 001
requests failed. No pod answered `v0`.

Two first attempts were INVALID because requests started more than 100 ms late.
The S3 smoke campaign of 15 November had no such run. The machine was in use
during the campaign: Claude Code ran commands in the repository while it was
running. The cause of the lag was not checked.

The small files of the campaign are in
`results/aggregated/20261119-232500_s3-smoke/`.

## What was not checked

- The cluster run is one run of Way C. It shows that the timeout works, it is not
  a result.
- The three Way C runs of 15 November ran without the timeout. Their `deletePod`
  started 32 to 35 s late, so they are VALID under the new rule and still not
  comparable with new runs.
- No approach has produced `v0` so far. The rule has only run in unit tests.
- `results/aggregated/20261115-170505_s3-smoke/` still holds the `runs.csv` and
  `summary.csv` of 15 November, with Way C as INVALID.
- S1 and S2 were not run again on the cluster. Their saved data evaluates to the
  same `runs.csv` as before.

## What this showed

Open point 2 was wider than its text. The same problem existed when v2 arrived and
v1 was still served at the end of `H`: C1 was written as an exact number for a
window that had not closed. The rule now covers both cases.

Making the Way C runs VALID was not enough for open point 1. A VALID run whose
`deletePod` is 32 s late would have gone into the statistics next to runs that
were on time. The timeout belongs to the same decision.

No rule checks that a step starts on time. A slow `commit`, or a slow `infraDown`,
can still move the steps after it, and the run stays VALID.

## Open points

1. The protocol file still says `protocolVersion` 1 although two constants were
   added and three rules of the plan changed. I have to decide whether the number
   goes to 2 now or together with the additions that are still planned.
2. My supervisor has not seen the three decisions of 2026-11-19.
3. A late step makes no run INVALID and sets no flag.
4. Two of five runs of the new S3 smoke campaign were INVALID for schedule lag.
   If that happens in a quiet campaign too, the cause has to be found before the
   full campaigns.
5. The database the professor asked for is not clarified.
6. The [open points of entry 07](07-way-b-gate-deadlock.md#open-points) on Way B
   are unchanged.

## Next

1. Read the changed classes and their tests.
2. Run the S1 smoke campaign as a check that nothing else moved.
3. Keep the machine quiet during a campaign and see whether the schedule lag
   comes back.
