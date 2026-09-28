#!/usr/bin/env bash
# The central demo: "Service B" (orders instance on :9002) fails and recovers under live traffic,
# and the timeline of its health status and circuit-breaker state is printed every 2 s.
#
#   ADMIN_USERNAME=... ADMIN_PASSWORD=... scripts/demo-failure-scenario.sh [gateway-url]
#
# Requires: gateway + demo services running and registered (scripts/register-demo-services.sh).
# Timeline: normal -> UNAVAILABLE (503, health probes fail too) -> RECOVER.
set -euo pipefail

GATEWAY="${1:-http://localhost:8080}"
: "${ADMIN_USERNAME:?set ADMIN_USERNAME}" "${ADMIN_PASSWORD:?set ADMIN_PASSWORD}"
TARGET_PORT="${TARGET_PORT:-9002}"

TOKEN=$(curl -sf -X POST "$GATEWAY/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$ADMIN_USERNAME\",\"password\":\"$ADMIN_PASSWORD\"}" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
[[ -n "$TOKEN" ]] || { echo "login failed"; exit 1; }
AUTH=(-H "Authorization: Bearer $TOKEN")

INSTANCE_ID=$(curl -sf "${AUTH[@]}" "$GATEWAY/api/health/instances" | python3 -c "
import sys, json
rows = [i for i in json.load(sys.stdin)['instances'] if i['serviceName'] == 'orders-service' and i['address'].endswith(':$TARGET_PORT')]
print(rows[0]['instanceId'] if rows else '')")
[[ -n "$INSTANCE_ID" ]] || { echo "orders-service instance on :$TARGET_PORT not registered"; exit 1; }

simulate() {
  curl -sf -X POST "$GATEWAY/api/simulations" "${AUTH[@]}" -H 'Content-Type: application/json' \
    -d "{\"instanceId\":$INSTANCE_ID,\"scenario\":\"$1\"}" > /dev/null
}

state() {
  curl -sf "${AUTH[@]}" "$GATEWAY/api/health/instances" | python3 -c "
import sys, json
i = [r for r in json.load(sys.stdin)['instances'] if r['instanceId'] == $INSTANCE_ID][0]
print(f\"health={i['status']:<9} circuit={i['circuitState']:<9} failures_in_a_row={i['consecutiveFailures']}\")"
}

STOP=$(mktemp); rm -f "$STOP"
for w in 1 2 3; do
  (while [[ ! -f $STOP ]]; do curl -s -o /dev/null "$GATEWAY/gateway/orders/orders"; sleep 0.1; done) &
done
trap 'touch $STOP; wait; rm -f $STOP; simulate RECOVER || true' EXIT

echo "Service B = orders-service instance :$TARGET_PORT (id $INSTANCE_ID), background traffic running"
for t in $(seq 0 2 70); do
  event=""
  [[ $t -eq 10 ]] && { simulate UNAVAILABLE; event="<- simulate: Service B UNAVAILABLE (503, health probes fail)"; }
  [[ $t -eq 44 ]] && { simulate RECOVER; event="<- simulate: Service B RECOVER"; }
  printf "t=%3ss  %s %s\n" "$t" "$(state)" "$event"
  sleep 2
done
echo
echo "Recent circuit transitions:"
curl -sf "${AUTH[@]}" "$GATEWAY/api/circuit-breakers" | python3 -c "
import sys, json
for t in reversed(json.load(sys.stdin)['recentTransitions'][:8]):
    if t['address'].endswith(':$TARGET_PORT'): print(f\"  {t['at'][11:19]}  {t['from']:>9} -> {t['to']:<9} {t['reason']}\")"
