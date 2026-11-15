#!/usr/bin/env bash
set -euo pipefail
usage="usage: way-c-commit.sh <version> <timeoutMs>"
version=${1:?$usage}
timeout=${2:?$usage}
repo=${CONFIG_REPO:-$HOME/config-repo}
file=probe-service.yml

cd "$repo"
tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT
sed -e "s/^  configVersion: .*/  configVersion: $version/" -e "s/^  timeoutMs: .*/  timeoutMs: $timeout/" "$file" > "$tmp"
cat "$tmp" > "$file"
if ! grep -q "^  configVersion: $version$" "$file" || ! grep -q "^  timeoutMs: $timeout$" "$file"; then
  echo "$file does not hold configVersion $version and timeoutMs $timeout" >&2
  exit 1
fi
if ! git diff --quiet HEAD -- "$file"; then
  git commit -qm "$version $timeout" -- "$file"
fi
git push -q
echo "pushed $version with timeoutMs $timeout"
