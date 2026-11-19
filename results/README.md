# Results

## Layout

```
raw/                               NOT committed (large, regenerable by running again)
  <campaign id>/                   one folder per campaign, for example 20261115-155726_s1-smoke
    campaign.json                  campaign file, protocol, scenario, approach names, block order
    approaches/<name>.json         copy of every approach descriptor used
    fingerprint.json               git commit and dirty flag, kubectl and server version, node count,
                                   images, Java version, OS, machine, Docker CPUs and memory
    <run id>.ndjson                one line per request
    <run id>.resources.ndjson      one line per container per resource sample
    <run id>.cluster.json          pod lists before and after, infrastructure state, events
    <run id>.meta.json             approach, block, attempt, steps with times and exit codes,
                                   t_cs, T0, verdict, reasons, flags
    runs.csv                       one row per run, one column per metric
    summary.csv                    counts, statistics, comparisons and sensitivity rows per approach
  <run id>.ndjson, <run id>.meta.json    files of single runs and of campaigns before 2026-11-15

aggregated/                        committed
  <campaign id>/                   the small files of a campaign, copied by hand from raw/
    runs.csv, summary.csv
    campaign.json, fingerprint.json, approaches/
    <run id>.meta.json

kubelet-delay.txt                  the kubelet delay measurements of journal entry 05
```

A campaign id is the local start time and the campaign name. A run id is the
local start time, the approach, the block and the attempt, for example
`20261115-172226_way-c_b01_a1`.

The format of every file is described in
[document/runner.md](../document/runner.md#files-of-a-campaign).

## What is committed today

| Folder | Campaign | Runs |
|---|---|---|
| `aggregated/20261115-155726_s1-smoke/` | S1 smoke, protocol version 1 | 6, all VALID |
| `aggregated/20261115-163837_s2-smoke/` | S2 smoke, protocol version 1 | 3, all VALID |
| `aggregated/20261115-170505_s3-smoke/` | S3 smoke, protocol version 1 | 5, of which 2 VALID and 3 INVALID |

These are smoke campaigns. The full campaigns have not been run. What the smoke
runs showed is in
[journal entry 06](../document/journal/06-experiment-protocol.md).

The raw folders of the three smoke campaigns are 19 MB, 14 MB and 24 MB on disk
and exist only on my machine.

## Re-evaluating a campaign

`runs.csv` and `summary.csv` are computed from the other files of a raw folder
and can be rebuilt at any time:

```bash
java -jar tools/experiment-runner/target/experiment-runner.jar --evaluate results/raw/20261115-155726_s1-smoke
```

This needs the raw files, so it does not work on a folder under `aggregated/`.
After a re-evaluation, copy the two CSV files to `aggregated/` again.

## Reproducibility

Every campaign folder holds `fingerprint.json` and a full copy of the protocol,
the scenario and the approach descriptors. Every row of `runs.csv` and
`summary.csv` starts with the protocol version. Results are compared only within
one protocol version.

The fingerprint is taken once, before the first run of the campaign. Its
`workloadImages` list shows the pods that were running at that moment, which are
the pods left over from whatever ran before. The image a run really used is in
`podsBefore` of its `cluster.json`.

## Rules

Never edit a file under `raw/` by hand. The runner also saves invalid runs,
with the verdict `INVALID` and the reasons in the meta file and in `runs.csv`.
They stay on disk and are left out of the statistics. If a run is bad for a
reason the runner cannot see, record why in the journal. Do not delete or
correct it.

The copy to `aggregated/` is a manual step. The runner does not do it.
