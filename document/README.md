# Project documentation

Documentation for the master thesis *Configuration Delivery in Replicated Spring Boot
Microservices on Kubernetes: A Reference Architecture, Controlled Experiments, and an
Evidence-Based Decision Model*.

The main quantity the thesis measures is the inconsistency window: how long replicas
of the same service answer with different configuration versions after a change.
This folder explains how the measurement setup is built, how to run it, and what I
have learned so far.

## Quick start: see the inconsistency window in 5 minutes

You need Docker Desktop (running), `kind`, `kubectl`, Java 21 or newer, and Maven.

From the repository root:

```bash
kind create cluster --config deploy/kind/kind-cluster.yaml
```

```bash
(cd services/probe-service && mvn -q -DskipTests package && docker build -t probe-service:0.1.0 .)
```

```bash
kind load docker-image probe-service:0.1.0 --name thesis
```

```bash
kubectl apply -f deploy/namespace.yaml && kubectl apply -f deploy/way-a-envvar/
```

```bash
kubectl -n thesis rollout status deployment/probe-service
```

Check that it answers:

```bash
curl -s localhost:30080/probe
```

The response should contain `"configVersion":"v1"`. To watch the window itself,
follow [the Way A runbook](runbook-way-a.md#3-run-the-experiment).

## Documents

| Document | Read it when you want to |
|---|---|
| [Architecture](architecture.md) | understand how the setup is built and why |
| [Probe service API](probe-api.md) | know what each endpoint returns |
| [Runbook: Way A experiment](runbook-way-a.md) | run the experiment step by step, or fix a known error |
| [Experiment runner](runner.md) | run the measurement tool, read its output files, understand the coverage check |
| [Journal](journal/) | read what happened, what broke, and what I learned |
| [Design decisions](../docs/DECISIONS.md) | see each decision with the alternatives I rejected |

## Project status

| Part | State |
|---|---|
| Probe service | Working |
| Local cluster (kind, 1 control plane and 3 workers) | Working |
| Way A: environment variables and rolling restart | Working; restart triggered automatically by Reloader, 5 s `preStop` delay so no requests fail |
| Way B: mounted ConfigMap and runtime refresh | Working; refresh triggered automatically by the Configuration Watcher |
| Shared `platform` namespace for both triggers | Working; the Watcher discovers pods in `thesis` |
| Kubelet delay measured, `refreshDelay` tuned | Done: 9.2 s to 77.6 s over 30 values, `refreshDelay` stays at 90 s |
| Way C: Config Server and Spring Cloud Bus | Planned |
| Monitoring with Prometheus and Grafana | Planned, postponed |
| Experiment runner | Working: constant-rate load, coverage check, raw data per run, change applied after a warm-up, window detected and unit tested |
| Repeated runs of Way A and Way B | Next |
| Angular dashboard | Planned, last |
