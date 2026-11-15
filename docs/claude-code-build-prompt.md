# Build prompt for Claude Code: experiment protocol version 1

Paste everything below the line into Claude Code, started in the repository root.

---

You are the orchestrator for building the experiment described in
`docs/experiment-plan.md`. Read that file, `CLAUDE.md`, `docs/DECISIONS.md` and
`document/runner.md` completely before you do anything else.

## Permission for this session

`CLAUDE.md` says I write the project code myself. For this session I explicitly
ask you to write the code. This is a test of an agent build. Every other rule in
`CLAUDE.md` still applies: no comments in code, reasoning goes into
`docs/DECISIONS.md` and `document/`, documentation uses the two skills named there.

## The design is frozen

`docs/experiment-plan.md` is protocol version 1 and it is final. We stick to it.

- You design nothing new. No extra metric, scenario, approach, constant, phase,
  flag or validity rule, and none removed or renamed.
- You do not change the plan file. If you think the plan is wrong or cannot be
  built as written, stop, tell me the exact sentence and the problem, and wait.
- Do not offer alternatives, improvements or simplifications during the build.
  Collect them in the final report under "Ideas not built".
- Every subagent brief starts with the three rules above, word for word.
- The reviewer in phase 3 treats any difference between code and plan as a
  defect in the code.

Freedom you do have: class internals, method names inside the contracts, test
design, and how you split work between agents, as long as the plan holds.

## Safety rules

1. Work on a new branch `experiment-protocol-v1`. Never commit to `main`, never
   push, never force anything.
2. Do not touch `results/raw/`. Do not delete files. Move superseded files with
   `git mv`.
3. The only change allowed in `services/probe-service/` is the readiness gate
   of the plan, behind `CONFIG_GATE_ENABLED`, with the version raised to 0.2.0.
   In the Deployments only the image tag changes. No other fixed field.
4. Do not add libraries. The runner uses Java 21, Jackson and JUnit 5 only.
5. Do not install anything into the cluster. Loading the probe-service image
   with `kind load docker-image` is allowed. The `platform` namespace is changed
   in one way only: scenario S3 scales the Deployments of an approach to 0 and
   back during a run. Anything else, stop and ask me.
6. The specification is `docs/experiment-plan.md`. Symbols, formulas, constants
   and validity rules are binding. If the code has to deviate, stop and tell me
   why before you continue.
7. Commit after every phase with a message that names the phase.

## Decisions already taken

They are the same as the section "Decisions" of the plan and they are closed.
Record each one as an entry in `docs/DECISIONS.md` (Problem / Rejected / Chosen /
Cost) in phase 0, dated today.

| # | Decision |
|---|---|
| 1 | Fresh deployment at v1 before every run. The change is always v1 to v2 |
| 2 | Way C keeps reading from the remote Git repository on GitHub, permanently, because that is how a Config Server is run in practice. The network latency is part of Way C's result and is recorded as a limitation. The descriptor gets an optional `sourceCheck` command that the runner times before every run and stores in the meta file. Way C uses `git ls-remote`. The core must not know which approach uses it |
| 3 | `T0` is the end of `commit`. `trigger` counts as delivery time |
| 4 | Observation 150 s, baseline 60 s, warm-up 30 s, settle 100 s, 20 valid runs per approach, at most 25 blocks |
| 5 | Resource data comes from the kubelet endpoint `/metrics/resource` through `kubectl get --raw`. No metrics-server |
| 6 | No pass or fail thresholds in this build |
| 7 | A coverage gap inside the observation interval sets the flag `LOW_RESOLUTION` and no longer makes the run invalid. This replaces part of the entry of 2026-11-07 |
| 8 | Three scenarios on the same core: S1 valid change (20 valid runs), S2 invalid configuration then fix (10), S3 infrastructure outage (10), with the steps and offsets of the plan |
| 9 | Readiness gate in probe-service behind `CONFIG_GATE_ENABLED`, image 0.2.0 for all scenarios. S2 runs with the gate on only |
| 10 | A commit carries a payload of `configVersion` and `timeoutMs`, applied in one atomic change |

