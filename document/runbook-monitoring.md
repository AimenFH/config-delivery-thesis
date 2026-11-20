# Runbook: logs and results in Grafana

Use this runbook to install Loki, Alloy and Grafana on the kind cluster, to read
the logs of the pods, and to look at the results of a campaign as a picture.

The monitoring stack runs in the namespace `monitoring` on the control-plane node.
It is not part of what the runner measures. The reasons are in the four entries of
2026-11-20 in [docs/DECISIONS.md](../docs/DECISIONS.md).

| Component | Version | Role |
|---|---|---|
| Loki | 3.7.10 | Stores log lines with labels |
| Alloy | 1.22.0 | Reads the pod logs of `thesis` and `platform` and sends them to Loki |
| Grafana | 13.3.1 | The page in the browser. It asks Loki and draws the answer |

## Prerequisites

- Docker Desktop is running and `kubectl get nodes` shows four nodes with the
  status `Ready`.
- The namespaces `thesis` and `platform` exist, see the
  [runbook of experiment 1](runbook-experiment-1.md).
- For loading results: the runner is built, `tools/experiment-runner/target/experiment-runner.jar`
  exists.

All commands run from the repository root.

## 1. Install

```bash
kubectl apply -f deploy/monitoring/
```

Expected result: one line per object, `created` the first time and `unchanged` or
`configured` later. The file `00-namespace.yaml` has its number so that the
namespace is applied before the objects in it.

```bash
kubectl -n monitoring rollout status statefulset/loki --timeout=240s
```

```bash
kubectl -n monitoring rollout status deployment/alloy --timeout=240s
```

```bash
kubectl -n monitoring rollout status deployment/grafana --timeout=240s
```

Expected result: `partitioned roll out complete: 1 new pods have been updated...`
for Loki and `successfully rolled out` for the two others. The first start pulls
three images.

## 2. Open Grafana

The command keeps running and holds the connection open. Leave it in its own
terminal tab.

```bash
kubectl -n monitoring port-forward svc/grafana 3000:3000
```

Expected result: `Forwarding from 127.0.0.1:3000 -> 3000`.

Open `http://localhost:3000`. There is no login. The home page is the dashboard
"Cluster logs".

When the Grafana pod restarts, the command ends. Start it again.

## 3. Read the logs of the pods

The dashboard "Cluster logs" has two charts and one log panel per component:
probe-service, Reloader, the Configuration Watcher, the Config Server and
RabbitMQ.

- Set the time range at the top right.
- Type a word into **Search text** and press Enter. Every panel then shows only
  the lines that contain it. Upper and lower case do not matter.

Example: with the search text `refresh` and the hour after a start of the cluster,
the Watcher shows `will schedule remote refresh based on apps : [probe-service]`
and, about 90 s later, each probe pod shows `Refreshed keys : []`.

For a free search open `http://localhost:3000/explore`, choose the label `app` and
a value under **Label filters** and press **Run query**.

## 4. Load the results of a campaign

Alloy collects the pod logs by itself. The results of the runner are loaded by
hand, after a campaign has ended.

```bash
bash tools/loki-load.sh results/raw/20261119-232500_s3-smoke
```

Expected result, two lines per run:

```
Loaded 20261120-000019_way-c_b01_a1: 18001 requests, 5 steps
  http://localhost:3000/d/run-results/run-results?var-run=20261120-000019_way-c_b01_a1&from=1795129390389&to=1795129753390
```

The script opens its own connection to Loki on port 3100 and closes it at the end.
Loading five runs took about 4 s. A second call prints `Already in Loki` for every
run and sends nothing.

## 5. Look at a run

Open one of the printed addresses, or open
`http://localhost:3000/d/run-results/run-results` and click a run id in the table
at the top. The page then shows that run with its own time range.

| Panel | What it shows |
|---|---|
| Version answered by each pod | One row per pod, coloured by the version it answered. Rows with different colours at the same time are the inconsistency window |
| Answers per second by version | How the answers move from one version to the next |
| Latency of answered requests | p50, p99 and the maximum |
| Failed requests per second | A line at 0 when no request failed |
| Steps of the run | One line per scenario step with exit code and duration |
| What the pods and delivery components logged | The pod logs of the same minutes |

The orange dashed lines in the charts are the scenario steps. The switch
**Scenario steps** at the top hides them.

The last panel is empty for a run that happened before Alloy was installed.

## Check that it works

Loki answers, through its own port-forward:

```bash
kubectl -n monitoring port-forward svc/loki 3100:3100
```

```bash
curl -s localhost:3100/ready
```

Expected result: `ready`.

Alloy has found the pods:

```bash
curl -s localhost:3100/loki/api/v1/label/app/values
```

Expected result: the five names `config-server`, `config-watcher`,
`probe-service`, `rabbitmq` and `reloader-reloader`.

## Problems

| Symptom | Cause | What to do |
|---|---|---|
| `namespaces "monitoring" not found` during the install | The objects were applied before the namespace | Run `kubectl apply -f deploy/monitoring/` again |
| `Loki did not answer on localhost:3100` from `loki-load.sh` | Loki is not ready, or the port is used by another program | Check `kubectl -n monitoring get pods`. Use another port with `LOKI_PORT=3101 bash tools/loki-load.sh <folder>` |
| The browser shows nothing at `localhost:3000` | The port-forward has ended | Start it again, see step 2 |
| A panel stays empty for a minute after a change of a dashboard file | Grafana reads the files every 30 s and the cluster needs up to a minute to pass a changed ConfigMap to the pod | Wait and reload the page |
| `Loaded` instead of `Already in Loki` on a second load, and a run counts twice | Loki did not find the first load. This happened when `query_store_max_look_back_period: -1` was missing in `deploy/monitoring/loki.yaml` | Check the setting. Then remove the loaded results, see below |
| `Loki push failed with status 429` | Loki refused the amount of data. The exporter has tried six times | Wait a minute and load again. Runs that are already in Loki are skipped |
| The pod timeline shows grey bars with the label `-∞+` | The panel has a `thresholds` block, which Grafana 13.3.1 puts before the value mappings | Remove the block from the panel in `grafana-dashboards.yaml` |

### Remove loaded results

Loki has no delete switched on. As long as the results are less than about 30
minutes in Loki, they are only in its write-ahead log and can be removed with it.
This also removes the pod logs of that time. Alloy sends the logs of the running
containers again when its pod is replaced.

```bash
kubectl -n monitoring scale statefulset/loki --replicas=0
```

```bash
kubectl -n monitoring wait --for=delete pod/loki-0 --timeout=120s
```

```bash
docker exec thesis-control-plane sh -c 'rm -rf /var/local-path-provisioner/*_monitoring_data-loki-0/wal/*'
```

```bash
kubectl -n monitoring scale statefulset/loki --replicas=1
```

```bash
kubectl -n monitoring delete pod -l app=alloy
```

After 30 minutes the results are in chunk files. Then the only way is to delete
the whole volume, which removes every log and every result in Loki:

```bash
kubectl delete namespace monitoring
```

Then install again from step 1 and load the campaigns again.

## Remove the monitoring stack

```bash
kubectl delete -f deploy/monitoring/alloy.yaml
```

```bash
kubectl delete namespace monitoring
```

The first command removes the two Roles and RoleBindings in `thesis` and
`platform`. The second removes everything else, with the stored logs.
