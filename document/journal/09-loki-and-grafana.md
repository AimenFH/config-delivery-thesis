# 09: Logs and results in Grafana with Loki

*20 November 2026, milestone 6*

## Summary

The cluster now keeps the logs of every pod in `thesis` and `platform`, and the
results of a campaign can be loaded and looked at as a picture. Three components
run in a new namespace `monitoring`: Loki stores, Alloy collects, Grafana shows.

| | Result |
|---|---|
| Pod logs | Collected by Alloy from five apps, readable in the dashboard "Cluster logs" |
| Results | The S3 smoke campaign of 19 November is in Loki, 5 runs, each with the same number of lines as its file |
| Picture of a run | The dashboard "Run results" shows the version each pod answered over time, with the scenario steps as lines |
| Unit tests of the runner | 510, all pass. 12 are new |
| Effect on the measurement | Not checked. No campaign has run with the monitoring stack on |

Claude Code wrote the manifests and the code in six parts, one after the other, so
that I could follow each one. I ran the commands of the first part myself. Claude
Code deployed the others and checked them, the dashboards also in a browser.

How to use it is in the [monitoring runbook](../runbook-monitoring.md). The
reasons are the four entries of 2026-11-20 in
[docs/DECISIONS.md](../../docs/DECISIONS.md). [Previous entry](08-open-points-in-the-runner.md).

## What was built

| Part | What | Files |
|---|---|---|
| 1 | Namespace and Loki as one pod with a 5 Gi volume | `deploy/monitoring/00-namespace.yaml`, `loki.yaml` |
| 2 | Alloy with read rights on pods and logs in `thesis` and `platform` | `deploy/monitoring/alloy.yaml` |
| 3 | Grafana with Loki as data source, no login | `deploy/monitoring/grafana.yaml` |
| 4 | Dashboard "Cluster logs" | `deploy/monitoring/grafana-dashboards.yaml` |
| 5a | The runner mode `--loki` and a script around it | `LokiExport`, `Runner`, `LokiExportTest`, `tools/loki-load.sh` |
| 5b | Dashboard "Run results" | `deploy/monitoring/grafana-dashboards.yaml` |

All three pods run on the control-plane node, away from the workers with the
probe pods.

## How one log line travels

A pod prints a line. Kubernetes keeps it on the node of the pod. Alloy asks the
API server for the logs of the pods it may read, adds the labels `namespace`,
`app`, `pod`, `container` and `node`, and sends the line to Loki. Loki indexes
the labels and stores the text. Grafana sends a query to Loki when I open a
dashboard.

Loki does not fetch anything itself. That is why the results of the runner can go
in through the same door: `LokiExport` sends them as lines with the labels
`job="runner"`, `kind`, `campaign`, `scenario`, `approach` and `run_id`.

## What the logs showed at once

When Docker started on 20 November, the dashboard "Cluster logs" with the search
text `refresh` showed this, without any change of a configuration:

| Time | Component | Line |
|---|---|---|
| 20:49:46 | Configuration Watcher | `will schedule remote refresh based on apps : [probe-service]` |
| 20:51:20 to 20:51:21 | probe-service, three pods | `Refreshed keys : []` |

This is the second finding of [entry 07](07-way-b-gate-deadlock.md#the-watcher-refreshes-when-it-starts):
the Watcher sends a refresh about 90 s after its start. In entry 07 it took debug
logging and a saved log file to see it.

## What the Way C run of S3 looks like

The run `20261120-000019_way-c_b01_a1` in the dashboard "Run results":

- Three pods answer v1 from the start.
- The row of `probe-service-785487458c-9cw8h` ends at 00:05:40. The steps panel
  names it as the pod of `deletePod`.
- A fourth row, `probe-service-785487458c-g5vsc`, starts at 00:07:22 with v2.
- The two other rows stay on v1 to the end, the `LOST_UPDATE` of this run.
- The steps panel shows `trigger: exit code 124, took 15040.878 ms`.

Counted in Loki, the four pods have 6 828, 2 456, 6 910 and 1 807 answers. These
are the numbers I get from the NDJSON file of the run.

## What broke

Five things did not work at first.

1. **The namespace came too late.** `kubectl apply -f deploy/monitoring/` reads the
   files in alphabetical order, so `loki.yaml` was sent before `namespace.yaml`.
   The file is now `00-namespace.yaml`.
2. **Loki did not find results it had just received.** Loki keeps new lines in
   memory for about 30 minutes before it writes them to a chunk file. For a query
   about a time more than 2 h 41 min ago it looked only at the chunk files. The
   results of the day before were loaded, accepted with status 204 and invisible.
   The setting `query_store_max_look_back_period: -1` makes Loki look in memory for
   every time range. A first attempt with `query_ingesters_within: 0` changed
   nothing.
3. **Every run was in Loki twice.** The exporter asks Loki whether a run is there
   before it loads it. Because of point 2 the answer was no, and a second test
   load stored all five runs again: 36 002 lines where the file has 18 001. Loki
   did not drop the duplicates. To remove them Claude Code stopped Loki, deleted
   its write-ahead log on the node and started it again. The stored chunk files
   with the pod logs of the night before, 487 lines, were not touched. The pod
   logs of the evening were in the deleted log too, and Alloy sent them again
   after its pod was replaced. A new load then gave the same count as the files,
   and a second load printed `Already in Loki` for every run.
4. **Eleven old tests failed with the new test class.** The JDK reads the property
   `jdk.httpclient.allowRestrictedHeaders` once, when its HTTP client is first
   used. `LokiExportTest` used the client before any test had set the property, and
   `ProbeClient` could then no longer send its `Connection` header. The test class
   now sets the property in a static block, like `ProbeClientTest`.
5. **The pod timeline was grey.** With a `thresholds` block in the panel, Grafana
   13.3.1 labels the states by threshold and ignores the value mappings that turn
   1 into v1 and 2 into v2. Claude Code tried four variants of the panel in a
   temporary dashboard. The ones without `thresholds` showed the colours. The link
   in the run table also passed the time as text. It now passes the number.

## What was not checked

- No campaign has run while Loki, Alloy and Grafana were up. I decided to go on
  building first. Whether the stack changes P1, C1 or the resource metrics is
  open, and so is the question whether it adds schedule lag.
- The rule for results older than a week. `reject_old_samples` is off and the
  oldest data loaded was one day old.
- The S1 and S2 smoke campaigns of 15 November are not loaded.
- What happens to the stored logs when the volume is full. No retention is set.
- Alloy sending lines twice after its pod is replaced. Loki stored the results
  twice in point 3, so I expect the same for logs.

## Open points

1. A smoke campaign with the monitoring stack on, compared with the campaigns of
   15 and 19 November. It has to happen before the full campaigns.
2. Loading the results is a step by hand. It can be forgotten after a campaign.
3. Grafana has no login. That is acceptable only while it is reached through a
   port-forward.
4. The open points of [entry 08](08-open-points-in-the-runner.md#open-points) are
   unchanged.

## Next

1. Decide what is built next: further delivery approaches, the database, or the
   Angular dashboard.
2. Read `LokiExport` and the two dashboards.
