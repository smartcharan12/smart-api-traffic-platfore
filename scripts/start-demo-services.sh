#!/usr/bin/env bash
# Starts the demo backends as separate processes:
#   orders-service      x3  on 9001, 9002, 9003
#   payments-service    x1  on 9101
#   restaurant-service  x1  on 9201
# Logs and PIDs go to .demo/ (git-ignored). Stop them with scripts/stop-demo-services.sh.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/services/demo-service/target/demo-service-0.1.0-SNAPSHOT.jar"
RUN_DIR="$ROOT/.demo"
mkdir -p "$RUN_DIR"

if [[ ! -f "$JAR" ]]; then
  echo "Building demo-service..."
  (cd "$ROOT/services/demo-service" && ./mvnw -q -B package -DskipTests)
fi

start() {
  local service="$1" port="$2"
  if [[ -f "$RUN_DIR/$port.pid" ]] && kill -0 "$(cat "$RUN_DIR/$port.pid")" 2>/dev/null; then
    echo "  $service already running on :$port"
    return
  fi
  # Small heap: five JVMs share one laptop.
  nohup java -Xmx128m -XX:+UseSerialGC -jar "$JAR" \
    --demo.service-name="$service" --server.port="$port" > "$RUN_DIR/$port.log" 2>&1 &
  echo $! > "$RUN_DIR/$port.pid"
  echo "  started $service on :$port (pid $!)"
}

echo "Starting demo services..."
start orders-service 9001
start orders-service 9002
start orders-service 9003
start payments-service 9101
start restaurant-service 9201

echo "Waiting for them to accept requests..."
for port in 9001 9002 9003 9101 9201; do
  for _ in $(seq 1 60); do
    curl -sf "localhost:$port/health" > /dev/null && break
    sleep 0.5
  done
  curl -sf "localhost:$port/health" > /dev/null || { echo "  :$port did not start, see $RUN_DIR/$port.log"; exit 1; }
done
echo "All demo services are up."
