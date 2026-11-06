# 04: Experiment runner, stages 3 and 4

*November 2026, milestone 3 (complete)*

## Summary

The runner now applies the configuration change itself and computes the
inconsistency window from its own observations. It probes for a warm-up period,
runs a `kubectl patch` as a child process, keeps probing, and then reports
propagation, window and convergence with an upper bound for each window. The first
real runs gave the first comparable numbers of the thesis:

| | Way A (Reloader, rolling restart) | Way B (Watcher, refresh) |
|---|---|---|
| Propagation | 7.8 s | 90.3 s |
| Window | 13.9 s (at most 15.9 s) | 0 ms (at most 167 ms) |
| Convergence | 21.7 s | 90.3 s |
| Failed requests | 41 of 6 251 | 0 of 7 750 |

Way B is slower to start but switches all replicas almost at once. Way A starts
sooner, serves two versions for about 14 seconds, and drops requests while old pods
shut down. A second Way A run gave almost the same numbers (window 13.9 s, 33
failed requests). Two runs are not enough to report a spread yet.

Usage is in [document/runner.md](../runner.md), the definitions in
[DECISIONS](../../docs/DECISIONS.md).

Reference material: [architecture](../architecture.md),
[previous entry](03-experiment-runner.md).

## Environment

Unchanged from [entry 01](01-setup-and-first-experiment.md#environment). JUnit 5.10
and Surefire 3.5 were added to the runner for the detector tests.

## What I built

| Class | Job |
|---|---|
| `ChangeTrigger` | Runs the change command and records when it started and finished on the runner's clock |
| `WindowDetector` | Finds old and new version, computes the window and its upper bound, propagation, convergence and failed requests, and checks the run |
| `WindowDetectorTest` | Eight cases built from made-up observations, including a rolling restart where pods are replaced |

`Runner` gained `--warmup` and takes everything after a bare `--` as the change
command. The change fires on a delayed executor while the load generator keeps its
schedule. A failed change command, a missing switch, a pod still on the old version
at the end, or a pod going back to the old version all make the run invalid.

I wrote the tests before running the detector on real data. All eight passed on the
first build. I then checked the boundaries of the Way B run by hand against the
NDJSON file, per pod, and they matched.

## What I observed

### A first run with the wrong key

The first stage 3 run patched `configVersion` while Way B was deployed. Way B reads
`demo.configVersion`, so the patch added a key nothing reads. All 7 751 answers
stayed on `v3`, and the run still ended with "Run VALID", because at that point only
the coverage was checked. The detector now rejects such a run with "new version never
observed". The stray key also triggered a watcher refresh 90 s later, so I removed it
and waited for that refresh to pass before the real run.

### Way B

Propagation was 90 256 ms after the patch: the `refreshDelay` of 90 s plus about
250 ms. All three pods switched between 95 437 ms and 95 604 ms, no pod restarted
(`startedAt` stayed the same), and no pod went back to the old version.

| Pod | Last `v3` | First `v4` |
|---|---|---|
| `5pswb` | 95 436.5 | 95 527.4 |
| `fgjvt` | 95 500.9 | 95 600.3 |
| `cqg4t` | 95 503.0 | 95 603.9 |

The last old answer came 24 ms before the first new one, so the measured window is 0.
The overlap could have been up to 167 ms without the runner seeing it. At 50 req/s,
the Way B window is below the resolution of the instrument.

### Way A

The first new pod answered 7.8 s after the patch. Each new pod was first seen 30 to
80 ms after the old pod it replaced was last seen, and the three handovers were
about 7 s apart:

| Old pod last answer | New pod first answer |
|---|---|
| 13 055 ms | 13 088 ms |
| 19 977 ms | 20 048 ms |
| 27 005 ms | 27 085 ms |

The window ran from the first new answer at 13.1 s to the last old answer at 27.0 s.

The 41 failed requests came in three bursts of 12 to 15, each arriving about 2.2 s
after an old pod's last successful answer. The third burst ended at 30.0 s, after
convergence plus the 1 s gap limit I had first used to count failures, so I changed
the detector to count every failure after the change.

All failures were recorded as `CompletionException`, which is only the wrapper. I
changed `ProbeClient` to record the cause and its message, and ran Way A again:

| Old pod `Killing` event (UTC) | Old pod last answer | Failed requests sent |
|---|---|---|
| 21:44:22 | 21:44:22.067 | 21:44:22.445, 14 requests in 0.76 s |
| 21:44:29 | 21:44:29.247 | 21:44:29.381, 9 requests in 0.70 s |
| 21:44:36 | 21:44:36.087 | 21:44:36.201, 10 requests in 0.76 s |

All 33 failures were `HttpTimeoutException: request timed out`, and each took between
1 999 and 2 004 ms, which is the runner's timeout. They were sent in the 0.7 s after
an old pod was told to stop, not 2 s later as their receive times suggested. About a
third of the requests in that time failed, which fits one of three endpoints no
longer answering. This is consistent with kube-proxy still routing new connections
to a pod for a short time after it started shutting down. Connect and request
timeout are both 2 s, so the data does not show whether those connections were
accepted.

### Way A with a shutdown delay

I added a `preStop` sleep of 5 s to the Way A container and ran the same change
twice.

| | Without `preStop` | With `preStop` |
|---|---|---|
| Failed requests | 41, 33 | 0, 0 |
| Window | 13.9 s, 13.9 s | 14.9 s, 16.9 s |
| Old pod still answering after its replacement appears | 0 ms | 0.6 to 1.0 s |

The requests that used to hang now reach a pod that still answers, with the old
version. The failures are gone, and the window is longer by up to about 1 s per
handover. Two runs per variant are not enough to say how much of the difference
is spread between runs.

### Switching between Ways leaves traces

When I switched from Way A back to Way B with `kubectl apply`, Reloader saw the
ConfigMap change while the Deployment still had its annotation, and added
`STAKATER_DEMO_CONFIG_CONFIGMAP` to the Way B pods. `kubectl apply` does not
remove a field it did not write. I now delete the Deployment before applying
the other Way.

## What I learned

The instrument had the same blind spot the thesis is about: a change that is
accepted by the API server and never reaches the application looks like success
unless something checks for it. The check has to be on the observed version, not
on the exit code of `kubectl`.

A window of 0 only means something together with its upper bound. For Way B that
bound comes from the request rate of the runner.

In Way A the failures come after the old version is already gone, so a failure
count limited by convergence misses them. The runner now counts failures up to the
end of the run.

## Decisions made in this phase

| Topic | Decision |
|---|---|
| Change trigger | The runner runs the command after `--` as a child process after `--warmup` seconds and records start and end |
| Window definition | Last old answer minus first new answer on `receivedAt`, at least 0, with an upper bound from the unobserved time on the boundary pods |
| Validity | Mixed warm-up, missing switch, old version at the end, or a regression make a run invalid |
| Failed requests | Counted from the change to the end of the run |
| Way A shutdown | `preStop` sleep of 5 s, so no requests fail while old pods stop |

Recorded in full in [docs/DECISIONS.md](../../docs/DECISIONS.md).

## Next

1. Repeat both Way A variants often enough to see whether the longer window
   with `preStop` is more than spread between runs.
2. Repeat both Ways often enough to report a spread instead of single values.
3. Finish the move of both triggers into the `platform` namespace and measure the
   kubelet delay before tuning `refreshDelay`.
