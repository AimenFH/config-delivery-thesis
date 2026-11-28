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
(cd services/probe-service && mvn -q -DskipTests package && docker build -t probe-service:0.3.0 .)
```

```bash
kind load docker-image probe-service:0.3.0 --name thesis
```

probe-service writes every request to PostgreSQL and does not start without it,
so the database comes first:

```bash
kubectl apply -f deploy/data/ && kubectl -n data rollout status statefulset/postgres --timeout=120s
```

```bash
kubectl apply -f deploy/namespace.yaml && kubectl apply -f deploy/probe-db-secret.yaml && kubectl apply -f deploy/way-a-envvar/
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
| [Architecture](architecture.md) | understand how the setup is built and why: the fixed core, the adapter boundary, the timeline of a run, the five scenarios |
| [Probe service API](probe-api.md) | know what each endpoint returns and how the readiness gate works |
| [Experiment runner](runner.md) | know the arguments, the input files, the files of a campaign and the exit codes |
| [Runbook: experiment 1](runbook-experiment-1.md) | run a smoke or full campaign, evaluate a folder again, add an approach or a scenario, fix a stopped campaign |
| [Runbook: Way A experiment](runbook-way-a.md) | set up the cluster and run Way A by hand |
| [Runbook: Way D with Argo CD and Helm](runbook-way-d.md) | install Argo CD, put the Helm chart into Git and change a configuration through a commit |
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
| [10](journal/10-way-d-argo-cd-and-helm.md) | Way D, GitOps with Argo CD and Helm |
| [11](journal/11-scenarios-from-incident-reports.md) | Two scenarios from incident reports |
| [12](journal/12-database-behind-the-probe-service.md) | A database behind the probe service |

## Project status

State on 2026-11-28.

| Part | State |
|---|---|
| Probe service | Working, version 0.2.0 with the readiness gate behind `CONFIG_GATE_ENABLED` |
| Local cluster (kind, 1 control plane and 3 workers) | Working |
| Way A: environment variables and rolling restart | Working; restart triggered by Reloader, 5 s `preStop` delay |
| Way B: mounted ConfigMap and runtime refresh | Working; refresh triggered by the Configuration Watcher, `refreshDelay` 90 s |
| Way C: Config Server and Spring Cloud Bus | Working; Config Server reads a Git repository on GitHub, refresh over RabbitMQ |
| Way D: GitOps with Argo CD and Helm | Working by hand: Argo CD 3.5.8 syncs a Helm chart from the branch `way-d`, a new value restarts the pods. In the protocol file since 2026-11-21. Run by the runner in the S4 and S5 smoke campaigns of that day, 2 runs, both VALID. Not run in S1, S2 and S3 yet, see journal entries [10](journal/10-way-d-argo-cd-and-helm.md) and [11](journal/11-scenarios-from-incident-reports.md) |
| Shared `platform` namespace for all delivery components | Working |
| Experiment plan, protocol version 1 | Frozen on 2026-11-15 |
| Experiment runner | Working: campaigns in randomized blocks, five scenarios, adapter per approach, resource sampling, 34 metrics computed from raw data, `--evaluate`, `--loki`. Three verdict and metric rules changed on 2026-11-19. S4 and S5 added on 2026-11-21, 541 unit tests pass |
| Smoke campaign S1 valid change | Run on 2026-11-15 with three approaches: 6 runs, all VALID |
| Smoke campaign S2 invalid configuration | Run: 3 runs, all VALID. Way B did not recover: the Watcher sends no refresh to pods that are not ready, see [journal entry 07](journal/07-way-b-gate-deadlock.md) |
| Smoke campaign S3 infrastructure outage | Run on 2026-11-15: Way A and Way B VALID, Way C INVALID in all 3 attempts, exit code 2. Run again on 2026-11-19 with the new rules: one valid run per approach, Way C VALID with `TRIGGER_FAILED`, two first attempts INVALID for schedule lag, see [journal entry 08](journal/08-open-points-in-the-runner.md) |
| Scenario S4 rapid successive changes | Defined on 2026-11-21: scenario file, two campaign files, metrics Q1 to Q7 with unit tests. Smoke campaign run on 2026-11-21 with four approaches: 4 runs, all VALID, see [journal entry 11](journal/11-scenarios-from-incident-reports.md#s4) and the [decision entry](../docs/DECISIONS.md#2026-11-21-s4-commits-two-valid-changes-10-seconds-apart) |
| Scenario S5 invalid configuration and pod restart | Defined on 2026-11-21: scenario file, two campaign files, the new step `deleteReadyPod` with unit tests, metrics of S2. Smoke campaign run on 2026-11-21 with four approaches: 4 runs, all VALID. Ways A and D served with 2 replicas after the pod start, see [journal entry 11](journal/11-scenarios-from-incident-reports.md#s5) and the [decision entry](../docs/DECISIONS.md#2026-11-21-s5-deletes-a-ready-pod-while-the-invalid-configuration-waits-at-the-source) |
| Full campaigns S1 to S5 | Not run. They are the last step, together with the decision model, and need about 31 hours with four approaches. They wait for the open points in journal entries [07](journal/07-way-b-gate-deadlock.md#open-points), [08](journal/08-open-points-in-the-runner.md#open-points), [09](journal/09-loki-and-grafana.md#open-points), [10](journal/10-way-d-argo-cd-and-helm.md#open-points) and [11](journal/11-scenarios-from-incident-reports.md#open-points) |
| Logs and results in Grafana with Loki and Alloy | Working in the namespace `monitoring`: pod logs of `thesis` and `platform`, two dashboards, results loaded with `tools/loki-load.sh`. No campaign has run with it yet, see [journal entry 09](journal/09-loki-and-grafana.md) |
| Database | Built on 2026-11-23: PostgreSQL 17.2 in the namespace `data`, probe-service 0.3.0 on Spring Boot 4.2 writes one row per `GET /probe`, all four Ways get the settings from the Secret `probe-db`. Applied by hand, not checked by the runner. First run in the S1 smoke campaign of 2026-11-28, see [journal entry 12](journal/12-database-behind-the-probe-service.md) |
| Metrics with Prometheus | Not built. The runner reads resource data from the kubelet itself |
| Angular dashboard | Planned, last |
