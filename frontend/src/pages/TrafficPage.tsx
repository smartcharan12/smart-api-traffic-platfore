import { useState } from 'react';
import { api } from '../api/client';
import { fmtMs, minuteLabel } from '../components/Kpi';
import { LineChart } from '../components/LineChart';
import { usePolling } from '../hooks/usePolling';

export function TrafficPage() {
  const [minutes, setMinutes] = useState(15);
  const { data, error } = usePolling(() => api.trafficSummary(minutes), 5000, [minutes]);
  const perMinute = data?.perMinute ?? [];
  const labels = perMinute.map((m) => minuteLabel(m.minute));

  return (
    <>
      {error && <p className="banner-error">{error}</p>}
      <p className="muted">
        Window:{' '}
        <select value={minutes} onChange={(e) => setMinutes(Number(e.target.value))}>
          {[5, 15, 60, 240].map((m) => (
            <option key={m} value={m}>
              last {m} minutes
            </option>
          ))}
        </select>{' '}
        Percentiles are computed in PostgreSQL (percentile_cont) over request_logs.
      </p>
      <section>
        <h2>Requests per minute</h2>
        <LineChart
          labels={labels}
          series={[
            { name: 'total', color: 'var(--accent)', values: perMinute.map((m) => m.total) },
            { name: '5xx', color: 'var(--down)', values: perMinute.map((m) => m.server_errors) },
          ]}
        />
      </section>
      <section>
        <h2>Latency per minute</h2>
        <LineChart
          labels={labels}
          unit=" ms"
          series={[
            { name: 'average', color: 'var(--accent)', values: perMinute.map((m) => m.avg_latency_ms) },
            { name: 'p95', color: 'var(--degraded)', values: perMinute.map((m) => m.p95_ms) },
          ]}
        />
      </section>
      <section>
        <h2>By service</h2>
        <table>
          <thead>
            <tr>
              <th>Service</th>
              <th>Requests</th>
              <th>5xx</th>
              <th>429</th>
              <th>Avg latency</th>
              <th>p95</th>
            </tr>
          </thead>
          <tbody>
            {(data?.byService ?? []).map((s) => (
              <tr key={s.service}>
                <td className="mono">{s.service}</td>
                <td>{s.total}</td>
                <td>{s.server_errors}</td>
                <td>{s.rate_limited}</td>
                <td>{fmtMs(s.avg_latency_ms)}</td>
                <td>{fmtMs(s.p95_ms)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </>
  );
}
