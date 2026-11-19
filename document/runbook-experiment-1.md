# Runbook: experiment 1, the three scenarios

## When to use this runbook

Use it to run a campaign of protocol version 1: one of the three smoke campaigns
or one of the three full campaigns. It also covers how to evaluate a campaign
folder again, how to add an approach or a scenario, and what to do when a
campaign stops.

State on 2026-11-15: the three smoke campaigns have been run once. The full
campaigns `campaigns/s1.json`, `campaigns/s2.json` and `campaigns/s3.json` have
not been run. They wait for the open points in
[journal entry 06](journal/06-experiment-protocol.md#open-points).

Reference: [experiment runner](runner.md) for arguments and file formats,
[plan](../docs/experiment-plan.md) for the specification.

## Prerequisites

| Requirement | Check |
|---|---|
| Docker Desktop running | `docker info` succeeds |
| kind cluster `thesis` with 1 control plane and 3 workers | `kubectl get nodes` shows four nodes, all `Ready` |
| `kubectl`, Java 21 or newer, Maven, `git`, `bash` | `kubectl version --client`, `java -version`, `mvn -v` |
| Port 30080 free for the cluster's NodePort | `curl -s localhost:30080/probe` answers when a Way is deployed |
| A clone of the configuration repository with push access to GitHub | `git -C ~/config-repo ls-remote -q origin HEAD` prints a commit hash without asking for a password |
| A quiet machine: plugged in, no builds, no other heavy work | |

All commands run from the repository root. The cluster setup itself is in the
[Way A runbook](runbook-way-a.md#1-create-the-cluster-once).

## Before a campaign

Do these five checks before every campaign. A campaign stops at the first run if
one of them is not met, but only after several minutes.

### 1. The runner is built

```bash
cd tools/experiment-runner && mvn -q package && cd ../..
```

Expected result: no output, and `tools/experiment-runner/target/experiment-runner.jar`
exists. The command runs the unit tests and prints the failures if there are any.

### 2. The image `probe-service:0.2.0` is loaded

```bash
docker exec thesis-worker crictl images | grep probe-service
```

Expected result: a line with `docker.io/library/probe-service` and the tag `0.2.0`.
If it is missing, build and load it:

```bash
(cd services/probe-service && mvn -q -DskipTests package && docker build -t probe-service:0.2.0 .)
```

```bash
kind load docker-image probe-service:0.2.0 --name thesis
```

Do not rebuild the image between the campaigns that you want to compare. The
image id is part of the fingerprint, and a campaign stops when the image id
changes between two of its runs.

### 3. The `platform` Deployments are up

```bash
kubectl -n platform get deployments
```

Expected result: four Deployments, each with `1/1` ready:

```
NAME                READY   UP-TO-DATE   AVAILABLE
config-server       1/1     1            1
config-watcher      1/1     1            1
rabbitmq            1/1     1            1
reloader-reloader   1/1     1            1
```

All four must be up for every campaign, also when you think only one approach
matters. The runner checks them before every run.

### 4. The configuration repository is clean

```bash
git -C ~/config-repo status --short
```

Expected result: no output. Then check that the file the script edits has the two
lines it looks for:

```bash
grep -n "^  configVersion: \|^  timeoutMs: " ~/config-repo/probe-service.yml
```

Expected result: two lines. If the repository is somewhere else, set
`CONFIG_REPO` to its path in the shell that starts the campaign.

Way C pushes two or three commits to GitHub in every run: the reset to v1 and one
per commit step. That is expected.

### 5. Nothing else is measuring

```bash
kubectl -n thesis get pods
```

Whatever is deployed in `thesis` is deleted by the first run. No other runner
process may be running against port 30080.

## Run a smoke campaign

A smoke campaign checks that everything works. Its numbers are not results.

```bash
caffeinate -dims java -jar tools/experiment-runner/target/experiment-runner.jar --campaign campaigns/s1-smoke.json
```

```bash
caffeinate -dims java -jar tools/experiment-runner/target/experiment-runner.jar --campaign campaigns/s2-smoke.json
```

```bash
caffeinate -dims java -jar tools/experiment-runner/target/experiment-runner.jar --campaign campaigns/s3-smoke.json
```

Run them one after the other, never two at once. `caffeinate` keeps macOS awake.

| Campaign | Runs when all are valid | Time on 2026-11-15 |
|---|---|---|
| `s1-smoke` | 6 (2 blocks of 3) | 15:57 to 16:38 |
| `s2-smoke` | 3 (1 block) | 16:38 to 17:05 |
| `s3-smoke` | 3 (1 block), 5 with the repeats of Way C | 17:05 to 17:49 |

The output of the three smoke campaigns of 2026-11-15, built from their result
files. The runner prints one line per run, when the run is over.

S1:

```
Campaign 20261115-155726_s1-smoke: 2 valid runs per approach, at most 2 blocks, results in /…/results/raw/20261115-155726_s1-smoke
Block 1 of at most 2: [way-a, way-b, way-c]
  20261115-155727_way-a_b01_a1 VALID
  20261115-160431_way-b_b01_a1 VALID
  20261115-161113_way-c_b01_a1 VALID
Block 2 of at most 2: [way-b, way-a, way-c]
  20261115-161810_way-b_b02_a1 VALID
  20261115-162447_way-a_b02_a1 VALID
  20261115-163122_way-c_b02_a1 VALID
Valid runs: {way-a=2, way-b=2, way-c=2}
Evaluated 6 runs (6 valid) in /…/results/raw/20261115-155726_s1-smoke
Saved /…/results/raw/20261115-155726_s1-smoke/runs.csv
Saved /…/results/raw/20261115-155726_s1-smoke/summary.csv
```

S2:

```
Campaign 20261115-163837_s2-smoke: 1 valid runs per approach, at most 1 blocks, results in /…/results/raw/20261115-163837_s2-smoke
Block 1 of at most 1: [way-a, way-b, way-c]
  20261115-163838_way-a_b01_a1 VALID
  20261115-164716_way-b_b01_a1 VALID
  20261115-165557_way-c_b01_a1 VALID
Valid runs: {way-a=1, way-b=1, way-c=1}
Evaluated 3 runs (3 valid) in /…/results/raw/20261115-163837_s2-smoke
```

S3:

```
Campaign 20261115-170505_s3-smoke: 1 valid runs per approach, at most 1 blocks, results in /…/results/raw/20261115-170505_s3-smoke
Block 1 of at most 1: [way-a, way-b, way-c]
  20261115-170505_way-a_b01_a1 VALID
  20261115-171344_way-b_b01_a1 VALID
  20261115-172226_way-c_b01_a1 INVALID [trigger after the commit of v2 failed with exit code 1]
  20261115-173113_way-c_b01_a2 INVALID [trigger after the commit of v2 failed with exit code 1]
  20261115-174007_way-c_b01_a3 INVALID [trigger after the commit of v2 failed with exit code 1]
Valid runs: {way-a=1, way-b=1, way-c=0}
Evaluated 5 runs (2 valid) in /…/results/raw/20261115-170505_s3-smoke
```

The S3 smoke campaign of 15 November ended with exit code 2, because Way C had no
valid run. Since 19 November a trigger that fails while the delivery components
are down sets the flag `TRIGGER_FAILED` and the run stays VALID, see
[journal entry 08](journal/08-open-points-in-the-runner.md). The S3 smoke
campaign of 19 November ran with this rule and reached one valid run per
approach.

VALID says that the instrument worked. It does not say that the approach behaved
well. In the S2 smoke run Way B is VALID with the flags `FAILED_REQUESTS` and
`NOT_RECOVERED`. The flags are in the `flags` column of `runs.csv`, the console
does not show them.

Check the exit code right after the campaign:

```bash
echo $?
```

| Code | Meaning |
|---|---|
| `0` | Every approach has its valid runs |
| `2` | The blocks are used up and an approach has too few valid runs |
| `1` | The campaign stopped, see [Troubleshooting](#troubleshooting) |

## Run a full campaign

The full campaigns have not been run. Do not start them before the open points
of [journal entry 06](journal/06-experiment-protocol.md#open-points) are decided.

```bash
caffeinate -dims java -jar tools/experiment-runner/target/experiment-runner.jar --campaign campaigns/s1.json
```

```bash
caffeinate -dims java -jar tools/experiment-runner/target/experiment-runner.jar --campaign campaigns/s2.json
```

```bash
caffeinate -dims java -jar tools/experiment-runner/target/experiment-runner.jar --campaign campaigns/s3.json
```

| Campaign | Valid runs per approach | Blocks at most | Time planned |
|---|---|---|---|
| `s1` | 20 | 25 | about 6.7 hours for 20 blocks |
| `s2` | 10 | 25 | about 4.3 hours for 10 blocks |
| `s3` | 10 | 25 | about 4.3 hours for 10 blocks |

The times are those of the plan (6.7 minutes per S1 run, 8.7 minutes per S2 or S3
run) and grow with every repeated run. The plan asks for 3 pilot blocks per
scenario before each campaign. They are not part of the results.

The output has the same form as that of a smoke campaign. The full campaigns use
the same seed as the smoke campaigns, so their first blocks have the same order:
block 1 is `[way-a, way-b, way-c]`, block 2 is `[way-b, way-a, way-c]`.

The campaign ends before the last block when every approach has reached its valid
runs. Every block that is started runs all three approaches, so an approach can
end with more valid runs than asked.

## After a campaign

1. Note the exit code.
2. Check that the infrastructure is back, most of all after S3:

   ```bash
   kubectl -n platform get deployments
   ```

   Expected result: the four Deployments with `1/1`, as before the campaign.

3. Look at the verdicts and flags:

   ```bash
   cut -d, -f2,3,7,8,9 results/raw/<campaign id>/runs.csv
   ```

   Expected result for the S2 smoke campaign:

   ```
   run_id,approach,verdict,reasons,flags
   20261115-163838_way-a_b01_a1,way-a,VALID,,
   20261115-164716_way-b_b01_a1,way-b,VALID,,FAILED_REQUESTS NOT_RECOVERED
   20261115-165557_way-c_b01_a1,way-c,VALID,,FAILED_REQUESTS LOW_RESOLUTION
   ```

4. Copy the small files to `results/aggregated/<campaign id>/`: `runs.csv`,
   `summary.csv`, `campaign.json`, `fingerprint.json`, the `approaches/` folder and
   every `*.meta.json`. The raw files stay in `results/raw/`, which is not
   committed. See [results/README.md](../results/README.md).
5. Write what happened into the journal.

## Evaluate a folder again

Use this after a change in the evaluator, or to check that the CSV files still
match the raw data.

```bash
java -jar tools/experiment-runner/target/experiment-runner.jar --evaluate results/raw/20261115-163837_s2-smoke
```

Expected result:

```
Evaluated 3 runs (3 valid) in /…/results/raw/20261115-163837_s2-smoke
Saved /…/results/raw/20261115-163837_s2-smoke/runs.csv
Saved /…/results/raw/20261115-163837_s2-smoke/summary.csv
```

The command reads the protocol and the scenario from `campaign.json` of the
folder, the descriptors from its `approaches/` folder, and the four files of every
run. It overwrites `runs.csv` and `summary.csv` and touches neither the raw files
nor the cluster. The verdict stored in a meta file is ignored, every run is judged
again. It does not work on a folder under `results/aggregated/`, because the raw
files are not there.

## Add an approach

No class in `tools/experiment-runner/` changes in these steps.

1. Create `deploy/<name>/` with the source objects, the Deployment and the
   Service. Copy the fixed fields from an existing approach: 3 replicas, image
   `probe-service:0.2.0`, container name `probe`, requests and limits, readiness
   probe, topology spread constraint, and the Service. Do not set
   `CONFIG_GATE_ENABLED` in the manifest, the runner sets it.
2. Install the delivery components of the approach in `platform`. They stay
   running for every run of every approach.
3. Create `approaches/<name>/approach.json`. `reset` must contain
   `{initialVersion}` and `{initialTimeoutMs}`, `commit` must contain `{version}`
   and `{timeoutMs}` and write both values in one atomic change. Name every
   delivery component under `infrastructure`. Fill `declared`. The fields are
   described in [runner.md](runner.md#approach-descriptor).
4. Add the path of the descriptor to `approaches` in the protocol file. The runner
   takes the list of approaches from there.
5. Add a `docs/DECISIONS.md` entry for the configuration of the approach and the
   result of the tuning rule (the fastest setting that gave no stale replica and no
   failed request in pilot runs).
6. Run the S1 smoke campaign. `InvariantCheck` must pass, and the new approach
   must end VALID.
7. Run a campaign that contains the new approach and at least one approach that
   was measured before, as a control. If the control's medians fall outside their
   earlier intervals, the environment has drifted and all approaches are run
   again.

Step 4 changes the protocol file. The plan's list for adding an approach does not
name this step, and the plan says that a change of the protocol needs a new
protocol version. Decide this before the first approach is added.

If the new approach needs a library inside probe-service, the image changes for
every approach and all approaches are measured again on the new image.

## Add a scenario

1. Create `scenarios/<name>.json` with `name`, `title`, `gate`,
   `invalidVersions` and `steps`. The rules are in
   [runner.md](runner.md#scenario-file): offsets must not decrease, every commit
   needs a payload with its own version, an `infraDown` needs a later `infraUp`.
2. Create a campaign file and a smoke campaign file in `campaigns/` that name the
   scenario.
3. Run the smoke campaign.

A scenario file can only use the four actions `commit`, `infraDown`, `infraUp` and
`deletePod`, and it gets one of the three existing sets of metrics, chosen by its
structure: U1 to U5 with an `infraDown` step, E1 to E4 and R1 with invalid
versions, P1 to O5 otherwise. A new action or a new metric needs a change in the
runner and, by the plan, a new protocol version.

The run lasts 90 s plus the offset of the last step plus 150 s.

## Troubleshooting

A stopped campaign prints `Campaign stopped: <reason>` and ends with exit code 1.
The runs before the stop are on disk and `runs.csv` and `summary.csv` are written.
Fix the cause and start the campaign again. It begins a new folder with block 1.

| The reason starts with | Cause | What to do |
|---|---|---|
| `deleting the Deployment failed` or `waiting until the pods are gone failed` | The cluster is not reachable, or the old pods did not go away in 120 s | `kubectl get nodes`, then `kubectl -n thesis get pods` |
| `reset failed with exit code` | The approach could not put its source back to v1. For Way C the output shows the `git` error: no repository at `$CONFIG_REPO`, or the push was rejected | Run the command from the message by hand. For Way C check the [configuration repository](#4-the-configuration-repository-is-clean) |
| `deploy failed` | `kubectl apply -f` of a manifest failed | Apply the manifest by hand and read the error |
| `the rollout failed` or `the rollout after setting CONFIG_GATE_ENABLED failed` | The pods were not ready in 120 s. Often the image is not loaded, or for Way C the Config Server is down | `kubectl -n thesis describe pods`, then checks [2](#2-the-image-probe-service020-is-loaded) and [3](#3-the-platform-deployments-are-up) |
| `InvariantCheck found a difference: … the protocol says …` | A fixed field of the Deployment or Service differs from the protocol file | Correct the manifest of the approach. Do not change the protocol file |
| `InvariantCheck found a difference: infrastructure platform/… has …` | A delivery component has 0 replicas or is not ready, for example after an S3 run that was interrupted | `kubectl -n platform scale deployment/<name> --replicas=1`, then `kubectl -n platform rollout status deployment/<name>` |
| `InvariantCheck found a difference: image id is …` | The image was rebuilt and loaded during the campaign | Start the campaign again and do not rebuild during it |
| `the invariants could not be read` | A `kubectl get` failed | `kubectl get nodes` |
| `the state is not steady after 120 s` | Not all 3 pods answer v1 without failures and under 200 ms, or the pod names still change | `curl -s localhost:30080/probe` a few times. Look for a pod that restarts or a source that is not at v1 |
| `step … INFRA_DOWN`, `INFRA_UP` or `DELETE_POD failed` | A `kubectl` call of the core failed during the run. The run is not saved | Check the cluster, then the infrastructure as in the row above |
| `infrastructure was not restored` | After an S3 run a Deployment could not be scaled back or was not ready in 120 s. The run is saved | Scale it back by hand as above |
| `the evaluation of run … failed` | The evaluator threw an exception. The run is saved as INVALID with this reason | Keep the folder. Fix the evaluator, then use [`--evaluate`](#evaluate-a-folder-again) |

Other cases:

| Symptom | Cause | What to do |
|---|---|---|
| The runner ends at once with a Java exception that names a file and a field | A campaign, protocol, scenario or descriptor file has an unknown or missing field | Correct the file |
| Every run of one approach is INVALID with `commit of … failed` | The commit command of the approach fails | Run it by hand with the values of the payload |
| A Way C run of S3 has the flag `TRIGGER_FAILED` | The trigger runs inside the Config Server pod, which S3 has scaled to 0. The runner stops it after 15 s | Expected. The run is VALID, see [journal entry 08](journal/08-open-points-in-the-runner.md) |
| A run outside S3 is INVALID with `trigger after the commit of … failed with exit code 124` | The trigger ran longer than `triggerTimeoutSeconds` | Run the trigger command of the descriptor by hand and see why it is slow |
| A run has the flag `UNCONFIGURED` | A pod answered `v0`, which means it had no external configuration | Look at U2 and at the raw data of the run to see which pod and for how long |
| A run is INVALID with `versions that no payload of the scenario declares [v0]` | A pod answered `v0` in the baseline, or the campaign folder was saved before 19 November | The run is repeated by the campaign |
| A Way B run of S2 has the flag `NOT_RECOVERED` and every request fails to the end of the run | The Watcher sends no refresh to pods that are not ready, and the gate has made all three not ready | Known, see [journal entry 07](journal/07-way-b-gate-deadlock.md). To bring a pod back by hand, send `POST /actuator/refresh` to it through `kubectl port-forward` |
| A Way B run shows the new version less than 90 s after the commit | The Watcher was restarted less than 90 s before the commit. It sends a refresh 90 s after its start | Wait more than 90 s after a restart of the Watcher before you start a campaign |
| A run is INVALID with `baseline: …` | A pod was not observed for more than 1 000 ms, a request failed or two versions were seen before the first step | The run is repeated by the campaign. If it happens often, the machine is not quiet |

## Rollback

A campaign changes three things outside its result folder.

| What | State after a campaign | How to reset |
|---|---|---|
| Namespace `thesis` | The Deployment of the approach that ran last, at the last version of the scenario | `kubectl -n thesis delete deployment probe-service`, or deploy the Way you need |
| Infrastructure in `platform` | Back at the earlier replica counts, unless the restore failed | `kubectl -n platform scale deployment/<name> --replicas=1` |
| Configuration repository on GitHub | At the last version Way C committed | `bash tools/way-c-commit.sh v1 1000` |

If something in the results looks wrong and the cause is not in this runbook,
keep the campaign folder as it is and write down what you saw before changing
anything.
