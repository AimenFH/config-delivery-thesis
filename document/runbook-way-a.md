# Runbook: Way A experiment

## When to use this runbook

Use it to run the Way A experiment by hand: deploy three replicas that read their
configuration from environment variables, change the configuration, trigger a
rolling restart and watch the inconsistency window.

## Prerequisites

| Requirement | Check |
|---|---|
| Docker Desktop running | `docker info` succeeds |
| kind installed | `kind version` |
| kubectl installed | `kubectl version --client` |
| Java 21+ and Maven | `java -version`, `mvn -v` |
| Port 30080 free on the machine | `lsof -i :30080` prints nothing |

All commands run from the repository root unless stated otherwise.

## 1. Create the cluster (once)

Skip this if `kind get clusters` already lists `thesis`.

```bash
kind create cluster --config deploy/kind/kind-cluster.yaml
```

```bash
kubectl get nodes
```

Expected result: four nodes, all `Ready`: `thesis-control-plane`, `thesis-worker`,
`thesis-worker2`, `thesis-worker3`.

## 2. Build and deploy

Repeat the build steps after every code change.

```bash
(cd services/probe-service && mvn -q -DskipTests package && docker build -t probe-service:0.1.0 .)
```

```bash
kind load docker-image probe-service:0.1.0 --name thesis
```

```bash
kubectl apply -f deploy/namespace.yaml && kubectl apply -f deploy/way-a-envvar/
```

```bash
kubectl -n thesis rollout status deployment/probe-service
```

```bash
kubectl -n thesis get pods -o wide
```

Expected result: three pods `1/1 Running`, one on each worker node.

```bash
for i in $(seq 1 6); do curl -s localhost:30080/probe; echo; done
```

Expected result: `"configVersion":"v1"` from three different pod names.

## 3. Run the experiment

Open two terminals.

In terminal 1, start the observation loop:

```bash
while true; do curl -s localhost:30080/probe | grep -o '"configVersion":"[^"]*","pod":"[^"]*"'; sleep 0.2; done
```

In terminal 2, change the configuration:

```bash
kubectl -n thesis patch configmap demo-config -p '{"data":{"configVersion":"v2"}}'
```

Expected result: terminal 1 keeps showing `v1`. Environment variables are only read
at process start.

In terminal 2, activate the change with a rolling restart:

```bash
kubectl -n thesis rollout restart deployment/probe-service
```

```bash
kubectl -n thesis rollout status deployment/probe-service
```

Expected result: terminal 1 shows `v1` and `v2` mixed, then only `v2`. New pods have a
new hash in their name. With three replicas the mix lasted about 18 seconds in the
first run.

Stop terminal 1 with `Ctrl+C`.

> This loop shows the effect but cannot measure it: it has no timestamps, waits for
> each answer, and `grep` hides failed answers. Measurements come from the
> experiment runner.

## 4. Reset (rollback)

Delete everything the experiment created. The cluster and the loaded image stay.

```bash
kubectl delete namespace thesis
```

Then continue with the `kubectl apply` steps in section 2. The ConfigMap comes back
as `v1` from the file.

> `kubectl apply` alone does not undo a `kubectl patch`: apply only changes
> fields that differ from what was last applied. Always reset by deleting the
> namespace.

## 5. Remove the cluster

```bash
kind delete cluster --name thesis
```

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `pull access denied for probe-service` on `docker run` | The image was never built, so Docker looked on Docker Hub | Run the build step in section 2 |
| Pods in `ErrImagePull` or `ImagePullBackOff` | The image is not inside the kind nodes, or the name/tag differs from the manifest | `kind load docker-image probe-service:0.1.0 --name thesis`; check `image:` in the Deployment |
| A pod stays `Pending` with *"didn't match pod topology spread constraints"* | The spread rule counts the tainted control-plane node | The Deployment needs `nodeTaintsPolicy: Honor` (see [DECISIONS](../docs/DECISIONS.md)) |
| Two new pods on the same node after a rollout | The spread rule counts old pods too | The Deployment needs `matchLabelKeys: [pod-template-hash]` |
| Rollout waits forever, old pods still serve `v1` | A new pod cannot start or become ready | `kubectl -n thesis describe pod <pending-pod>` and read the Events section |
| `curl localhost:30080` connection refused | Cluster created without the port mapping, or Service is not NodePort | Recreate the cluster with `deploy/kind/kind-cluster.yaml`; check `kubectl -n thesis get svc` shows `80:30080/TCP` |
| Every answer comes from the same pod | The client reuses one connection | Use a new connection per request (`curl` does this by default) |
| Code change not visible in the cluster | Same tag `0.1.0` reused, pods still run the old image | Rebuild, `kind load`, then `kubectl -n thesis rollout restart deployment/probe-service` |
| `Whitelabel Error Page` with 404 | You opened `/`, which has no mapping | Use `/probe` or `/config-health` |

## Useful commands

| Command | Shows |
|---|---|
| `kubectl -n thesis get pods -o wide -w` | Pods being replaced, live (`Ctrl+C` to stop) |
| `kubectl -n thesis get events --sort-by=.lastTimestamp` | What Kubernetes did, in order |
| `kubectl -n thesis get rs` | One ReplicaSet per Deployment revision |
| `kubectl -n thesis get endpointslices` | Which pod IPs currently receive traffic |
| `docker exec thesis-worker crictl images` | Images available on a node |
