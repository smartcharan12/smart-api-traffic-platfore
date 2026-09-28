#!/usr/bin/env bash
# Checks that a freshly started `docker compose up` stack actually works end to end.
# Reads ADMIN_USERNAME / ADMIN_PASSWORD from .env. Used by CI; runnable locally too.
set -euo pipefail

cd "$(dirname "$0")/.."
# shellcheck disable=SC1091
set -a && source .env && set +a
GATEWAY=http://localhost:8080

wait_for() { # description, command...
  local what="$1"; shift
  for _ in $(seq 1 90); do
    if "$@" > /dev/null 2>&1; then echo "ok   $what"; return 0; fi
    sleep 2
  done
  echo "FAIL $what"; return 1
}

wait_for "gateway healthy" curl -fs "$GATEWAY/api/health"
wait_for "demo services registered" bash -c "docker compose ps -a demo-setup --format '{{.State}} {{.ExitCode}}' | grep -q 'exited 0'"

TOKEN=$(curl -fs -X POST "$GATEWAY/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$ADMIN_USERNAME\",\"password\":\"$ADMIN_PASSWORD\"}" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
[[ -n "$TOKEN" ]] && echo "ok   admin login"

instances=$(curl -fs -H "Authorization: Bearer $TOKEN" "$GATEWAY/api/service-instances" | grep -o '"id":' | wc -l)
[[ "$instances" -ge 5 ]] && echo "ok   $instances instances registered"

wait_for "orders routed through the gateway" bash -c "curl -fs $GATEWAY/gateway/orders/orders | grep -q '\"service\":\"orders-service\"'"
wait_for "instances probed HEALTHY" bash -c "curl -fs -H 'Authorization: Bearer $TOKEN' $GATEWAY/api/health/instances | grep -q '\"status\":\"HEALTHY\"'"
[[ "$(curl -s -o /dev/null -w '%{http_code}' "$GATEWAY/api/services")" == 401 ]] && echo "ok   management API requires a token"
wait_for "Redis in use (health UP, not DEGRADED)" bash -c "curl -fs $GATEWAY/api/health | grep -q '\"status\":\"UP\"'"
wait_for "Prometheus metrics exposed" bash -c "curl -fs $GATEWAY/actuator/prometheus | grep -q gateway_requests_seconds_count"
wait_for "Prometheus scraping the gateway" bash -c "curl -fs 'http://localhost:9090/api/v1/query?query=up' | grep -q '\"value\":\\[[^]]*,\"1\"\\]'"
wait_for "Grafana dashboard provisioned" bash -c "curl -fs 'http://localhost:3000/api/search?query=Smart' | grep -q smart-api-gateway"
wait_for "dashboard served by nginx" bash -c "curl -fs http://localhost:5173/ | grep -q '<div id=\"root\">'"
wait_for "dashboard proxies /api to the gateway" curl -fs http://localhost:5173/api/health
echo "Smoke test passed."
