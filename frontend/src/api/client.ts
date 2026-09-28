import type {
  CircuitBreakersReport,
  CreateInstanceRequest,
  FailuresReport,
  GatewaySettings,
  InstanceHealthReport,
  Page,
  RequestLogEntry,
  TrafficSummary,
  CreateServiceRequest,
  Health,
  InstanceStatus,
  Service,
  ServiceInstance,
} from './types';

/**
 * Error thrown for any non-2xx response. The backend returns RFC 9457 problem+json, so we can
 * surface its human-readable `detail` and, for validation failures, per-field `errors`.
 */
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
    public readonly fieldErrors: Record<string, string> = {},
  ) {
    super(message);
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response;
  try {
    response = await fetch(path, {
      ...init,
      headers: { 'Content-Type': 'application/json', ...init?.headers },
    });
  } catch {
    throw new ApiError(0, 'Cannot reach the backend. Is it running on port 8080?');
  }
  if (!response.ok) {
    const problem = await response.json().catch(() => null);
    throw new ApiError(
      response.status,
      problem?.detail ?? `Request failed with HTTP ${response.status}`,
      problem?.errors ?? {},
    );
  }
  return response.status === 204 ? (undefined as T) : ((await response.json()) as T);
}

export const api = {
  listServices: () => request<Service[]>('/api/services'),
  createService: (body: CreateServiceRequest) =>
    request<Service>('/api/services', { method: 'POST', body: JSON.stringify(body) }),
  deleteService: (id: number) => request<void>(`/api/services/${id}`, { method: 'DELETE' }),

  listInstances: () => request<ServiceInstance[]>('/api/service-instances'),
  createInstance: (body: CreateInstanceRequest) =>
    request<ServiceInstance>('/api/service-instances', { method: 'POST', body: JSON.stringify(body) }),
  updateInstanceStatus: (id: number, status: InstanceStatus) =>
    request<ServiceInstance>(`/api/service-instances/${id}/status`, {
      method: 'PATCH',
      body: JSON.stringify({ status }),
    }),
  deleteInstance: (id: number) => request<void>(`/api/service-instances/${id}`, { method: 'DELETE' }),

  healthInstances: () => request<InstanceHealthReport>('/api/health/instances'),
  runHealthChecks: () => request<unknown[]>('/api/health/instances/check', { method: 'POST' }),
  circuitBreakers: () => request<CircuitBreakersReport>('/api/circuit-breakers'),
  trafficSummary: (minutes: number) => request<TrafficSummary>(`/api/traffic/summary?minutes=${minutes}`),
  failures: (minutes: number) => request<FailuresReport>(`/api/traffic/failures?minutes=${minutes}`),
  settings: () => request<GatewaySettings>('/api/gateway/settings'),
  requestLogs: (filters: Record<string, string | number | undefined>) => {
    const params = new URLSearchParams();
    Object.entries(filters).forEach(([k, v]) => {
      if (v !== undefined && v !== '') params.set(k, String(v));
    });
    return request<Page<RequestLogEntry>>(`/api/request-logs?${params}`);
  },
  requestLogById: (requestId: string) => request<RequestLogEntry[]>(`/api/request-logs/${encodeURIComponent(requestId)}`),

  // /api/health answers 503 with a JSON body when a dependency is down, so read the body either way.
  health: async (): Promise<Health | null> => {
    try {
      const response = await fetch('/api/health');
      return (await response.json()) as Health;
    } catch {
      return null;
    }
  },
};
