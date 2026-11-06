#!/usr/bin/env bash
set -euo pipefail
new=$1
now() { python3 -c 'import time; print(time.time())'; }
start=$(now)
kubectl -n thesis patch configmap demo-config --type merge -p "{\"data\":{\"demo.configVersion\":\"$new\"}}" >/dev/null
for pod in $(kubectl -n thesis get pods -l app=probe-service -o name); do
  (
    until [ "$(kubectl -n thesis exec "${pod#pod/}" -- cat /etc/config/demo.configVersion)" = "$new" ]; do
      sleep 0.5
    done
    echo "${pod#pod/} $(python3 -c "print(round($(now) - $start, 1))") s"
  ) &
done
wait