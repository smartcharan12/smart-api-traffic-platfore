# monitoring/

Prometheus scrapes the gateway's `/actuator/prometheus`; Grafana shows a provisioned dashboard.
Everything is configuration-as-code: no clicking in the UI.

| File | Purpose |
|---|---|
| `prometheus/prometheus.yml` | Scrape config for Docker Compose (target `gateway:8080`, every 5 s) |
| `prometheus/prometheus.local.yml` | Same, for a gateway on `localhost:8080` |
| `grafana/provisioning/datasources/prometheus.yml` | Prometheus datasource (URL from `PROMETHEUS_URL`) |
| `grafana/provisioning/dashboards/dashboards.yml` | Loads dashboards from `GRAFANA_DASHBOARDS_DIR` |
| `grafana/dashboards/gateway.json` | The "Smart API Gateway" dashboard (16 panels) |
| `grafana/generate_dashboard.py` | Source of `gateway.json`. Edit the panel list there, then re-run it. |

Panels: requests/s by service, 5xx error rate, p50/p95/p99 latency, average latency by service,
responses by status, 429s (and decisions made locally while Redis is down), retries by reason,
circuit-breaker state per backend, circuit openings, cache hit ratio, Redis availability, dropped
request-log rows, and per-backend health (up/degraded/down), active connections, uptime and probe latency.

Metric names and labels are documented in `backend/.../metrics/GatewayMetrics.java`. The backend
address label is `upstream`, because `instance` is Prometheus' own label for the scrape target.

## Running without Docker

Versions pinned for this project: Prometheus 3.15.0, Grafana 13.2.2 (standalone release tarballs).

```bash
./prometheus --config.file=monitoring/prometheus/prometheus.local.yml
PROMETHEUS_URL=http://localhost:9090 \
GRAFANA_DASHBOARDS_DIR=$PWD/monitoring/grafana/dashboards \
GF_PATHS_PROVISIONING=$PWD/monitoring/grafana/provisioning \
GF_AUTH_ANONYMOUS_ENABLED=true ./bin/grafana server      # from the Grafana directory
# dashboard: http://localhost:3000/d/smart-api-gateway
```
