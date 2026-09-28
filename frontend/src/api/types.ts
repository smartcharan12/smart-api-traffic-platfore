// Mirrors the backend DTOs (com.smartapi.gateway.dto). Keep in sync when the API changes.

export type LoadBalancingStrategy = 'ROUND_ROBIN' | 'WEIGHTED_ROUND_ROBIN' | 'LEAST_CONNECTIONS';
export type InstanceStatus = 'UNKNOWN' | 'HEALTHY' | 'DEGRADED' | 'DOWN';

export interface Service {
  id: number;
  name: string;
  basePath: string;
  description: string | null;
  loadBalancingStrategy: LoadBalancingStrategy;
  createdAt: string;
  updatedAt: string;
}

export interface ServiceInstance {
  id: number;
  serviceId: number;
  serviceName: string;
  host: string;
  port: number;
  weight: number;
  status: InstanceStatus;
  lastHeartbeatAt: string | null;
  createdAt: string;
}

export interface Health {
  status: 'UP' | 'DEGRADED' | 'DOWN';
  components: Record<string, 'UP' | 'DOWN'>;
  timestamp: string;
}

export interface CreateServiceRequest {
  name: string;
  basePath: string;
  description?: string;
  loadBalancingStrategy?: LoadBalancingStrategy;
}

export interface CreateInstanceRequest {
  serviceId: number;
  host: string;
  port: number;
  weight?: number;
}

// ---- Phase 3 ----

export type CircuitState = 'CLOSED' | 'HALF_OPEN' | 'OPEN';

export interface InstanceHealthView {
  instanceId: number;
  serviceName: string;
  address: string;
  weight: number;
  status: InstanceStatus;
  lastHeartbeatAt: string | null;
  latencyMs: number | null;
  availabilityPercent: number | null;
  uptimeSeconds: number | null;
  consecutiveFailures: number | null;
  totalChecks: number | null;
  lastCheckAt: string | null;
  lastError: string | null;
  activeConnections: number;
  circuitState: CircuitState;
}

export interface InstanceHealthReport {
  healthChecksEnabled: boolean;
  thisGatewayIsChecker: boolean;
  instances: InstanceHealthView[];
}

export interface CircuitBreakerView {
  address: string;
  state: CircuitState;
  bufferedCalls: number;
  failedCalls: number;
  failureRatePercent: number;
  openedAt: string | null;
  remainingOpenMs: number;
  timesOpened: number;
}

export interface CircuitTransition {
  address: string;
  from: CircuitState;
  to: CircuitState;
  at: string;
  reason: string;
}

export interface CircuitBreakersReport {
  enabled: boolean;
  breakers: CircuitBreakerView[];
  recentTransitions: CircuitTransition[];
}

export interface RequestLogEntry {
  id: number;
  requestId: string;
  createdAt: string;
  method: string;
  path: string;
  clientId: string | null;
  clientIp: string | null;
  serviceId: number | null;
  serviceName: string | null;
  instanceId: number | null;
  statusCode: number;
  latencyMs: number;
  retryCount: number;
  failureReason: string | null;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** Row shapes of /api/traffic/summary (snake_case: they are SQL column aliases). */
export interface TrafficTotals {
  total: number;
  server_errors: number;
  rate_limited: number;
  retried_requests: number;
  retries: number;
  avg_latency_ms: number | null;
  p50_ms: number | null;
  p95_ms: number | null;
  p99_ms: number | null;
}

export interface TrafficMinute {
  minute: string;
  total: number;
  server_errors: number;
  rate_limited: number;
  avg_latency_ms: number | null;
  p95_ms: number | null;
}

export interface TrafficByService {
  service: string;
  total: number;
  server_errors: number;
  rate_limited: number;
  avg_latency_ms: number | null;
  p95_ms: number | null;
}

export interface TrafficSummary {
  windowMinutes: number;
  totals: TrafficTotals;
  perMinute: TrafficMinute[];
  byService: TrafficByService[];
}

export interface FailureGroup {
  service: string;
  status_code: number;
  failure_reason: string;
  occurrences: number;
  last_seen: string;
}

export interface FailuresReport {
  windowMinutes: number;
  groups: FailureGroup[];
}

export interface GatewaySettings {
  rateLimit: {
    enabled: boolean;
    algorithm: string;
    limit: number;
    windowSeconds: number;
    keyBy: string[];
    store: string;
    localFallbackDecisions: number;
  };
  redis: { available: boolean; failures: number; skippedCalls: number };
  circuitBreaker: {
    enabled: boolean;
    slidingWindowSize: number;
    minimumCalls: number;
    failureRateThreshold: number;
    openDurationSeconds: number;
    halfOpenPermittedCalls: number;
  };
  retry: {
    maxAttempts: number;
    initialBackoffMs: number;
    multiplier: number;
    maxBackoffMs: number;
    jitter: number;
    budgetRatio: number;
    budgetTokensAvailable: number;
  };
  cache: { enabled: boolean; maxTtlSeconds: number };
}
