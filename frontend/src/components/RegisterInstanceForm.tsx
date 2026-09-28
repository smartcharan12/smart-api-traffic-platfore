import { useState, type FormEvent } from 'react';
import { api, ApiError } from '../api/client';
import type { Service } from '../api/types';
import { FieldError } from './RegisterServiceForm';

export function RegisterInstanceForm({ services, onCreated }: { services: Service[]; onCreated: () => void }) {
  const [serviceId, setServiceId] = useState('');
  const [host, setHost] = useState('localhost');
  const [port, setPort] = useState('');
  const [weight, setWeight] = useState('1');
  const [error, setError] = useState<ApiError | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      await api.createInstance({
        serviceId: Number(serviceId),
        host: host.trim(),
        port: Number(port),
        weight: Number(weight),
      });
      setPort('');
      onCreated();
    } catch (e) {
      setError(e instanceof ApiError ? e : new ApiError(0, String(e)));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form onSubmit={submit} className="form">
      <h3>Register instance</h3>
      <label>
        Service
        <select value={serviceId} onChange={(e) => setServiceId(e.target.value)} required>
          <option value="" disabled>
            {services.length === 0 ? 'Register a service first' : 'Select a service'}
          </option>
          {services.map((s) => (
            <option key={s.id} value={s.id}>
              {s.name}
            </option>
          ))}
        </select>
        <FieldError error={error} field="serviceId" />
      </label>
      <label>
        Host
        <input value={host} onChange={(e) => setHost(e.target.value)} required />
        <FieldError error={error} field="host" />
      </label>
      <label>
        Port
        <input type="number" min={1} max={65535} value={port} onChange={(e) => setPort(e.target.value)} required />
        <FieldError error={error} field="port" />
      </label>
      <label>
        Weight
        <input type="number" min={1} max={100} value={weight} onChange={(e) => setWeight(e.target.value)} />
        <FieldError error={error} field="weight" />
      </label>
      {error && Object.keys(error.fieldErrors).length === 0 && <p className="error">{error.message}</p>}
      <button type="submit" disabled={submitting || services.length === 0}>
        {submitting ? 'Registering…' : 'Register'}
      </button>
    </form>
  );
}
