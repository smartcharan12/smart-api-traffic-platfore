import type { Service, ServiceInstance } from '../api/types';

interface Props {
  services: Service[];
  instances: ServiceInstance[];
  /** Hide change controls (the API enforces ADMIN-only changes anyway). */
  readOnly?: boolean;
  onDelete: (service: Service) => void;
}

export function ServicesTable({ services, instances, onDelete, readOnly = false }: Props) {
  if (services.length === 0) return <p className="empty">No services registered yet.</p>;
  return (
    <table>
      <thead>
        <tr>
          <th>Name</th>
          <th>Base path</th>
          <th>Load balancing</th>
          <th>Instances (healthy / total)</th>
          <th>Description</th>
          {!readOnly && <th />}
        </tr>
      </thead>
      <tbody>
        {services.map((service) => {
          const own = instances.filter((i) => i.serviceId === service.id);
          const healthy = own.filter((i) => i.status === 'HEALTHY').length;
          return (
            <tr key={service.id}>
              <td className="mono">{service.name}</td>
              <td className="mono">{service.basePath}</td>
              <td>{service.loadBalancingStrategy}</td>
              <td>
                {healthy} / {own.length}
              </td>
              <td>{service.description ?? '—'}</td>
              {!readOnly && (
                <td>
                  <button className="link-danger" onClick={() => onDelete(service)}>
                    Delete
                  </button>
                </td>
              )}
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}