## How to use agents

You coordinate. Subagents write the code. Give every subagent a brief that
contains: the sections of the plan it implements, the files it owns, the files
it must not edit, the contracts from phase 1, and the check it has to pass. A
subagent returns a short report: files changed, tests added, test result, open
questions.

Two agents never edit the same file at the same time. In phase 2 the agents run
in parallel, each in its own git worktree, and you merge them one after the
other. If a merge conflicts, resolve it yourself and run the tests again.

Tests are designed in parallel too. In phase 2 every agent writes the tests for
its own part from the plan, before or together with the code, and the expected
values come from the formulas of the plan, calculated by hand in the test. A
test that only repeats what the code does is not accepted. In phase 3 a
separate agent `test-audit` with a fresh context gets the plan and the test
sources only, and lists every metric, flag, instrument check, scenario step and
fairness rule of the plan that has no test. You close those gaps before the
reviewer runs.

### Phase 0: preparation (you, no subagent)

- Create the branch.
- Run `cd tools/experiment-runner && mvn -q package` and confirm the existing
  tests pass. If they fail, stop.
- Write the ten decision entries.
- Check whether the cluster is reachable: `kubectl get nodes`. Note the result
  for phase 4. Do not start or create a cluster.

### Phase 1: contracts (one subagent, sequential)

Agent `contracts`. Owns only new files plus `CampaignConfig.java` and
`RunSettings.java`.

- `Protocol` record, read from one JSON file, with validation. It holds every
  constant of the plan's table, the namespace, the deployment name, the
  change (`initialVersion` v1, `targetVersion` v2), the
  seed, and the list of approach descriptor paths.
- `Phases`: computes warm-up, baseline `B` and observation `H` from `t_cs`, and
  assigns a request to an interval by its intended send time.
- `ApproachDescriptor` record as in the plan, with validation. It must have no
  timing, rate, replica or validity field. Commands use the placeholders
  `{version}`, `{timeoutMs}`, `{initialVersion}` and `{initialTimeoutMs}`.
  Infrastructure entries name a namespace and a Deployment.
- `Payload` (`configVersion`, `timeoutMs`) and `Scenario` record, read from a
  file in `scenarios/`, with validation: gate on or off, the payloads, the
  invalid versions, and timed steps from the actions `commit`, `infraDown`,
  `infraUp` and `deletePod`. Every payload version must be unique.
- `DeliveryAdapter` interface with `deploy`, `reset`, `commit`, `trigger`,
  `infrastructure`, `declared`, and `CommandAdapter` as the one implementation.
  It reuses `ChangeTrigger` to run commands and replaces the placeholders.
  `commit` takes a `Payload`.
- `RunRecord`: everything one run produces before evaluation (settings, `t_cs`,
  `T0` of every commit, the start and end time of every step, exit codes,
  observations, resource samples, pod lists).
- `RunMetrics` and `Metric`: the registry type the evaluator fills, with id,
  criterion, unit and a primary flag, using the ids P1 to O5 of the plan.
- `intendedAt` added to `Observation`, set by `LoadGenerator` as `seq × period`.
- Files: `approaches/way-a/approach.json`, `approaches/way-b/approach.json`,
  `approaches/way-c/approach.json`. Way A and Way B patch both keys of their
  ConfigMap in one `kubectl patch`.
- Files: `scenarios/s1-valid-change.json`, `scenarios/s2-invalid-config.json`,
  `scenarios/s3-infra-outage.json`, with the steps of the plan.
- Files: one campaign file per scenario, `campaigns/s1.json` (20 valid runs),
  `campaigns/s2.json` and `campaigns/s3.json` (10 valid runs each), all with at
  most 25 blocks, and a smoke file for each: `campaigns/s1-smoke.json` with
  2 blocks, `campaigns/s2-smoke.json` and `campaigns/s3-smoke.json` with 1 block.
  Move the old campaign files to `campaigns/legacy/`.
- Split `tools/way-c-change.sh` into `tools/way-c-commit.sh` (edit both values,
  one commit, push, no refresh) and the `trigger` command in the descriptor.
  Make the `sed` call work on macOS and Linux.

