#!/usr/bin/env bash
# Compares the three load-balancing strategies on the SAME three orders instances (weights 3/2/1).
# Registers one route per strategy (/orders-rr, /orders-wrr, /orders-lc), then runs:
#   1. sequential traffic, all instances equally fast
#   2. concurrent traffic while instance 9003 is made slow (100 ms per request)
# Requires: demo services running (scripts/start-demo-services.sh) and the gateway started with
# rate limiting disabled (--gateway.rate-limit.enabled=false) so 429s don't distort the counts.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
GATEWAY="${GATEWAY:-http://localhost:8080}"
REQUESTS="${REQUESTS:-600}"

id_of() { grep -o '"id":[0-9]*' | head -1 | cut -d: -f2; }

register() {
  local name="$1" path="$2" strategy="$3" sid
  sid=$(curl -s -X POST "$GATEWAY/api/services" -H 'Content-Type: application/json' \
    -d "{\"name\":\"$name\",\"basePath\":\"$path\",\"loadBalancingStrategy\":\"$strategy\"}" | id_of || true)
  if [[ -z "$sid" ]]; then
    sid=$(curl -s "$GATEWAY/api/services" | grep -o "{\"id\":[0-9]*,\"name\":\"$name\"" | id_of)
  fi
  for spec in 9001:3 9002:2 9003:1; do
    curl -s -X POST "$GATEWAY/api/service-instances" -H 'Content-Type: application/json' \
      -d "{\"serviceId\":$sid,\"host\":\"localhost\",\"port\":${spec%%:*},\"weight\":${spec##*:}}" > /dev/null
  done
}

register orders-rr /orders-rr ROUND_ROBIN
register orders-wrr /orders-wrr WEIGHTED_ROUND_ROBIN
register orders-lc /orders-lc LEAST_CONNECTIONS

run() {
  java "$ROOT/tests/load/LoadTest.java" --url "$GATEWAY/gateway/$1/orders" --requests "$REQUESTS" --concurrency "$2"
}

echo "=== 1. Sequential ($REQUESTS requests, concurrency 1), all instances normal ==="
for route in orders-rr orders-wrr orders-lc; do run "$route" 1; done

echo
echo "=== 2. Concurrent ($REQUESTS requests, concurrency 20), instance 9003 slowed to 100 ms ==="
curl -s -X POST 'localhost:9003/_chaos?mode=SLOW&delayMs=100' > /dev/null
trap "curl -s -X POST 'localhost:9003/_chaos?mode=NONE' > /dev/null" EXIT
for route in orders-rr orders-wrr orders-lc; do run "$route" 20; done
