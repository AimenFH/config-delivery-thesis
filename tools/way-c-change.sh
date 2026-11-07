#!/usr/bin/env bash
set -euo pipefail
new=${1:?usage: way-c-change.sh <version>}
repo=${CONFIG_REPO:-$HOME/config-repo}

cd "$repo"
sed -i '' "s/^  configVersion: .*/  configVersion: $new/" probe-service.yml
grep -q "^  configVersion: $new$" probe-service.yml
git commit -qam "$new"
git push -q
kubectl -n platform exec deploy/config-server -- curl -sf -X POST localhost:8888/actuator/busrefresh
echo "pushed $new and sent busrefresh"
