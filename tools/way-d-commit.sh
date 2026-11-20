#!/usr/bin/env bash
set -euo pipefail
usage="usage: way-d-commit.sh <version> <timeoutMs>"
version=${1:?$usage}
timeout=${2:?$usage}
repo=${CONFIG_REPO_WAY_D:-$HOME/config-repo-way-d}
branch=${CONFIG_BRANCH_WAY_D:-way-d}
file=probe-service/values.yaml

cd "$repo"
current=$(git rev-parse --abbrev-ref HEAD)
if [ "$current" != "$branch" ]; then
  echo "$repo is on branch $current, expected $branch" >&2
  exit 1
fi
tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT
sed -e "s/^  configVersion: .*/  configVersion: $version/" -e "s/^  timeoutMs: .*/  timeoutMs: \"$timeout\"/" "$file" > "$tmp"
cat "$tmp" > "$file"
if ! grep -q "^  configVersion: $version$" "$file" || ! grep -q "^  timeoutMs: \"$timeout\"$" "$file"; then
  echo "$file does not hold configVersion $version and timeoutMs $timeout" >&2
  exit 1
fi
if ! git diff --quiet HEAD -- "$file"; then
  git commit -qm "$version $timeout" -- "$file"
fi
git push -q origin "$branch"
echo "pushed $version with timeoutMs $timeout to $branch"
