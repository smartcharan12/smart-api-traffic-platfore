import { api } from '../api/client';
import { Kpi, minuteLabel } from '../components/Kpi';
import { LineChart } from '../components/LineChart';
import { RequestLogTable } from './RequestLogsPage';
import { usePolling } from '../hooks/usePolling';

export function RateLimitPage() {
  const settings = usePolling(() => api.settings(), 5000);
  const traffic = usePolling(() => api.trafficSummary(15), 5000);
  const rejected = usePolling(() => api.requestLogs({ status: 429, size: 20 }), 5000);
  const rl = settings.data?.rateLimit;
  const perMinute = traffic.data?.perMinute ?? [];

  return (
    <>
      {settings.error && <p className="banner-error">{settings.error}</p>}
      <div className="cards">
        <Kpi label="Algorithm" value={rl ? (rl.enabled ? rl.algorithm : 'DISABLED') : '—'} />
        <Kpi label="Limit" value={rl ? `${rl.limit} / ${rl.windowSeconds}s` : '—'} />
        <Kpi label="Key" value={rl ? rl.keyBy.join(' + ') : '—'} />
        <Kpi label="State store" value={rl?.store ?? '—'} />
        <Kpi
          label="Redis"
          value={settings.data ? (settings.data.redis.available ? 'available' : 'unavailable') : '—'}
          tone={settings.data && !settings.data.redis.available ? 'bad' : 'good'}
        />
        <Kpi label="Decisions made locally (Redis down)" value={rl?.localFallbackDecisions ?? '—'} />
      </div>
      <section>
        <h2>429 responses per minute (last 15 min)</h2>
        <LineChart
          labels={perMinute.map((m) => minuteLabel(m.minute))}
          series={[
            { name: 'allowed', color: 'var(--accent)', values: perMinute.map((m) => m.total - m.rate_limited) },
            { name: '429', color: 'var(--degraded)', values: perMinute.map((m) => m.rate_limited) },
          ]}
        />
      </section>
      <section>
        <h2>Most recent rejected requests</h2>
        <RequestLogTable entries={rejected.data?.items ?? []} />
      </section>
    </>
  );
}
