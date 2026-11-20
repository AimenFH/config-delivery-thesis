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
(cd services/probe-service && mvn -q -DskipTests package && docker build -t probe-service:0.2.0 .)
```

```bash
kind load docker-image probe-service:0.2.0 --name thesis
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
| [Architecture](architecture.md) | understand how the setup is built and why: the fixed core, the adapter boundary, the timeline of a run, the three scenarios |
| [Probe service API](probe-api.md) | know what each endpoint returns and how the readiness gate works |
| [Experiment runner](runner.md) | know the arguments, the input files, the files of a campaign and the exit codes |
| [Runbook: experiment 1](runbook-experiment-1.md) | run a smoke or full campaign, evaluate a folder again, add an approach or a scenario, fix a stopped campaign |
| [Runbook: Way A experiment](runbook-way-a.md) | set up the cluster and run Way A by hand |
| [Runbook: logs and results in Grafana](runbook-monitoring.md) | install Loki, Alloy and Grafana, read the pod logs, load a campaign and look at a run |
| [Code walkthrough](code-walkthrough.md) | learn the code: every class, every metric with a worked example, one run from `main` to `summary.csv` |
| [Experiment plan](../docs/experiment-plan.md) | read the specification of protocol version 1 |
| [Design decisions](../docs/DECISIONS.md) | see each decision with the alternatives I rejected |
| [Results](../results/README.md) | find the result files and know which are committed |
| [Journal](journal/) | read what happened, what broke, and what I learned |

Journal entries:

| Entry | Milestone |
|---|---|
| [01](journal/01-setup-and-first-experiment.md) | Setup and first experiment |
| [02](journal/02-way-b-and-automatic-triggers.md) | Way B and automatic triggers |
| [03](journal/03-experiment-runner.md) | Experiment runner |
| [04](journal/04-change-and-window-detection.md) | Change and window detection |
| [05](journal/05-platform-namespace-and-kubelet-delay.md) | Platform namespace and kubelet delay |
| [06](journal/06-experiment-protocol.md) | Experiment protocol version 1 and the smoke campaigns |
| [07](journal/07-way-b-gate-deadlock.md) | Why Way B did not recover in S2 |
| [08](journal/08-open-points-in-the-runner.md) | Three open points closed in the runner |
| [09](journal/09-loki-and-grafana.md) | Logs and results in Grafana with Loki |

## Project status

State on 2026-11-20.

| Part | State |
|---|---|
| Probe service | Working, version 0.2.0 with the readiness gate behind `CONFIG_GATE_ENABLED` |
| Local cluster (kind, 1 control plane and 3 workers) | Working |
| Way A: environment variables and rolling restart | Working; restart triggered by Reloader, 5 s `preStop` delay |
| Way B: mounted ConfigMap and runtime refresh | Working; refresh triggered by the Configuration Watcher, `refreshDelay` 90 s |
| Way C: Config Server and Spring Cloud Bus | Working; Config Server reads a Git repository on GitHub, refresh over RabbitMQ |
| Shared `platform` namespace for all delivery components | Working |
| Experiment plan, protocol version 1 | Frozen on 2026-11-15 |
| Experiment runner | Working: campaigns in randomized blocks, three scenarios, adapter per approach, resource sampling, 27 metrics computed from raw data, `--evaluate`, `--loki`. Three verdict and metric rules changed on 2026-11-19, 510 unit tests pass |
| Smoke campaign S1 valid change | Run: 6 runs, all VALID |
| Smoke campaign S2 invalid configuration | Run: 3 runs, all VALID. Way B did not recover: the Watcher sends no refresh to pods that are not ready, see [journal entry 07](journal/07-way-b-gate-deadlock.md) |
| Smoke campaign S3 infrastructure outage | Run on 2026-11-15: Way A and Way B VALID, Way C INVALID in all 3 attempts, exit code 2. Run again on 2026-11-19 with the new rules: one valid run per approach, Way C VALID with `TRIGGER_FAILED`, two first attempts INVALID for schedule lag, see [journal entry 08](journal/08-open-points-in-the-runner.md) |
| Full campaigns S1, S2, S3 | Not run. They are the last step, together with the decision model. They wait for the open points in journal entries [07](journal/07-way-b-gate-deadlock.md#open-points), [08](journal/08-open-points-in-the-runner.md#open-points) and [09](journal/09-loki-and-grafana.md#open-points) |
| Logs and results in Grafana with Loki and Alloy | Working in the namespace `monitoring`: pod logs of `thesis` and `platform`, two dashboards, results loaded with `tools/loki-load.sh`. No campaign has run with it yet, see [journal entry 09](journal/09-loki-and-grafana.md) |
| Metrics with Prometheus | Not built. The runner reads resource data from the kubelet itself |
| Angular dashboard | Planned, last |
