import type { InstanceStatus, Service, ServiceInstance } from '../api/types';

const STATUSES: InstanceStatus[] = ['HEALTHY', 'DEGRADED', 'DOWN', 'UNKNOWN'];

export function SummaryCards({ services, instances }: { services: Service[]; instances: ServiceInstance[] }) {
  const countByStatus = (status: InstanceStatus) => instances.filter((i) => i.status === status).length;
  return (
    <div className="cards">
      <div className="card">
        <div className="card-value">{services.length}</div>
        <div className="card-label">Services</div>
      </div>
      <div className="card">
        <div className="card-value">{instances.length}</div>
        <div className="card-label">Instances</div>
      </div>
      {STATUSES.map((status) => (
        <div key={status} className={`card card-${status.toLowerCase()}`}>
          <div className="card-value">{countByStatus(status)}</div>
          <div className="card-label">{status}</div>
        </div>
      ))}
    </div>
  );
}
