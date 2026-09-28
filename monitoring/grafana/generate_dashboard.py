#!/usr/bin/env python3
"""Generates dashboards/gateway.json. Edit the PANELS list, not the JSON."""
import json, pathlib

DS = {"type": "prometheus", "uid": "prometheus"}
R = "$__rate_interval"

PANELS = [
    # (title, type, unit, [(expr, legend)], width, description)
    ("Requests per second", "timeseries", "reqps",
     [(f'sum by (service) (rate(gateway_requests_seconds_count[{R}]))', "{{service}}")], 12,
     "Every request through /gateway/**, by resolved service ('unresolved' = no route / rejected before routing)."),
    ("Error rate (5xx) %", "timeseries", "percent",
     [(f'100 * sum(rate(gateway_requests_seconds_count{{status=~"5.."}}[{R}])) / sum(rate(gateway_requests_seconds_count[{R}]))', "5xx %")], 12,
     "Server errors returned to clients, including backend 5xx passed through and gateway 502/503/504."),
    ("Latency p50 / p95 / p99", "timeseries", "s",
     [(f'histogram_quantile({q}, sum by (le) (rate(gateway_requests_seconds_bucket[{R}])))', f"p{int(q*100)}") for q in (0.5, 0.95, 0.99)], 12,
     "Computed from histogram buckets (10ms..5s), so values are bucket-interpolated estimates."),
    ("Average latency by service", "timeseries", "s",
     [(f'sum by (service) (rate(gateway_requests_seconds_sum[{R}])) / sum by (service) (rate(gateway_requests_seconds_count[{R}]))', "{{service}}")], 12, ""),
    ("Responses by status", "timeseries", "reqps",
     [(f'sum by (status) (rate(gateway_requests_seconds_count[{R}]))', "{{status}}")], 12, ""),
    ("Rate-limit rejections (429/s)", "timeseries", "reqps",
     [(f'rate(gateway_rate_limit_rejections_total[{R}])', "429/s"),
      (f'rate(gateway_rate_limit_local_fallback_decisions_total[{R}])', "decided locally (Redis unavailable)")], 12, ""),
    ("Retries per second by reason", "timeseries", "reqps",
     [(f'sum by (reason) (rate(gateway_retries_total[{R}]))', "{{reason}}")], 12,
     "Retries are capped by the retry budget; a flat line during an outage means the budget is doing its job."),
    ("Circuit breaker state (0 closed, 1 half-open, 2 open)", "timeseries", "none",
     [("gateway_circuit_breaker_state", "{{upstream}}")], 12, ""),
    ("Circuit openings (last 5m)", "stat", "none",
     [('sum(increase(gateway_circuit_breaker_transitions_total{to="OPEN"}[5m]))', "openings")], 6, ""),
    ("Cache hit ratio (5m)", "stat", "percentunit",
     [('sum(rate(gateway_cache_requests_total{result="HIT"}[5m])) / sum(rate(gateway_cache_requests_total[5m]))', "hit ratio")], 6, ""),
    ("Redis available", "stat", "none",
     [("gateway_redis_available", "redis")], 6, "1 = Redis reachable; 0 = gateway using local fallbacks."),
    ("Request-log rows dropped", "stat", "none",
     [("gateway_request_log_dropped_total", "dropped")], 6, ""),
    ("Instance health (1 healthy, 0.5 degraded, 0 down)", "timeseries", "none",
     [("gateway_instance_up", "{{service}} {{upstream}}")], 12, ""),
    ("Active connections per instance", "timeseries", "none",
     [("gateway_instance_active_connections", "{{service}} {{upstream}}")], 12, ""),
    ("Instance uptime", "timeseries", "s",
     [("gateway_instance_uptime_seconds", "{{service}} {{upstream}}")], 12, "Time since the instance last became routable."),
    ("Health-probe latency", "timeseries", "s",
     [("gateway_instance_probe_latency_seconds", "{{service}} {{upstream}}")], 12, ""),
]

panels, x, y, pid = [], 0, 0, 1
for title, kind, unit, targets, width, desc in PANELS:
    height = 4 if kind == "stat" else 8
    if x + width > 24:
        x, y = 0, y + prev_h
    panels.append({
        "id": pid, "type": kind, "title": title, "description": desc, "datasource": DS,
        "gridPos": {"x": x, "y": y, "w": width, "h": height},
        "fieldConfig": {"defaults": {"unit": unit}, "overrides": []},
        "options": {"legend": {"displayMode": "list", "placement": "bottom"}} if kind == "timeseries"
                   else {"reduceOptions": {"calcs": ["lastNotNull"]}, "colorMode": "value"},
        "targets": [{"refId": chr(65 + i), "datasource": DS, "expr": e, "legendFormat": l} for i, (e, l) in enumerate(targets)],
    })
    x += width
    prev_h = height
    pid += 1

dashboard = {
    "uid": "smart-api-gateway", "title": "Smart API Gateway", "tags": ["gateway"],
    "timezone": "browser", "refresh": "5s", "schemaVersion": 39, "version": 1,
    "time": {"from": "now-15m", "to": "now"}, "panels": panels,
}
out = pathlib.Path(__file__).parent / "dashboards" / "gateway.json"
out.write_text(json.dumps(dashboard, indent=2) + "\n")
print(f"wrote {out} with {len(panels)} panels")
