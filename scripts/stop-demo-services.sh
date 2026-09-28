#!/usr/bin/env bash
# Stops everything started by start-demo-services.sh, or one port: stop-demo-services.sh 9003
set -uo pipefail

RUN_DIR="$(cd "$(dirname "$0")/.." && pwd)/.demo"
if [[ $# -gt 0 ]]; then
  ports=("$@")
else
  ports=(9001 9002 9003 9101 9201)
fi

for port in "${ports[@]}"; do
  pid_file="$RUN_DIR/$port.pid"
  if [[ -f "$pid_file" ]]; then
    kill "$(cat "$pid_file")" 2>/dev/null && echo "stopped :$port"
    rm -f "$pid_file"
  fi
done
