import type { InstanceStatus, ServiceInstance } from '../api/types';
import { StatusBadge } from './StatusBadge';

interface Props {
  instances: ServiceInstance[];
  onDelete: (instance: ServiceInstance) => void;
  onStatusChange: (instance: ServiceInstance, status: InstanceStatus) => void;
}

// Manual override until Phase 3's automatic health checks. The gateway skips DOWN instances.
const SETTABLE: InstanceStatus[] = ['HEALTHY', 'DEGRADED', 'DOWN'];

const formatTime = (iso: string | null) => (iso ? new Date(iso).toLocaleString() : 'never');

export function InstancesTable({ instances, onDelete, onStatusChange }: Props) {
  if (instances.length === 0) return <p className="empty">No instances registered yet.</p>;
  return (
    <table>
      <thead>
        <tr>
          <th>Service</th>
          <th>Address</th>
          <th>Weight</th>
          <th>Status</th>
          <th>Last heartbeat</th>
          <th>Set status</th>
          <th />
        </tr>
      </thead>
      <tbody>
        {instances.map((instance) => (
          <tr key={instance.id}>
            <td className="mono">{instance.serviceName}</td>
            <td className="mono">
              {instance.host}:{instance.port}
            </td>
            <td>{instance.weight}</td>
            <td>
              <StatusBadge status={instance.status} />
            </td>
            <td>{formatTime(instance.lastHeartbeatAt)}</td>
            <td>
              <select
                aria-label={`Set status of ${instance.host}:${instance.port}`}
                value=""
                onChange={(e) => onStatusChange(instance, e.target.value as InstanceStatus)}
              >
                <option value="" disabled>
                  Change…
                </option>
                {SETTABLE.filter((s) => s !== instance.status).map((s) => (
                  <option key={s} value={s}>
                    {s}
                  </option>
                ))}
              </select>
            </td>
            <td>
              <button className="link-danger" onClick={() => onDelete(instance)}>
                Remove
              </button>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
