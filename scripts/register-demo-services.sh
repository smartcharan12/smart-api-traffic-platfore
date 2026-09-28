#!/usr/bin/env bash
# Registers the demo services and instances with the gateway's registry.
#   orders-service      /orders       WEIGHTED_ROUND_ROBIN, weights 3 / 2 / 1
#   payments-service    /payments     ROUND_ROBIN
#   restaurant-service  /restaurants  ROUND_ROBIN
# Usage: scripts/register-demo-services.sh [gateway-url]   (default http://localhost:8080)
set -euo pipefail

GATEWAY="${1:-http://localhost:8080}"

post() {
  curl -sf -X POST "$GATEWAY$1" -H 'Content-Type: application/json' -d "$2"
}

id_of() {
  grep -o '"id":[0-9]*' | head -1 | cut -d: -f2
}

register_service() {
  local name="$1" path="$2" strategy="$3" existing
  existing=$(curl -sf "$GATEWAY/api/services" | grep -o "{\"id\":[0-9]*,\"name\":\"$name\"" | id_of || true)
  if [[ -n "$existing" ]]; then
    echo "$existing"
  else
    post /api/services "{\"name\":\"$name\",\"basePath\":\"$path\",\"loadBalancingStrategy\":\"$strategy\"}" | id_of
  fi
}

register_instance() {
  local service_id="$1" port="$2" weight="$3"
  post /api/service-instances "{\"serviceId\":$service_id,\"host\":\"localhost\",\"port\":$port,\"weight\":$weight}" \
    > /dev/null && echo "  registered localhost:$port (weight $weight)" || echo "  localhost:$port already registered"
}

orders=$(register_service orders-service /orders WEIGHTED_ROUND_ROBIN)
echo "orders-service id=$orders"
register_instance "$orders" 9001 3
register_instance "$orders" 9002 2
register_instance "$orders" 9003 1

payments=$(register_service payments-service /payments ROUND_ROBIN)
echo "payments-service id=$payments"
register_instance "$payments" 9101 1

restaurants=$(register_service restaurant-service /restaurants ROUND_ROBIN)
echo "restaurant-service id=$restaurants"
register_instance "$restaurants" 9201 1
