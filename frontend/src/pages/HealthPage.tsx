import { isAdmin } from '../api/auth';
import { useState } from 'react';
import { api, ApiError } from '../api/client';
import { StatusBadge } from '../components/StatusBadge';
import { fmtDuration, fmtMs, fmtTime } from '../components/Kpi';
import { usePolling } from '../hooks/usePolling';

export function HealthPage() {
  const { data, error, refresh } = usePolling(() => api.healthInstances(), 3000);

  const [message, setMessage] = useState<string | null>(null);

  async function simulate(instanceId: number, scenario: string) {
    try {
      const r = await api.simulate(instanceId, scenario);
      setMessage(`${scenario} applied to ${r.address} (backend answered ${r.backendStatus}). Watch status and circuit change.`);
    } catch (e) {
      setMessage(e instanceof ApiError ? e.message : String(e));
    }
    await refresh();
  }

  async function checkNow() {
    await api.runHealthChecks();
    await refresh();
  }

  return (
    <section>
      <h2>Service health</h2>
      {error && <p className="banner-error">{error}</p>}
      <p className="muted">
        Active probes of each instance's health endpoint:{' '}
        {data?.healthChecksEnabled ? 'enabled' : 'disabled'}.{' '}
        {data && !data.thisGatewayIsChecker && 'Another gateway holds the health-checker lease, so probe details are shown there.'}{' '}
        DEGRADED = slow or failing probes (still routable); DOWN = 3 consecutive failures (no traffic).
        {isAdmin() && (
          <button className="small" onClick={() => void checkNow()}>
            Run checks now
          </button>
        )}
      </p>
      {message && <p className="banner-info">{message}</p>}
      <table>
        <thead>
          <tr>
            <th>Service</th>
            <th>Instance</th>
            <th>Status</th>
            <th>Circuit</th>
            <th>Probe latency</th>
            <th>Availability</th>
            <th>Uptime</th>
            <th>Consecutive failures</th>
            <th>Active conns</th>
            <th>Last heartbeat</th>
            <th>Last error</th>
            {isAdmin() && <th>Simulate failure (demo services)</th>}
          </tr>
        </thead>
        <tbody>
          {(data?.instances ?? []).map((i) => (
            <tr key={i.instanceId}>
              <td className="mono">{i.serviceName}</td>
              <td className="mono">{i.address}</td>
              <td>
                <StatusBadge status={i.status} />
              </td>
              <td>
                <span className={`badge badge-circuit-${i.circuitState.toLowerCase()}`}>{i.circuitState}</span>
              </td>
              <td>{fmtMs(i.latencyMs)}</td>
              <td>{i.availabilityPercent === null ? '—' : `${i.availabilityPercent}%`}</td>
              <td>{fmtDuration(i.uptimeSeconds)}</td>
              <td>{i.consecutiveFailures ?? '—'}</td>
              <td>{i.activeConnections}</td>
              <td>{fmtTime(i.lastHeartbeatAt)}</td>
              <td className="mono">{i.lastError ?? '—'}</td>
              {isAdmin() && (
                <td>
                  <select
                    aria-label={`Simulate a failure on ${i.address}`}
                    value=""
                    onChange={(e) => void simulate(i.instanceId, e.target.value)}
                  >
                    <option value="" disabled>
                      Choose…
                    </option>
                    <option value="ERROR">HTTP 500 errors</option>
                    <option value="SLOW">High latency (1.5 s)</option>
                    <option value="TIMEOUT">Timeouts (8 s)</option>
                    <option value="UNAVAILABLE">Unavailable (503, health too)</option>
                    <option value="RECOVER">Recover</option>
                  </select>
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}