Check: unit tests for `Protocol`, `Phases`, `Scenario` and `ApproachDescriptor`
validation.
`mvn -q package` passes. You review the contracts yourself before phase 2,
because every later agent builds on them.

### Phase 2: four subagents in parallel

Agent `campaign`. Owns `Campaign.java`, `ClusterControl.java`, `Runner.java`,
new `InvariantCheck.java`, new `Fingerprint.java`.

- Run procedure of the plan, steps 1 to 9: delete the Deployment, adapter
  reset, adapter deploy, rollout, settle, invariants, steady check with stable
  pod names in two snapshots 10 s apart, load, the steps of the scenario
  starting at 90 s, observation, save.
- Step executor for the four actions. It records start and end of every step on
  the runner clock. After `deploy` it sets `CONFIG_GATE_ENABLED` on the
  Deployment to the value of the scenario and waits for the rollout.
- `infraDown` remembers the replica count of every Deployment it scales.
  `infraUp` restores it. The restore also runs in a `finally` block, and the
  next run starts only when the infrastructure is ready again.
- Randomized blocks from the seed, immediate repeat of an invalid run at most
  twice per block, stop at 20 valid runs per approach or 25 blocks.
- `InvariantCheck` compares the live Deployment and Service with the fixed
  fields of the plan and stops the campaign on a difference.
- `Fingerprint` writes `fingerprint.json`.
- No approach name, ConfigMap name or key may remain in these classes.

Check: unit tests for the block order (same seed gives the same order), for
`InvariantCheck` against JSON fixtures, for the retry logic with a fake
adapter, and for the step executor with a fake adapter, including the restore
after a failing step. `grep -rn -E "way-|versionKey|patchCommand|demo-config" src/main`
finds nothing.

Agent `evaluator`. Owns `WindowDetector.java`, `CoverageCheck.java`,
`Summary.java`, `ResultWriter.java`, new `Evaluator.java` and one new class per
criterion.

- `T0` as reference point for P1 and P2, P3 as commit duration.
- Instrument checks and outcome flags exactly as in the plan's failure table.
  A run without convergence is VALID with `NOT_CONVERGED` and a censored P1.
- Metrics C1 to C3, A1 to A3, F1 to F3, O1 to O5 as pure functions of a
  `RunRecord` and an interval.
- Metrics E1 to E4 and R1 for S2, U1 to U5 for S3, with the flags
  `NOT_RECOVERED` and `LOST_UPDATE`. The scenario decides which metrics are
  reported.
- Statistics: n, min, quartiles, median, max, bootstrap interval of the median
  with 10 000 resamples and a stored seed, difference of medians and Cliff's
  delta between approaches for the primary metrics.
- `runs.csv` and `summary.csv`, and a `--evaluate <campaign dir>` argument that
  rebuilds both from raw files only.

Check: for every metric at least one unit test with a hand-built observation
list and a hand-calculated expected value. Tests for each instrument check and
each flag. Evaluating the same folder twice gives identical files.

Agent `resources`. Owns new `ResourceSampler.java`, new
`ResourceMetricsParser.java`.

- First, if the cluster is reachable, run
  `kubectl get --raw /api/v1/nodes/<node>/proxy/metrics/resource` for one node
  and save the output as a test fixture. If the cluster is not reachable, build the
  parser against the documented Prometheus text format of that endpoint, mark
  this clearly in your report, and do not claim it was verified. If the cluster
  is reachable and the endpoint does not work, stop and ask me, as decision 5 of
  the plan says.
- Sampler: every 5 s, all nodes, CPU seconds and working set per container,
  written as `<run id>.resources.ndjson`. Sampling must not block the load
  generator.
- CPU use is the sum of positive counter increments per container between
  consecutive samples, so a replaced pod does not lose its history.

Check: parser tests against the fixture, a test for the increment logic with a
container that disappears and one that appears.

Agent `gate`. Owns `services/probe-service/` and the image tag in the three
`deploy/way-*/deployment.yaml` files.

