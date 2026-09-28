#!/usr/bin/env bash
# Registers the demo services and instances with the gateway's registry (as ADMIN).
#   orders-service      /orders       WEIGHTED_ROUND_ROBIN, weights 3 / 2 / 1
#   payments-service    /payments     ROUND_ROBIN
#   restaurant-service  /restaurants  ROUND_ROBIN
#
# Usage: ADMIN_USERNAME=... ADMIN_PASSWORD=... scripts/register-demo-services.sh [gateway-url]
# Instance lists ("host:port:weight ...") can be overridden, e.g. by docker-compose:
#   ORDERS_INSTANCES, PAYMENTS_INSTANCES, RESTAURANT_INSTANCES   (default: localhost ports)
set -euo pipefail

GATEWAY="${1:-http://localhost:8080}"
: "${ADMIN_USERNAME:?set ADMIN_USERNAME}" "${ADMIN_PASSWORD:?set ADMIN_PASSWORD}"
ORDERS_INSTANCES="${ORDERS_INSTANCES:-localhost:9001:3 localhost:9002:2 localhost:9003:1}"
PAYMENTS_INSTANCES="${PAYMENTS_INSTANCES:-localhost:9101:1}"
RESTAURANT_INSTANCES="${RESTAURANT_INSTANCES:-localhost:9201:1}"

TOKEN=""
for _ in $(seq 1 30); do   # the gateway may still be starting
  TOKEN=$(curl -s -X POST "$GATEWAY/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$ADMIN_USERNAME\",\"password\":\"$ADMIN_PASSWORD\"}" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4 || true)
  [[ -n "$TOKEN" ]] && break
  sleep 2
done
[[ -n "$TOKEN" ]] || { echo "login as $ADMIN_USERNAME failed"; exit 1; }

api() { # method path [json]
  curl -s -X "$1" "$GATEWAY$2" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' ${3:+-d "$3"}
}
id_of() { grep -o '"id":[0-9]*' | head -1 | cut -d: -f2; }

register_service() {
  local name="$1" path="$2" strategy="$3" existing
  existing=$(api GET /api/services | grep -o "{\"id\":[0-9]*,\"name\":\"$name\"" | id_of || true)
  if [[ -n "$existing" ]]; then
    echo "$existing"
  else
    api POST /api/services "{\"name\":\"$name\",\"basePath\":\"$path\",\"loadBalancingStrategy\":\"$strategy\"}" | id_of
  fi
}

register_instances() { # service-id "host:port:weight ..."
  for spec in $2; do
    IFS=: read -r host port weight <<< "$spec"
    status=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GATEWAY/api/service-instances" \
      -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
      -d "{\"serviceId\":$1,\"host\":\"$host\",\"port\":$port,\"weight\":$weight}")
    case "$status" in
      201) echo "  registered $host:$port (weight $weight)";;
      409) echo "  $host:$port already registered";;
      *)   echo "  $host:$port -> HTTP $status";;
    esac
  done
}

orders=$(register_service orders-service /orders WEIGHTED_ROUND_ROBIN); echo "orders-service id=$orders"
register_instances "$orders" "$ORDERS_INSTANCES"
payments=$(register_service payments-service /payments ROUND_ROBIN); echo "payments-service id=$payments"
register_instances "$payments" "$PAYMENTS_INSTANCES"
restaurants=$(register_service restaurant-service /restaurants ROUND_ROBIN); echo "restaurant-service id=$restaurants"
register_instances "$restaurants" "$RESTAURANT_INSTANCES"
