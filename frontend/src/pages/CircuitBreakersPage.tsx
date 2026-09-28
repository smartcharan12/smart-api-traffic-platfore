import { api } from '../api/client';
import { fmtTime } from '../components/Kpi';
import { usePolling } from '../hooks/usePolling';

export function CircuitBreakersPage() {
  const { data, error } = usePolling(() => api.circuitBreakers(), 2000);
  const settings = usePolling(() => api.settings(), 10000);
  const cb = settings.data?.circuitBreaker;

  return (
    <>
      {error && <p className="banner-error">{error}</p>}
      <p className="muted">
        One breaker per backend address, in this gateway process.{' '}
        {cb &&
          `Opens at ≥${cb.failureRateThreshold}% failures over the last ${cb.slidingWindowSize} calls (min ${cb.minimumCalls}); ` +
            `stays open ${cb.openDurationSeconds}s, then allows ${cb.halfOpenPermittedCalls} trial call(s).`}
      </p>
      <section>
        <h2>Breakers</h2>
        <table>
          <thead>
            <tr>
              <th>Backend</th>
              <th>State</th>
              <th>Failure rate</th>
              <th>Failed / recorded calls</th>
              <th>Opened at</th>
              <th>Retry in</th>
              <th>Times opened</th>
            </tr>
          </thead>
          <tbody>
            {(data?.breakers ?? []).map((b) => (
              <tr key={b.address}>
                <td className="mono">{b.address}</td>
                <td>
                  <span className={`badge badge-circuit-${b.state.toLowerCase()}`}>{b.state}</span>
                </td>
                <td>{b.failureRatePercent}%</td>
                <td>
                  {b.failedCalls} / {b.bufferedCalls}
                </td>
                <td>{fmtTime(b.openedAt)}</td>
                <td>{b.state === 'OPEN' ? `${Math.ceil(b.remainingOpenMs / 1000)}s` : '—'}</td>
                <td>{b.timesOpened}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
      <section>
        <h2>Recent transitions</h2>
        <table>
          <thead>
            <tr>
              <th>Time</th>
              <th>Backend</th>
              <th>Transition</th>
              <th>Reason</th>
            </tr>
          </thead>
          <tbody>
            {(data?.recentTransitions ?? []).map((t, i) => (
              <tr key={i}>
                <td>{fmtTime(t.at)}</td>
                <td className="mono">{t.address}</td>
                <td>
                  {t.from} → <strong>{t.to}</strong>
                </td>
                <td>{t.reason}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </>
  );
}
