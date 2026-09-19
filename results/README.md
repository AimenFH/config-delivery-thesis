# Results

## Layout

```
raw/          one NDJSON file per run — NOT committed (large, regenerable)
aggregated/   one CSV per (approach, scenario) — committed
figures/      generated charts — committed
runs.csv      index: run id, approach, scenario, timestamp, git commit, environment
```

## Reproducibility

Every run records an environment fingerprint: Kubernetes version, node count,
image digests, runner git commit, request rate, replica count. Without this a
number cannot be compared to any other number.

## Rule

Never edit a file under `raw/` by hand. If a run is bad, discard it and record
why in `runs.csv`. Silent correction is the fastest way to lose an entire dataset's
credibility.
