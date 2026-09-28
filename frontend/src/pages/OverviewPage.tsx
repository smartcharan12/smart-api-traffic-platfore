import { api } from '../api/client';
import { Kpi, fmtMs, minuteLabel } from '../components/Kpi';
import { LineChart } from '../components/LineChart';
import { usePolling } from '../hooks/usePolling';

export function OverviewPage() {
  const traffic = usePolling(() => api.trafficSummary(15), 5000);
  const health = usePolling(() => api.healthInstances(), 5000);
  const circuits = usePolling(() => api.circuitBreakers(), 5000);

  const t = traffic.data?.totals;
  const errorRate = t && t.total > 0 ? (100 * t.server_errors) / t.total : 0;
  const instances = health.data?.instances ?? [];
  const count = (s: string) => instances.filter((i) => i.status === s).length;
  const openCircuits = circuits.data?.breakers.filter((b) => b.state !== 'CLOSED').length ?? 0;
  const perMinute = traffic.data?.perMinute ?? [];

  return (
    <>
      {(traffic.error || health.error) && <p className="banner-error">{traffic.error ?? health.error}</p>}
      <p className="muted">Last 15 minutes, all gateway instances (from request logs). Refreshes every 5 s.</p>
      <div className="cards">
        <Kpi label="Requests" value={t?.total ?? '—'} />
        <Kpi label="5xx error rate" value={t ? `${errorRate.toFixed(1)}%` : '—'} tone={errorRate > 5 ? 'bad' : undefined} />
        <Kpi label="p95 latency" value={fmtMs(t?.p95_ms)} />
        <Kpi label="p99 latency" value={fmtMs(t?.p99_ms)} />
        <Kpi label="Rate limited (429)" value={t?.rate_limited ?? '—'} tone={t && t.rate_limited > 0 ? 'warn' : undefined} />
        <Kpi label="Retries" value={t?.retries ?? '—'} />
      </div>
      <div className="cards">
        <Kpi label="Healthy instances" value={count('HEALTHY')} tone="good" />
        <Kpi label="Degraded" value={count('DEGRADED')} tone={count('DEGRADED') > 0 ? 'warn' : undefined} />
        <Kpi label="Down" value={count('DOWN')} tone={count('DOWN') > 0 ? 'bad' : undefined} />
        <Kpi label="Open / half-open circuits" value={openCircuits} tone={openCircuits > 0 ? 'bad' : undefined} />
      </div>
      <section>
        <h2>Requests per minute</h2>
        <LineChart
          labels={perMinute.map((m) => minuteLabel(m.minute))}
          series={[
            { name: 'total', color: 'var(--accent)', values: perMinute.map((m) => m.total) },
            { name: '5xx', color: 'var(--down)', values: perMinute.map((m) => m.server_errors) },
            { name: '429', color: 'var(--degraded)', values: perMinute.map((m) => m.rate_limited) },
          ]}
        />
      </section>
    </>
  );
}
