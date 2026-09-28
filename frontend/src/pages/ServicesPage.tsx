import { useCallback, useEffect, useState } from 'react';
import { isAdmin } from '../api/auth';
import { api, ApiError } from '../api/client';
import type { Service, ServiceInstance } from '../api/types';
import { InstancesTable } from '../components/InstancesTable';
import { RegisterInstanceForm } from '../components/RegisterInstanceForm';
import { RegisterServiceForm } from '../components/RegisterServiceForm';
import { ServicesTable } from '../components/ServicesTable';
import { SummaryCards } from '../components/SummaryCards';

/** The Phase 1/2 registry page, moved here unchanged apart from the page wrapper. */
export function ServicesPage() {
  const [services, setServices] = useState<Service[]>([]);
  const [instances, setInstances] = useState<ServiceInstance[]>([]);
  const [loadError, setLoadError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      const [s, i] = await Promise.all([api.listServices(), api.listInstances()]);
      setServices(s);
      setInstances(i);
      setLoadError(null);
    } catch (e) {
      setLoadError(e instanceof ApiError ? e.message : String(e));
    }
  }, []);

  useEffect(() => {
    void refresh();
    const timer = setInterval(() => void refresh(), 5000);
    return () => clearInterval(timer);
  }, [refresh]);

  async function runAndRefresh(action: () => Promise<void>) {
    try {
      await action();
    } catch (e) {
      alert(e instanceof ApiError ? e.message : String(e));
    }
    await refresh();
  }

  return (
    <>
      {loadError && <p className="banner-error">{loadError}</p>}
      <SummaryCards services={services} instances={instances} />
      <section>
        <h2>Services</h2>
        <ServicesTable
          readOnly={!isAdmin()}
          services={services}
          instances={instances}
          onDelete={(s) => {
            if (confirm(`Delete ${s.name} and all its instances?`)) void runAndRefresh(() => api.deleteService(s.id));
          }}
        />
      </section>
      <section>
        <h2>Service instances</h2>
        <InstancesTable
          readOnly={!isAdmin()}
          instances={instances}
          onDelete={(i) => {
            if (confirm(`Remove ${i.host}:${i.port}?`)) void runAndRefresh(() => api.deleteInstance(i.id));
          }}
          onStatusChange={(i, status) =>
            void runAndRefresh(async () => {
              await api.updateInstanceStatus(i.id, status);
            })
          }
        />
      </section>
      {isAdmin() ? (
        <section className="forms">
          <RegisterServiceForm onCreated={() => void refresh()} />
          <RegisterInstanceForm services={services} onCreated={() => void refresh()} />
        </section>
      ) : (
        <p className="muted">Signed in as USER: read-only. Registering, deleting and changing status require ADMIN.</p>
      )}
    </>
  );
}
