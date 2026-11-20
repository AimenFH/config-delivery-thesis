#!/usr/bin/env bash
set -euo pipefail
usage="usage: loki-load.sh <campaign folder>"
campaign=${1:?$usage}
jar=tools/experiment-runner/target/experiment-runner.jar
port=${LOKI_PORT:-3100}

kubectl -n monitoring port-forward svc/loki "$port:3100" >/dev/null 2>&1 &
tunnel=$!
trap 'kill "$tunnel" 2>/dev/null || true' EXIT

ready=no
for _ in $(seq 1 30); do
  if curl -sf "localhost:$port/ready" >/dev/null 2>&1; then
    ready=yes
    break
  fi
  sleep 1
done
if [ "$ready" != yes ]; then
  echo "Loki did not answer on localhost:$port" >&2
  exit 1
fi

java -jar "$jar" --loki "$campaign" --loki-url "http://localhost:$port"
