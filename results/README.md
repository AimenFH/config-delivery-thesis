# Results

## Layout

```
raw/          two files per run, NOT committed (large, regenerable):
                <run id>.ndjson     one line per request
                <run id>.meta.json  settings and validity of the run
aggregated/   one CSV per (approach, scenario) — committed
figures/      generated charts — committed
runs.csv      index: run id, approach, scenario, timestamp, git commit, environment
```

The format of the raw files is described in
[document/runner.md](../document/runner.md#output-files). `aggregated/`, `figures/`
and `runs.csv` do not exist yet.

## Reproducibility

Every run will record an environment fingerprint: Kubernetes version, node count,
image digests, runner git commit, request rate, replica count. The meta file
already holds the run's own settings; the cluster part is still to do. Without this a
number cannot be compared to any other number.

## Rule

Never edit a file under `raw/` by hand. The runner also saves invalid runs, marked
`"valid": false` with the reasons in their meta file. If a run is bad for another
reason, discard it and record why in `runs.csv`. Silent correction is the fastest way to lose an entire dataset's
credibility.
