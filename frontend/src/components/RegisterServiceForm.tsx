import { useState, type FormEvent } from 'react';
import { api, ApiError } from '../api/client';
import type { LoadBalancingStrategy } from '../api/types';

export function RegisterServiceForm({ onCreated }: { onCreated: () => void }) {
  const [name, setName] = useState('');
  const [basePath, setBasePath] = useState('/');
  const [description, setDescription] = useState('');
  const [strategy, setStrategy] = useState<LoadBalancingStrategy>('ROUND_ROBIN');
  const [error, setError] = useState<ApiError | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      await api.createService({
        name: name.trim(),
        basePath: basePath.trim(),
        description: description.trim() || undefined,
        loadBalancingStrategy: strategy,
      });
      setName('');
      setBasePath('/');
      setDescription('');
      onCreated();
    } catch (e) {
      setError(e instanceof ApiError ? e : new ApiError(0, String(e)));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form onSubmit={submit} className="form">
      <h3>Register service</h3>
      <label>
        Name
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder="orders-service" required />
        <FieldError error={error} field="name" />
      </label>
      <label>
        Base path
        <input value={basePath} onChange={(e) => setBasePath(e.target.value)} placeholder="/orders" required />
        <FieldError error={error} field="basePath" />
      </label>
      <label>
        Load balancing
        <select value={strategy} onChange={(e) => setStrategy(e.target.value as LoadBalancingStrategy)}>
          <option value="ROUND_ROBIN">Round robin</option>
          <option value="WEIGHTED_ROUND_ROBIN">Weighted round robin</option>
          <option value="LEAST_CONNECTIONS">Least connections</option>
        </select>
      </label>
      <label>
        Description
        <input value={description} onChange={(e) => setDescription(e.target.value)} />
      </label>
      {error && Object.keys(error.fieldErrors).length === 0 && <p className="error">{error.message}</p>}
      <button type="submit" disabled={submitting}>
        {submitting ? 'Registering…' : 'Register'}
      </button>
    </form>
  );
}

export function FieldError({ error, field }: { error: ApiError | null; field: string }) {
  const message = error?.fieldErrors[field];
  return message ? <span className="error">{message}</span> : null;
}
