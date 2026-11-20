# Runbook: Way D with Argo CD and Helm

Use this runbook to install Argo CD, to put the Helm chart of probe-service into
Git, to let Argo CD create the pods from it, and to change a configuration by
hand through a commit.

In Way D nobody applies the Deployment. Argo CD reads a Helm chart from the
branch `way-d` of the configuration repository and makes the namespace `thesis`
follow it. The design is in the [architecture](architecture.md#way-d-in-detail),
the reasons are the three entries of 2026-11-21 in
[docs/DECISIONS.md](../docs/DECISIONS.md).

| Component | Version | Role |
|---|---|---|
| Argo CD application controller | 3.5.8 | Compares Git with the cluster and applies the difference |
| Argo CD repo server | 3.5.8 | Fetches the repository and renders the chart with its own Helm 4.2.1 |
| Redis | from the Argo CD manifests | Cache of the two others |

## Prerequisites

| Requirement | Check |
|---|---|
| The cluster and the namespaces `thesis` and `platform` exist | `kubectl get nodes` shows four nodes, `kubectl get namespace thesis platform` shows two |
| The image `probe-service:0.2.0` is loaded | See the [runbook of experiment 1](runbook-experiment-1.md#2-the-image-probe-service020-is-loaded) |
| A clone of the configuration repository in `~/config-repo` with push access | `git -C ~/config-repo ls-remote -q origin HEAD` prints a commit hash without asking for a password |
| The cluster reaches GitHub | Argo CD fetches `https://github.com/AimenFH/config-delivery-config.git` |

All commands run from the repository root.

## 1. Install Argo CD (once)

```bash
kubectl apply --server-side -k deploy/platform/argocd
```

Expected result: one line per object, each ending in `serverside-applied`. The
option `--server-side` is needed because some of the definitions are too large
for a normal apply.

```bash
kubectl -n platform rollout status deployment/argocd-application-controller --timeout=240s
```

```bash
kubectl -n platform rollout status deployment/argocd-repo-server --timeout=240s
```

```bash
kubectl -n platform rollout status deployment/argocd-redis --timeout=240s
```

Expected result: `successfully rolled out` three times.

## 2. Put the chart on the branch `way-d` (once)

Skip this if `git ls-remote https://github.com/AimenFH/config-delivery-config.git way-d`
prints a line.

The branch gets its own folder next to the clone, so that `~/config-repo` stays
on `main` for Way C.

```bash
git -C ~/config-repo worktree add -b way-d ~/config-repo-way-d
```

Expected result: `Preparing worktree (new branch 'way-d')`.

```bash
mkdir -p ~/config-repo-way-d/probe-service && cp -R deploy/way-d-gitops/chart/. ~/config-repo-way-d/probe-service/
```

```bash
git -C ~/config-repo-way-d add probe-service && git -C ~/config-repo-way-d commit -m "Way D: Helm chart of probe-service"
```

Expected result: a line with `5 files changed`.

```bash
git -C ~/config-repo-way-d push -u origin way-d
```

Expected result: a line ending in `way-d -> way-d`.

Do not merge `way-d` into `main` and do not delete the branch on GitHub. Way C
commits go to `main`, Way D commits go to `way-d`.

## 3. Give Argo CD the Application

```bash
kubectl apply -f deploy/way-d-gitops/application.yaml
```

Expected result:

```
appproject.argoproj.io/thesis created
application.argoproj.io/way-d created
```

```bash
kubectl -n platform get application way-d
```

Expected result after about 35 s:

```
NAME    SYNC STATUS   HEALTH STATUS
way-d   Synced        Healthy
```

Before that it shows `OutOfSync` for a few seconds and then `Synced` with
`Progressing` while the pods start.

```bash
curl -s localhost:30080/probe
```

Expected result: a line with `"configVersion":"v1"`.

## 4. Change the configuration through Git

In a second terminal tab, watch the answers:

```bash
while true; do curl -s -H 'Connection: close' localhost:30080/probe; echo; sleep 0.5; done
```

In the first tab, commit the change and tell Argo CD to look:

```bash
bash tools/way-d-commit.sh v2 1000
```

Expected result: `pushed v2 with timeoutMs 1000 to way-d`.

```bash
kubectl -n platform annotate application way-d argocd.argoproj.io/refresh=normal --overwrite
```

Expected result: `application.argoproj.io/way-d annotated`.

What I saw on 2026-11-21, in seconds after the second command:

| After | What happened |
|---|---|
| 3 s | The Application is `OutOfSync` with the new commit |
| 7 s | `Synced`, the first new pod starts |
| 21 s | First answer with v2 |
| 49 s | Last answer with v1 |
| 50 s | `Healthy`, three new pods |

Without the second command Argo CD finds the commit by itself after some minutes.

## 5. Put v1 back

```bash
bash tools/way-d-reset.sh v1 1000
```

Expected result:

```
pushed v1 with timeoutMs 1000 to way-d
synced v1 at 89f9d0667b3b70a34b3bda764eecb188042bb582
```

The hash is the one of your commit. The script deletes the Application, creates
it again and waits until Argo CD has synced. It took 7 s. This is the `reset` the
runner calls before every Way D run.

## 6. Run Way D with the runner

Way D is the fourth entry of `approaches` in `protocol/protocol-v1.json`, so every
campaign contains it. Follow the [runbook of experiment 1](runbook-experiment-1.md).
Its checks before a campaign include the Argo CD Deployments and the folder
`~/config-repo-way-d`.

## Check that it works

```bash
kubectl -n platform get application way-d -o jsonpath='{.status.sync.status} {.status.health.status} {.status.sync.revision}{"\n"}'
```

Expected result: `Synced Healthy` and the hash of the last commit on `way-d`.

```bash
kubectl -n thesis get configmap demo-config-gitops -o jsonpath='{.data}{"\n"}'
```

Expected result: the three values of `values.yaml`, for example
`{"configVersion":"v1","timeoutMs":"1000","value":"hello"}`.

```bash
kubectl -n platform logs deployment/argocd-application-controller --since=10m | grep -c 'level=error'
```

Expected result: `0`.

## Settings of the two scripts

| Variable | Default | Meaning |
|---|---|---|
| `CONFIG_REPO_WAY_D` | `~/config-repo-way-d` | Folder with the branch `way-d` checked out |
| `CONFIG_BRANCH_WAY_D` | `way-d` | Branch the commit script expects and pushes |
| `RESET_TIMEOUT_SECONDS` | `120` | How long the reset waits for the sync |

## Problems

| Symptom | Cause | What to do |
|---|---|---|
| The Application shows `OutOfSync` and nothing happens | Another approach is deployed. Argo CD sees the difference and, without self-heal, leaves it | Nothing. This is the intended state during Way A, B and C |
| `way-d-commit.sh` prints `is on branch main, expected way-d` | The folder `~/config-repo-way-d` was switched to another branch | `git -C ~/config-repo-way-d switch way-d` |
| `git push` is rejected in one of the commit scripts | The branch on GitHub has commits the local folder does not have, for example after a merge on GitHub | `git -C ~/config-repo-way-d pull --ff-only`, or the same in `~/config-repo` for Way C |
| `way-d-reset.sh` prints `expected Synced at <hash>` after 120 s | Argo CD is not running, or it cannot reach GitHub | `kubectl -n platform get pods`, then `kubectl -n platform get application way-d -o jsonpath='{.status.conditions}'` |
| `Synced` and `Progressing` for minutes | A new pod does not become ready. I expect this for an invalid `timeoutMs` with the readiness gate on. It is not tested | `kubectl -n thesis get pods`, then commit a valid value |
| `kubectl apply` of Way A prints `service/probe-service configured` | Argo CD has applied the Service of the chart before. Type, ports and selector are the same | Nothing |

## Remove Way D

```bash
kubectl -n platform delete application way-d
```

The objects in `thesis` stay, because the Application has no finalizer. The next
run of any approach deletes the Deployment anyway.

Removing Argo CD itself was not tried. The command would be
`kubectl delete -k deploy/platform/argocd`. It also removes the definitions of
Application and AppProject, and with them every object of those kinds.
