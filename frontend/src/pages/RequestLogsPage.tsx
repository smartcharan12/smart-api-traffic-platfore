import { useState } from 'react';
import { api, ApiError } from '../api/client';
import type { RequestLogEntry } from '../api/types';
import { fmtTime } from '../components/Kpi';
import { usePolling } from '../hooks/usePolling';

export function RequestLogTable({ entries }: { entries: RequestLogEntry[] }) {
  if (entries.length === 0) return <p className="empty">No matching requests.</p>;
  return (
    <table>
      <thead>
        <tr>
          <th>Time</th>
          <th>Request ID</th>
          <th>Client</th>
          <th>Method / path</th>
          <th>Service</th>
          <th>Status</th>
          <th>Latency</th>
          <th>Retries</th>
          <th>Failure reason</th>
        </tr>
      </thead>
      <tbody>
        {entries.map((l) => (
          <tr key={l.id}>
            <td>{fmtTime(l.createdAt)}</td>
            <td className="mono" title={l.requestId}>
              {l.requestId.slice(0, 8)}
            </td>
            <td className="mono">{l.clientId ?? l.clientIp ?? '—'}</td>
            <td className="mono">
              {l.method} {l.path}
            </td>
            <td className="mono">{l.serviceName ?? '—'}</td>
            <td>
              <span className={`status status-${Math.floor(l.statusCode / 100)}xx`}>{l.statusCode}</span>
            </td>
            <td>{l.latencyMs} ms</td>
            <td>{l.retryCount}</td>
            <td className="mono wrap">{l.failureReason ?? ''}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

export function RequestLogsPage() {
  const [statusClass, setStatusClass] = useState('');
  const [clientId, setClientId] = useState('');
  const [page, setPage] = useState(0);
  const [lookup, setLookup] = useState('');
  const [found, setFound] = useState<RequestLogEntry[] | null>(null);
  const [lookupError, setLookupError] = useState<string | null>(null);
  const { data, error } = usePolling(
    () => api.requestLogs({ statusClass, clientId: clientId.trim(), page, size: 50 }),
    5000,
    [statusClass, clientId, page],
  );

  async function findRequest() {
    setLookupError(null);
    try {
      setFound(await api.requestLogById(lookup.trim()));
    } catch (e) {
      setFound(null);
      setLookupError(e instanceof ApiError ? e.message : String(e));
    }
  }

  return (
    <>
      <section>
        <h2>Find a request by X-Request-ID</h2>
        <div className="toolbar">
          <input value={lookup} onChange={(e) => setLookup(e.target.value)} placeholder="7c3e0f2a-…" size={40} />
          <button className="small" onClick={() => void findRequest()} disabled={!lookup.trim()}>
            Find
          </button>
        </div>
        {lookupError && <p className="error">{lookupError}</p>}
        {found && <RequestLogTable entries={found} />}
      </section>
      <section>
        <h2>Request logs</h2>
        <div className="toolbar">
          <label>
            Status{' '}
            <select value={statusClass} onChange={(e) => { setStatusClass(e.target.value); setPage(0); }}>
              <option value="">all</option>
              <option value="2">2xx</option>
              <option value="4">4xx</option>
              <option value="5">5xx</option>
            </select>
          </label>
          <label>
            Client{' '}
            <input value={clientId} onChange={(e) => { setClientId(e.target.value); setPage(0); }} placeholder="X-User-Id" />
          </label>
          <span className="muted">
            {data ? `${data.totalElements} requests · page ${data.page + 1} of ${Math.max(1, data.totalPages)}` : ''}
          </span>
          <button className="small" disabled={page === 0} onClick={() => setPage(page - 1)}>
            ← Newer
          </button>
          <button className="small" disabled={!data || page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>
            Older →
          </button>
        </div>
        {error && <p className="banner-error">{error}</p>}
        <RequestLogTable entries={data?.items ?? []} />
      </section>
    </>
  );
}
