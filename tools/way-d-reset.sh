#!/usr/bin/env bash
set -euo pipefail
usage="usage: way-d-reset.sh <version> <timeoutMs>"
version=${1:?$usage}
timeout=${2:?$usage}
here=$(cd "$(dirname "$0")" && pwd)
repo=${CONFIG_REPO_WAY_D:-$HOME/config-repo-way-d}
manifest=$here/../deploy/way-d-gitops/application.yaml
argo=${ARGO_NAMESPACE:-platform}
application=${ARGO_APPLICATION:-way-d}
namespace=${WORKLOAD_NAMESPACE:-thesis}
deployment=${WORKLOAD_DEPLOYMENT:-probe-service}
configmap=${WORKLOAD_CONFIGMAP:-demo-config-gitops}
limit=${RESET_TIMEOUT_SECONDS:-120}

bash "$here/way-d-commit.sh" "$version" "$timeout"
head=$(git -C "$repo" rev-parse HEAD)

kubectl -n "$argo" delete application "$application" --ignore-not-found --wait=true > /dev/null
kubectl apply -f "$manifest" > /dev/null

deadline=$((SECONDS + limit))
while true; do
  status=$(kubectl -n "$argo" get application "$application" -o jsonpath='{.status.sync.status}' 2> /dev/null || true)
  revision=$(kubectl -n "$argo" get application "$application" -o jsonpath='{.status.sync.revision}' 2> /dev/null || true)
  if [ "$status" = "Synced" ] && [ "$revision" = "$head" ] \
      && kubectl -n "$namespace" get deployment "$deployment" > /dev/null 2>&1; then
    break
  fi
  if [ "$SECONDS" -ge "$deadline" ]; then
    echo "application $application is ${status:-unknown} at ${revision:-no revision} after $limit s, expected Synced at $head" >&2
    exit 1
  fi
  sleep 1
done

delivered=$(kubectl -n "$namespace" get configmap "$configmap" -o jsonpath='{.data.configVersion}')
if [ "$delivered" != "$version" ]; then
  echo "configmap $configmap holds configVersion $delivered, expected $version" >&2
  exit 1
fi
echo "synced $version at $head"
