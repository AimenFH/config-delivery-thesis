# 10: Way D, GitOps with Argo CD and Helm

*20 and 21 November 2026, milestone 7*

## Summary

There is a fourth approach. In Way D a configuration change is a commit to a Helm
chart in Git, and Argo CD makes the cluster follow. Nobody applies the Deployment
by hand.

| | Result |
|---|---|
| Argo CD | Version 3.5.8 with three components in `platform`, no errors in its log |
| First creation from Git | Three pods ready about 35 s after the Application was applied |
| One change by hand, v1 to v2 | First answer with v2 after 21 s, last answer with v1 after 49 s, 443 requests, none failed |
| Gate setting of the runner | Still on the Deployment after a sync |
| Argo CD during Way A | No sync in four minutes, the Way A pods were not touched |
| Reset script | 7 s from no pods to synced at v1. The preparation steps of the runner passed by hand |
| Unit tests of the runner | 511, all pass. One is new, 24 failed first and were changed |
| Runs of Way D by the runner | None |

Claude Code wrote the manifests, the chart, the two scripts, the descriptor and
the test changes in four parts, and made the checks on the cluster. I pushed the
chart to GitHub myself.

How to use it is in the [Way D runbook](../runbook-way-d.md). The reasons are the
three entries of 2026-11-21 in [docs/DECISIONS.md](../../docs/DECISIONS.md).
[Previous entry](09-loki-and-grafana.md).

## What was built

| Part | What | Files |
|---|---|---|
| 1 | Argo CD: application controller, repo server and Redis in `platform` | `deploy/platform/argocd/kustomization.yaml` |
| 2 | Helm chart of probe-service, and the Application that points Argo CD to it | `deploy/way-d-gitops/chart/`, `deploy/way-d-gitops/application.yaml` |
| 3 | Commit script, reset script and descriptor | `tools/way-d-commit.sh`, `tools/way-d-reset.sh`, `approaches/way-d/approach.json` |
| 4 | Way D in the protocol file, unit tests for four approaches, documentation | `protocol/protocol-v1.json`, six test classes |

The chart is in Git twice: in this repository as a copy, and on the branch
`way-d` of the configuration repository, where Argo CD reads it.

## How one change travels

1. `tools/way-d-commit.sh v2 1000` changes two lines in `values.yaml` and pushes
   one commit to the branch `way-d`.
2. A refresh request tells Argo CD to look at Git. It stands for the webhook that
   GitHub would send.
3. The repo server fetches the branch and renders the chart with Helm.
4. The application controller compares the result with the cluster, sees that the
   ConfigMap and the Deployment differ, and applies both.
5. The pod template has a new value in the annotation `checksum/config`, the hash
   of the ConfigMap. For Kubernetes that is a new pod template, so it starts a
   rolling restart.
6. The new pods read the new values from environment variables when they start.

From step 5 on this is Way A. What differs is everything before it: the source is
Git, and Argo CD takes the place of Reloader.

## What Argo CD is asked to do, and what not

The Application lets Argo CD sync a new commit by itself. After that sync Argo CD
still compares the cluster with Git, but it corrects nothing (`selfHeal: false`).

I need that because there is one test system for all approaches. The runner sets
`CONFIG_GATE_ENABLED` on the Deployment by hand, and Way A, B and C deploy their
own Deployment with the same name. With self-heal on, Argo CD would undo the
second.

Argo CD checks that the cluster equals Git. It does not check that the values in
Git are right. A wrong `timeoutMs` is delivered like a right one, and the
readiness probe has to stop it.

## What the checks by hand showed

The change from v1 to v2, with one request every 0.2 s and times in whole
seconds:

| Time | What happened |
|---|---|
| 00:08:19 | The commit is on GitHub and the refresh request is sent |
| 00:08:22 | The Application is `OutOfSync` with the new commit |
| 00:08:26 | `Synced`, the first new pod starts |
| 00:08:40 | First answer with v2 |
| 00:09:08 | Last answer with v1 |
| 00:09:09 | `Healthy` |

The answers were mixed for about 28 s. These are numbers from a loop with `curl`,
not from the runner.

The gate: before the change I set `CONFIG_GATE_ENABLED=false` with
`kubectl set env`, as the runner does. Argo CD still showed `Synced`. After the
sync of v2 the variable was still there.

The other approaches: with Way A deployed, the Application showed `OutOfSync`.
In four minutes, with one forced refresh, the time of the last sync did not
change and the three Way A pods stayed the same. A Way A change to v2 then
restarted the pods through Reloader and all answered v2. The log of the
application controller had no sync line in that time.

## What broke

1. The ClusterRoleBinding pointed to the wrong namespace. The official
   manifest names the service account of Argo CD in the namespace `argocd`. The
   `namespace` line of the kustomization does not change that field. A patch in
   the kustomization now sets it to `platform`.
2. I merged the branch into `main`. After the push I opened a pull request on
   GitHub and merged `way-d` into `main`. It does no harm: the Config Server reads
   only `probe-service.yml`, and Argo CD reads only the branch. But my local
   `~/config-repo` was two commits behind, and the next Way C commit would have
   been rejected at the push. Claude Code saw it and ran `git pull --ff-only`.
   `main` now holds a copy of the chart that nothing uses.
3. 24 unit tests failed when Way D was added to the protocol file. They
   count runs, blocks and files for three approaches. The expected values are now
   those for four. No rule of the runner changed.
4. Way A now changes the Service. `kubectl apply -f deploy/way-a-envvar`
   prints `service/probe-service configured` where I expect `unchanged` without
   Argo CD. Type, ports and selector are the same as before. Which field differs
   is not confirmed.

## What was not checked

- The runner has not run Way D. Whether `InvariantCheck` passes and whether a run
  is VALID is open.
- S2. I expect one new pod that never becomes ready and three old pods on v1, as
  in Way A.
- S3. The trigger is a write to the Kubernetes API and works while Argo CD is
  down. I expect that the change arrives after `infraUp`, where Way C loses it.
- Whether Argo CD in the background changes the numbers of Way A, B and C.
- Argo CD during Way B and Way C. The check was made with Way A only.
- The resource metrics of Way D. The three Argo CD containers have no CPU or
  memory requests.
- Many pushes and fetches in a row. Way C and Way D each make two or three
  commits per run, and GitHub may limit that.
- That the two copies of the chart are the same after a later change.
- Removing Argo CD.

## Open points

1. The protocol file has a fourth approach and still says `protocolVersion` 1.
   The plan asks for a new version when the protocol changes. This is the same
   question as open point 1 of [entry 08](08-open-points-in-the-runner.md#open-points).
2. A smoke campaign with Way D, with Argo CD and the monitoring stack on, before
   the full campaigns. It also answers open point 1 of
   [entry 09](09-loki-and-grafana.md#open-points).
3. The plan asks for pilot runs and a tuning rule for a new approach. Way D has
   neither.
4. My supervisor has not seen the three decisions for Way D. The ones to discuss
   are self-heal off and the refresh request as trigger.
5. The full campaigns get longer: about 8.9 hours for S1 and 5.8 hours each for
   S2 and S3 with four approaches.
6. The other open points of entries 08 and 09 are unchanged.

## Next

1. Decide what is built next: the database or the Angular dashboard.
2. Read the chart, the two scripts and the Application file.