- A health indicator with the rules of `ConfigHealth`, in the readiness group
  only when `CONFIG_GATE_ENABLED` is `true`. It must see refreshed values after
  `/actuator/refresh` and after a Bus refresh.
- `/config-health` and `/probe` keep their behaviour. Version 0.2.0.
- If Docker and the cluster are reachable: build the image, load it with
  `kind load docker-image probe-service:0.2.0 --name thesis`. Otherwise report
  the two commands for me to run.

Check: tests that readiness is unchanged with the gate off, and is down with
the gate on and `timeoutMs` set to `abc`, also after a refresh at runtime.

### Phase 3: integration and review

- You merge the four worktrees, wire sampler, evaluator and campaign together
  in `Runner` and `Campaign`, and run all tests.
- Then start agent `reviewer` with a fresh context. It gets only
  `docs/experiment-plan.md` and the diff of the branch against `main`. Its task:
  find every place where the code deviates from the plan, where an approach
  could influence the measurement, where a run could be excluded because of its
  outcome, and where a metric formula differs. It reports findings with file
  and line and changes nothing.
- You fix confirmed findings and list the ones you rejected with the reason.

### Phase 4: run on the cluster

Only if `kubectl get nodes` showed the kind cluster with 3 workers and the
`platform` namespace is running. Otherwise skip this phase and say so.

- Run the three smoke campaigns one after the other with
  `java -jar tools/experiment-runner/target/experiment-runner.jar --campaign campaigns/s1-smoke.json`,
  then `s2-smoke.json`, then `s3-smoke.json`. Together they take about
  1.5 hours.
- Way C pushes commits to my configuration repository on GitHub. That is
  expected. If the push fails, mark Way C as not verified and continue with
  Way A and Way B.
- Compare S1 with the old scenario 1 as a plausibility check: Way A convergence
  near 28 s, Way B near 90 s, Way C a few seconds and higher than the old 1.6 s
  because the trigger now counts.
- For S2 and S3 there are no expected values. Report what each approach did:
  exposed replicas and requests, failed requests, recovery or `NOT_RECOVERED`,
  catch-up or `LOST_UPDATE`. Do not adjust the code to make an approach look
  better. An approach that does not recover is a result.
- After S3, confirm that every Deployment in `platform` is back at its earlier
  replica count and ready.
- Do not start `campaigns/s1.json`, `s2.json` or `s3.json`. I start the long
  campaigns myself.

### Phase 5: documentation (one subagent)

Agent `docs`, after everything else. It follows the documentation rules of
`CLAUDE.md` and loads both skills.

- `document/runner.md`: new arguments, files, the protocol file and the
  scenario files.
- `document/probe-api.md`: the readiness gate.
- `document/architecture.md`: adapter boundary, with the diagram from the plan.
- New `document/runbook-experiment-1.md`: how to run the three campaigns, how
  to add an approach, how to add a scenario.
- New `document/journal/06-experiment-protocol.md` and the status table in
  `document/README.md`.
- New `document/code-walkthrough.md`, written for me to learn the code and
  defend it in the thesis. For every class: what it does, what goes in and out,
  which section of the plan it implements, and the one thing that is easy to get
  wrong in it. For every metric: the formula and one worked example with
  numbers. For every scenario: the timeline and what each approach did in the
  smoke run, with the numbers from the raw data. Then one path through the code
  for a single S1 run, from `main` to `summary.csv`, naming each method in
  order.
- `results/README.md`: new layout.

## Final report

End with one report to me:

1. What was built, per phase, with the commit of each phase.
2. Test count and result.
3. What was verified on the real cluster and what was only unit tested. Be exact
   about the resource endpoint, the readiness gate, Way C, and each of the
   three scenarios.
4. Every deviation from `docs/experiment-plan.md` and why.
5. Reviewer findings: fixed, rejected, open.
6. What I have to do before the full campaigns.
7. Ideas not built: what you would have changed, one line each. Nothing from
   this list may be in the code.

Explain every point in detail and in plain language. I have to understand and
defend all of it.

If you are blocked or unsure about a rule of the plan, stop and ask. A partial
build with an honest report is better than a complete one that guesses.
