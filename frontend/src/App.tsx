import { useCallback, useEffect, useState } from 'react';
import { api, ApiError } from './api/client';
import type { Health, Service, ServiceInstance } from './api/types';
import { HealthIndicator } from './components/HealthIndicator';
import { InstancesTable } from './components/InstancesTable';
import { RegisterInstanceForm } from './components/RegisterInstanceForm';
import { RegisterServiceForm } from './components/RegisterServiceForm';
import { ServicesTable } from './components/ServicesTable';
import { SummaryCards } from './components/SummaryCards';

// Instance status will start changing on its own once health checks exist (Phase 3), so poll.
const REFRESH_INTERVAL_MS = 5000;

export default function App() {
  const [services, setServices] = useState<Service[]>([]);
  const [instances, setInstances] = useState<ServiceInstance[]>([]);
  const [health, setHealth] = useState<Health | null | undefined>(undefined);
  const [loadError, setLoadError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setHealth(await api.health());
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
    const timer = setInterval(() => void refresh(), REFRESH_INTERVAL_MS);
    return () => clearInterval(timer); // stop polling when the component unmounts
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
    <div className="app">
      <header>
        <h1>Smart API Traffic Platform</h1>
        <HealthIndicator health={health} />
      </header>

      {loadError && <p className="banner-error">{loadError}</p>}

      <SummaryCards services={services} instances={instances} />

      <section>
        <h2>Services</h2>
        <ServicesTable
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
          instances={instances}
          onDelete={(i) => {
            if (confirm(`Remove ${i.host}:${i.port}?`)) void runAndRefresh(() => api.deleteInstance(i.id));
          }}
        />
      </section>

      <section className="forms">
        <RegisterServiceForm onCreated={() => void refresh()} />
        <RegisterInstanceForm services={services} onCreated={() => void refresh()} />
      </section>
    </div>
  );
}
