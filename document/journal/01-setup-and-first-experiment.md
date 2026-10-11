# 01: Setup and first experiment

*September to October 2026, milestone 1*

## Summary

I built a small Spring Boot service that reports which configuration version it is
running, packaged it as a container image, and started three replicas on a local
Kubernetes cluster with three worker nodes. Then I changed the configuration and
restarted the service while sending requests to it.

For about 18 seconds the same service answered with two different configuration
versions, and nothing failed or raised an alert. That window is what the thesis is
about. Seeing it live, in my own terminal, made the research question feel much more
concrete than it had on paper.

Reference material lives in separate documents:
[architecture](../architecture.md),
[API](../probe-api.md),
[runbook](../runbook-way-a.md) and
[decisions](../../docs/DECISIONS.md).

## Environment

| Component | Version |
|---|---|
| Kubernetes (kind node image) | v1.37.0 |
| kind | v0.33.0 |
| Docker | 29.8, 10 CPUs and 8 GB assigned |
| Spring Boot / Java target | 3.3.4 / 21 |
| Machine | MacBook, Apple Silicon (arm64) |

## What I did

1. I made the probe service observable. Every response carries the config version,
   the pod name and the process start time. The start time will later tell a
   restart apart from a refresh.
2. I fixed the validation endpoint. `timeoutMs` used to be read as a number, so a
   broken value like `"abc"` crashed the request with a 500 before my validation
   could run. It is now read as text and checked by hand, and returns a clean
   `503 INVALID`. In the invalid-configuration scenario I want to measure when a
   replica detects the problem, and an accidental crash would hide that.
3. I packaged it with a short Dockerfile on `eclipse-temurin:21-jre`. The image is
   about 147 MB.
4. I created a kind cluster with one control plane and three workers, with port
   30080 forwarded to my machine.
5. I deployed Way A: three replicas, one per node, configuration injected as
   environment variables, reachable through a NodePort.
6. I ran the first experiment by hand with a curl loop.

## What I observed

Changing the ConfigMap from `v1` to `v2` had no visible effect at first. Every replica
kept answering `v1`, which is what I expected, because a process never re-reads its
environment.

After `kubectl rollout restart` the answers started to mix:

```
"configVersion":"v1","pod":"probe-service-584fd7d4b-lz42q"
"configVersion":"v2","pod":"probe-service-7f57b846f8-sv44t"
"configVersion":"v2","pod":"probe-service-7f57b846f8-sv44t"
"configVersion":"v1","pod":"probe-service-584fd7d4b-lz42q"
"configVersion":"v2","pod":"probe-service-7f57b846f8-sbmg2"
```

The middle part of each pod name is a hash of the pod template, so old pods
(`584fd7d4b`) and new pods (`7f57b846f8`) can be told apart from the name alone.

From the Kubernetes events I reconstructed this timeline:

| Time | Event |
|---|---|
| 0 s | First new pod starts |
| ~9 s | It is ready; first old pod stopped; second new pod starts |
| ~18 s | Second old pod stopped; third new pod starts |
| ~27 s | Last old pod stopped |

Each new pod needed about 9 seconds to start and pass its readiness probe. From the
first ready `v2` pod to the last `v1` pod, about 18 seconds passed. With more
replicas the window would grow, because the pods are replaced one at a time.

## What went wrong

### The rollout that never finished

My first restart got stuck. One new pod stayed `Pending`, and the scheduler reported:

> 0/4 nodes are available: 1 node(s) had untolerated taint(s),
> 3 node(s) didn't match pod topology spread constraints.

The rule that spreads replicas across nodes also counted the control-plane node,
which can never run application pods. Measured against that empty node, every worker
looked overloaded, so the scheduler rejected all of them. One line fixed it:
`nodeTaintsPolicy: Honor`.

A user would have noticed nothing. The old pods kept serving `v1`, no request
failed, and no alert fired, so the configuration change silently never arrived.
Way A failed safely here, and I could only tell because I was looking at the pods.

### Two new pods on one node

After that fix, two new pods ended up on `thesis-worker3` and none on
`thesis-worker2`. The spread rule had counted the old pods as well, even though they
were about to be stopped. With `matchLabelKeys: [pod-template-hash]` it only counts
pods of the same revision.

### Smaller stumbles

`docker run` failed with *"pull access denied"*. It looked like a login problem, but
I simply had not built the image yet, so Docker searched Docker Hub for it.

`kubectl apply` did not reset my patched ConfigMap back to `v1`. Apply only changes
fields that differ from what was last applied, so deleting the namespace is the
reliable way to reset.

A stray tab in `application.yml` would have stopped the service from starting,
because YAML does not allow tabs for indentation. I caught it before committing.

## What I learned

Three replicas and one restart were enough to produce 18 seconds of mixed answers,
so the problem is easy to reproduce. What worries me more is that neither the mixed
answers nor the stuck rollout produced a single error.

The curl loop could show the effect but could not measure it. It printed no
timestamps, it waited for each answer before sending the next, and `grep` silently
dropped failed answers. The experiment runner will need a fixed request rate and its
own clock.

Kubernetes balances connections rather than individual requests. A client that keeps
its connection open keeps reaching the same pod and would miss the window
completely, so the runner has to open a new connection for every request.

Both scheduling problems came from default settings that looked harmless until a
rollout actually ran.

## Decisions made in this phase

| Topic | Decision |
|---|---|
| Argo CD | Analysed in the compatibility matrix and decision model, not measured |
| Where experiments run | Local kind cluster, one control plane and three workers |
| Optional tools | Only the Angular dashboard stays in scope |
| Image build | Plain Dockerfile instead of buildpacks |
| Possible fourth variant | Way A with Stakater Reloader, pending supervisor approval |
| Prometheus and Grafana | Planned, postponed until after Way B |

## Next

1. Way B: fix the ConfigMap keys, deploy the Configuration Watcher with its
   permissions, and watch the configuration change without a restart.
2. Monitoring: add a config-version metric, Prometheus and Grafana, and test whether
   a standard dashboard can show the window at all.
3. Experiment runner: constant request rate, one clock, automatic window detection.
