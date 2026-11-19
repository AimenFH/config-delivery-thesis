# 07: Why Way B did not recover in S2

*19 November 2026, follow-up to milestone 5*

## Summary

In the S2 smoke run Way B never served the fixed configuration. The cause is now
verified. The Configuration Watcher sends its refresh only to pods that the
Service lists as ready. The readiness gate made all three pods not ready after the
invalid configuration, so the refresh for the fix was sent to no pod.

| | Result |
|---|---|
| Refresh after the bad commit | Sent to 3 pods, 3 times `200 OK` |
| Ready addresses of the Service after that | 0 ready, 3 not ready |
| Refresh after the fix | Service looked up, no request sent, no error logged |
| Pods 3 min 29 s after the fix | 3 of 3 not ready |
| Manual `POST /actuator/refresh` on one pod | Pod valid with v3 and ready 2 s later |

A second finding came out of the same log. The Watcher schedules a refresh for
every labelled ConfigMap when it starts, without any change.

This was a manual reproduction on the kind cluster, without the runner and
without load. I deployed Way B with the gate on. Claude Code then switched on
debug logging in the Watcher, sent the two commits and the manual refresh, and
saved the Watcher's log.

Reference material: [previous entry](06-experiment-protocol.md),
[probe API, the readiness gate](../probe-api.md#the-readiness-gate),
[architecture](../architecture.md), [runbook](../runbook-experiment-1.md).

## The question

[Entry 06](06-experiment-protocol.md#s2-invalid-configuration) left this open: in
the S2 smoke run Way B served v2 from all three pods, every request failed from
185.86 s on, and v3 was never observed although the fix was committed at 210 s.

## What the saved run already showed

Before the reproduction I read the files of the run
`20261115-164716_way-b_b01_a1` again.

| Observation | Source |
|---|---|
| The three pods have the same names and start times before and after the run | `cluster.json`, `podsBefore` and `podsAfter` |
| The only events with a time inside the run are `Readiness probe failed: HTTP probe failed with statuscode: 503`, on all three pods | `cluster.json`, `events` |
| All 8 700 requests intended after 186 s have status 0 and the error `HttpTimeoutException: request timed out` | the NDJSON file |
| v2 was first served at 180.3 s, 90.3 s after the bad commit | entry 06 |

The pods did not crash and no pod answered through the Service. The 90.3 s is the
Watcher's `refreshDelay`. The refresh for the fix, committed at 210 s, was
therefore not due before 300 s, which left 60 s until the end of the run.

Way C recovered in its S2 run with the same image and the same gate. So the
service can leave the invalid state when a refresh reaches it.

## The reproduction

Way B was deployed from `deploy/way-b-volume` with `CONFIG_GATE_ENABLED=true`,
three pods ready, ConfigMap at v1.

At its normal log level the Watcher logs that it schedules a refresh and nothing
about sending it. Debug logging was switched on for the test and removed after it:

```bash
kubectl -n platform set env deployment/config-watcher LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_CLOUD_KUBERNETES=DEBUG
```

The bad commit and the fix are the commit command of the
[Way B descriptor](../../approaches/way-b/approach.json) with the payloads of S2:

```bash
kubectl -n thesis patch configmap demo-config --type merge -p '{"data":{"demo.configVersion":"v2","demo.timeoutMs":"abc"}}'
```

```bash
kubectl -n thesis patch configmap demo-config --type merge -p '{"data":{"demo.configVersion":"v3","demo.timeoutMs":"1000"}}'
```

The ready and not ready addresses of the Service:

```bash
kubectl -n thesis get endpoints probe-service -o jsonpath='ready={.subsets[*].addresses[*].ip}{"\n"}notReady={.subsets[*].notReadyAddresses[*].ip}{"\n"}'
```

The manual refresh, through a port-forward to one pod:

```bash
kubectl -n thesis port-forward pod/<pod-name> 8081:8080
```

```bash
curl -s -X POST localhost:8081/actuator/refresh
```

## What happened

Times are UTC on 19 November 2026. Lines of the Watcher are from its debug log.

| Time | Event |
|---|---|
| 21:55:22.984 | The Watcher has just started and logs `ConfigMap demo-config was added in namespace thesis` |
| 21:55:23.079 | Watcher: `Scheduling remote refresh event to be published for config-map: with appName : probe-service to be published in 90000 milliseconds` |
| 21:55:35.220 | Bad commit. The Watcher logs `ConfigMap demo-config was updated` and schedules a second refresh in 90 000 ms |
| 21:56:53.323 to 21:56:53.742 | The refresh from the start is due. Watcher: `Sending refresh request for probe-service to URI http://10.244.1.5:8080/actuator/refresh`, and the same for `10.244.2.6` and `10.244.3.5` |
| 21:56:54.705 to 21:56:54.968 | Watcher: `returned a 200 OK` for all three |
| 21:57:01 | All three pods show `0/1`. The Service has no ready address and three not ready addresses |
| 21:57:05.226 | The refresh of the bad commit is due. The Watcher looks up the service `probe-service` and logs no `Sending refresh request` line |
| 21:57:19.630 | Fix commit. The Watcher schedules a refresh in 90 000 ms |
| 21:58:49.646 | The refresh of the fix is due. The Watcher looks up the service and logs no `Sending refresh request` line and no error |
| 22:00:48 | All three pods still `0/1`, the Service still has three not ready addresses |
| 22:01:07 | Manual refresh on `probe-service-784f7b9d4c-gh5lw`. Before it, `/actuator/health/readiness` answers 503 and `/config-health` answers `INVALID` with `demo.timeoutMs='abc' is not an integer`. The refresh answers `["demo.configVersion","demo.value","demo.timeoutMs"]`. After it, `/config-health` answers `VALID` with v3 and readiness answers 200 |
| 22:01:09 | That pod shows `1/1` and is the only ready address. The two other pods stay `0/1` |

The pods were polled every 2 s, so 21:57:01 is the first poll in which all three
were not ready.

One mistake in the procedure: the Watcher was restarted for the debug setting
12 s before the bad commit. Its refresh from the start delivered v2 to the pods
78 s after the commit instead of 90 s. The refresh of the bad commit itself came
12 s later and found no ready pod. Next time I wait more than 90 s after a restart
of the Watcher. The result does not depend on this, because the Watcher sent no
request in two cases, at 21:57:05 and at 21:58:49.

## The cause

The Watcher finds the pods of an application through the Service with the
application's name and calls `/actuator/refresh` on each address it gets. While
all pods were ready it got three addresses. While all pods were not ready it sent
no request. It does not log how many addresses it got, so the empty list is my
reading of the missing requests.

With the readiness gate this is a deadlock for Way B:

1. The Watcher refreshes all ready pods with an invalid configuration.
2. The gate makes every pod not ready.
3. The fix is committed and reaches the mounted files.
4. The Watcher sends the next refresh to the ready pods, and there are none.

The pod that got the manual refresh loaded v3 at once, so the fix was already in
its mounted files and only the call was missing.

Way C does not have this problem because its pods receive the refresh event from
RabbitMQ themselves. Whether a pod is ready plays no role there.

A partly invalid state was not tested. At the end of the reproduction one pod was
ready and two were not, and I did not commit another change to see which pods the
Watcher calls then.

## The Watcher refreshes when it starts

When the Watcher starts, it sees every ConfigMap as added and schedules a refresh
for each one with the label `spring.cloud.kubernetes.config`. The log of the
reproduction shows this at 21:55:23. The Watcher pod before it, which ran without
debug logging, logged `will schedule remote refresh based on apps :
[probe-service]` at 21:53:40 during its start.

This fits the S3 smoke run of Way B in [entry 06](06-experiment-protocol.md#s3-infrastructure-outage):

| | Time on 15 November, UTC |
|---|---|
| `infraUp` scales the Watcher from 0 to 1 | 16:19:55.830 |
| Container of the Watcher starts | 16:19:56.816 |
| Watcher logs `Started ConfigurationWatcherApplication` | 16:20:10.435 |
| The two other pods switch to v2 (313.8 s in the run) | 16:21:39.620 |

The pods switched 89.2 s after the `Started` line. In the reproduction the
`added` line came 2.2 s before the `Started` line. So the catch-up of 103.66 s in
that run is the start of the Watcher, about 12 s until it first sees the
ConfigMaps, then the 90 s `refreshDelay` and the refresh itself. Way B catches up
after the outage because the Watcher refreshes at every start. I have no sign
that it notices the change it missed.

I read the two Watcher times of 15 November from the log of the previous
container on 19 November. That pod was replaced during the reproduction and the
log was not saved.

## What I learned

A readiness gate and a refresh that is pushed to ready pods do not fit together.
The gate removes exactly the pods that need the next refresh.

Under the current setup R1 of Way B in S2 is censored in every run. Even without
the deadlock the earliest recovery would be 90 s after the fix.

The Watcher needs debug logging to show what it sends. At its normal level a
refresh that reaches three pods and a refresh that reaches none look the same.

A restart of the Watcher is a delivery event for Way B. A restart less than 90 s
before a commit shortens the measured propagation time.

## Open points

1. Keep the Watcher as it is and report the deadlock as a result for Way B, or
   let the Watcher call pods that are not ready. Claude Code named the discovery
   property `spring.cloud.kubernetes.discovery.include-not-ready-addresses` from
   memory. I have not looked it up for version 3.1.6 and not tested it with the
   Watcher. The choice needs an entry in
   [docs/DECISIONS.md](../../docs/DECISIONS.md).
2. Whether U4 of Way B in S3 should be described as the Watcher's refresh at
   start in the thesis text.
3. The four [open points of entry 06](06-experiment-protocol.md#open-points) are
   unchanged.

## Evidence

The debug log of the Watcher for the whole reproduction is in
`results/raw/20261119_way-b-gate-diagnosis/watcher.log`. The folder `results/raw/`
is not in git.

After the reproduction the debug setting was removed and `kubectl diff -f
deploy/platform/watcher.yaml` showed no difference. The `thesis` namespace was
left with one ready pod on v3 and two not ready pods. The next campaign deletes
it.
