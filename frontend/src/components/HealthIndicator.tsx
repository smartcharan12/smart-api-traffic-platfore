import type { Health } from '../api/types';

export function HealthIndicator({ health }: { health: Health | null | undefined }) {
  if (health === undefined) return <span className="health">Checking backend…</span>;
  if (health === null) return <span className="health health-down">Backend unreachable</span>;
  const details = Object.entries(health.components)
    .map(([name, status]) => `${name}: ${status}`)
    .join(', ');
  return (
    <span className={`health health-${health.status.toLowerCase()}`} title={details}>
      Backend {health.status} ({details})
    </span>
  );
}
