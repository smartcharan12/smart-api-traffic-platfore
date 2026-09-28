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
