#!/usr/bin/env bash
# Performance suite (local, non-Docker). Produced docs/results/phase-4/performance.txt.
#
#   [SCENARIOS=ABCD] tests/load/perf-suite.sh <gateway-jar> <env-file>
#
# Assumes PostgreSQL on :5432 (DB_URL/DB_PASSWORD exported), Redis on :6379, the five demo services
# running and registered (scripts/start-demo-services.sh + register-demo-services.sh). The gateway is
# (re)started by this script with the configuration each scenario needs; <env-file> supplies
# JWT_SECRET / ADMIN_* (never commit it).
set -uo pipefail

JAR=$1; ENV_FILE=$2
SCENARIOS="${SCENARIOS:-ABCD}"
want() { [[ "$SCENARIOS" == *"$1"* ]]; }
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
LOADTEST="$ROOT/tests/load/LoadTest.java"
WORK=$(mktemp -d)
TCK=$(getconf CLK_TCK)

gw_pid() { ss -ltnp | grep ':8080 ' | grep -o 'pid=[0-9]*' | cut -d= -f2 | head -1; }

restart_gateway() {
  local old; old=$(gw_pid)
  if [[ -n "$old" ]]; then kill "$old"; sleep 4; fi
  # shellcheck disable=SC1090  # the env file is a runtime argument
  (set -a; . "$ENV_FILE"; set +a; nohup java -jar "$JAR" "$@" > "$WORK/gateway.log" 2>&1 &)
  for _ in $(seq 1 90); do curl -sf localhost:8080/api/health > /dev/null && break; sleep 1; done
  sleep 8   # health checks mark instances HEALTHY; JIT warms up a little
}

load() { java "$LOADTEST" --url "$1" --requests "$2" --concurrency "$3" --timeout-ms 30000; }

run() { # label url requests concurrency
  local pid t0 t1 sampler out wall peak
  pid=$(gw_pid)
  t0=$(awk '{print $14+$15}' /proc/"$pid"/stat)
  ( while kill -0 "$pid" 2>/dev/null; do awk '/VmRSS/{print $2}' /proc/"$pid"/status; sleep 0.5; done ) > "$WORK/rss" & sampler=$!
  out=$(load "$2" "$3" "$4")
  t1=$(awk '{print $14+$15}' /proc/"$pid"/stat)
  kill "$sampler" 2>/dev/null; wait "$sampler" 2>/dev/null
  peak=$(sort -n "$WORK/rss" | tail -1)
  wall=$(echo "$out" | grep -o 'wall time=[0-9.]*' | cut -d= -f2)
  echo "### $1"
  echo "$out" | sed -n '3,7p'
  printf "  gateway process: CPU %.1f s (%.2f cores on average), peak RSS %d MB\n" \
    "$(echo "scale=2; ($t1-$t0)/$TCK" | bc)" "$(echo "scale=3; ($t1-$t0)/$TCK/$wall" | bc)" "$((peak / 1024))"
}

ORDERS=http://localhost:8080/gateway/orders/orders
RESTAURANTS=http://localhost:8080/gateway/restaurants/restaurants

echo "Host: $(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2 | xargs), $(nproc) hardware threads, $(free -m | awk '/Mem/{print $2}') MB RAM"
echo "Load average before: $(cut -d' ' -f1-3 /proc/loadavg). Gateway, 5 demo JVMs, PostgreSQL, Redis and the load generator share this machine."
echo

if want A; then
  echo "== A. Concurrency scale: rate limiting OFF, cache OFF, $ORDERS (weighted RR over 3 instances)"
  restart_gateway --gateway.rate-limit.enabled=false --gateway.cache.enabled=false
  load "$ORDERS" 3000 50 > /dev/null   # warmup, not measured
  for c in 100 500 1000 5000; do
    n=$(( c * 5 > 5000 ? c * 5 : 5000 ))
    run "A: $c concurrent users, $n requests" "$ORDERS" "$n" "$c"
  done
  echo
fi

if want B; then
  echo "== B. Rate limiting: 100 concurrent users, 5000 requests, all from one client IP"
  restart_gateway --gateway.rate-limit.enabled=false --gateway.cache.enabled=false
  run "B1: rate limiting OFF" "$ORDERS" 5000 100
  restart_gateway --gateway.rate-limit.enabled=true --gateway.rate-limit.store=REDIS --gateway.cache.enabled=false
  run "B2: rate limiting ON (token bucket 100/min per IP, Redis)" "$ORDERS" 5000 100
  echo
fi

if want C; then
  echo "== C. Response cache: 100 concurrent users, 5000 requests, $RESTAURANTS (backend sends max-age=30)"
  restart_gateway --gateway.rate-limit.enabled=false --gateway.cache.enabled=false
  load "$RESTAURANTS" 2000 50 > /dev/null
  run "C1: cache OFF" "$RESTAURANTS" 5000 100
  restart_gateway --gateway.rate-limit.enabled=false --gateway.cache.enabled=true
  load "$RESTAURANTS" 2000 50 > /dev/null
  run "C2: cache ON" "$RESTAURANTS" 5000 100
  echo "  cache counters after C2: $(curl -s localhost:8080/actuator/prometheus | grep '^gateway_cache_requests_total' | sed 's/application="smart-api-gateway",//' | tr '\n' ' ')"
  echo
fi

if want D; then
  echo "== D. Circuit breaker: orders instance :9003 answers after 8 s (gateway timeout 5 s); 50 users, 1500 requests"
  curl -s -X POST 'localhost:9003/_chaos?mode=SLOW&delayMs=8000' > /dev/null
  restart_gateway --gateway.rate-limit.enabled=false --gateway.cache.enabled=false --gateway.circuit-breaker.enabled=false
  run "D1: circuit breaker OFF (retries ON)" "$ORDERS" 1500 50
  restart_gateway --gateway.rate-limit.enabled=false --gateway.cache.enabled=false --gateway.circuit-breaker.enabled=true
  run "D2: circuit breaker ON (retries ON)" "$ORDERS" 1500 50
  curl -s -X POST 'localhost:9003/_chaos?mode=NONE' > /dev/null
  echo
fi

echo "Load average after: $(cut -d' ' -f1-3 /proc/loadavg)"
rm -rf "$WORK"
