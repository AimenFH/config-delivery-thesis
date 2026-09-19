# Configuration Delivery in Replicated Spring Boot Microservices on Kubernetes

Master's thesis project (50 ECTS, Software Engineering).
All code in this repository is written from scratch for this thesis.

## The problem

A configuration change is not atomic. A service running as several replicas does not
switch to the new value at the same moment across all of them. During propagation,
replicas of the same service answer requests with different values.

Nothing fails. No error is raised. Monitoring stays green. The system is simply
inconsistent with itself for a while — and today that window is neither measured
nor observable.

## The question

> Under which technical and organisational conditions should a team select which
> combination of configuration source, delivery mechanism and activation strategy
> for replicated Spring Boot services on Kubernetes?

The goal is **not** to declare a winner. The goal is a decision model linking
measured properties to context factors: team size, release frequency, compliance
requirements, operating model.

## The three dimensions

Configuration is not one choice but three independent ones:

| Source | Delivery | Activation |
|---|---|---|
| ConfigMap | kubectl / Helm | rolling restart |
| Git repository | Argo CD | in-process reload |
| Config Server | CI pipeline | watch event |
| External store | operator | none (stays stale) |

Most combinations are technically invalid. Deriving and justifying which ones are
valid is part of the work, not an assumption. Examples of invalid combinations:

- **env var + runtime reload** — a process reads its environment once at `exec()`;
  there is no API to change it afterwards
- **subPath mount + file reload** — the kubelet never updates a `subPath` mount
- **Argo CD + no activation trigger** — the configuration lands in the cluster and
  nothing activates it

## What gets measured

| Approach | Source / activation | Extra components |
|---|---|---|
| **A** | ConfigMap as env var → rolling restart | none |
| **B** | ConfigMap as volume → in-process reload | Configuration Watcher |
| **C** | Config Server → bus refresh | Config Server, RabbitMQ (bus only) |

Argo CD, AWS AppConfig and Azure App Configuration are analysed against the feature
model but not measured — their behaviour depends on provider-internal mechanisms and
is not reproducible by third parties.

### Scenarios

1. Valid configuration change under sustained load
2. Invalid configuration change — detection time and blast radius
3. Rollback after a harmful change
4. Scale-out during a change — a new replica may remain permanently inconsistent

3 approaches × 4 scenarios × 15 repetitions, fully automated.

### Metrics

- time until the first replica serves the new version
- time until the last replica serves the new version
- **cross-replica inconsistency window** (the primary metric)
- stale responses, error rate, latency percentiles
- detection time for an invalid configuration
- rollback and recovery time

## Measurement principles

These are not implementation details — they are the reason the numbers can be trusted.

**Client-side measurement.** Replicas sit on different nodes whose clock offset may
exceed the window being measured. The experiment runner provides a single clock.
Pod-side timestamps are a secondary, cross-checked signal only.

**Constant arrival rate.** Load is generated on a fixed schedule, never
"send → wait for response → send again". Waiting reduces throughput exactly when the
system degrades, which hides the event being measured (coordinated omission).

**Stated sampling limit.** A replica is only observed when a request reaches it.
With N replicas and rate r, each replica is sampled r/N times per second, so the
measured window is a lower bound. The relationship is derived and reported.

**Frozen instrument.** The runner is frozen before the measurement campaign. Changing
the instrument afterwards invalidates every earlier run.

## Repository layout

```
services/
  probe-service/          the object under study — reports value, version, pod, start time
  consumer-service/       calls probe; shows blast radius crossing a service boundary
tools/
  experiment-runner/      load, config change, window detection, statistics
  combination-prober/     derives the compatibility matrix experimentally
  config-validator/       admission webhook — rejects invalid config before activation
lib/
  config-consistency-starter/   makes config version and drift observable in any service
ui/
  config-drift-dashboard/ live replica view, results, decision wizard (Angular)
deploy/
  way-a-envvar/  way-b-volume/  way-c-configserver/
docs/
  DECISIONS.md            design decisions, recorded as they are made
results/                  raw data and generated figures
```

## Running the probe service locally

```bash
cd services/probe-service
mvn spring-boot:run
curl localhost:8080/probe
curl localhost:8080/config-health
```

Expected output:

```json
{"value":"UNSET","configVersion":"v0","pod":"local","startedAt":...,"servedAt":...}
```

## Status

| Component | State |
|---|---|
| probe-service | first version |
| Way A / Way B manifests | first version |
| consumer-service | not started |
| experiment-runner | not started |
| combination-prober | not started |
| config-validator | not started |
| starter library | not started |
| dashboard | not started |

## Licence

To be decided before the repository is made public.
