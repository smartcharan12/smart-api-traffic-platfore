import { api } from '../api/client';
import { fmtTime } from '../components/Kpi';
import { RequestLogTable } from './RequestLogsPage';
import { usePolling } from '../hooks/usePolling';

export function FailuresPage() {
  const failures = usePolling(() => api.failures(60), 5000);
  const recent = usePolling(() => api.requestLogs({ statusClass: 5, size: 20 }), 5000);

  return (
    <>
      <section>
        <h2>Failures in the last 60 minutes, grouped</h2>
        <p className="muted">
          Includes requests that eventually succeeded after retries. The failure reason lists every failed attempt, e.g.
          <code> TIMEOUT@host:port</code>.
        </p>
        {failures.error && <p className="banner-error">{failures.error}</p>}
        <table>
          <thead>
            <tr>
              <th>Service</th>
              <th>Final status</th>
              <th>Failure reason</th>
              <th>Occurrences</th>
              <th>Last seen</th>
            </tr>
          </thead>
          <tbody>
            {(failures.data?.groups ?? []).map((g, i) => (
              <tr key={i}>
                <td className="mono">{g.service}</td>
                <td>{g.status_code}</td>
                <td className="mono wrap">{g.failure_reason || '—'}</td>
                <td>{g.occurrences}</td>
                <td>{fmtTime(g.last_seen)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
      <section>
        <h2>Most recent 5xx responses</h2>
        <RequestLogTable entries={recent.data?.items ?? []} />
      </section>
    </>
  );
}
