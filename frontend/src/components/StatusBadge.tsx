import type { InstanceStatus } from '../api/types';

export function StatusBadge({ status }: { status: InstanceStatus | 'UP' }) {
  return <span className={`badge badge-${status.toLowerCase()}`}>{status}</span>;
}
