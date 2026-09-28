# Architecture

This document tracks the target architecture and what is implemented so far. Anything not marked
**built** is planned, not done.

## Target architecture (end of Phase 4)

```mermaid
flowchart TD
    Client["Client (curl / app)"] --> GW
    Dash["React dashboard"] -->|"/api/* management APIs"| GW

    subgraph GW["API Gateway (Spring Boot)"]
        direction TB
        Auth["Authentication (JWT / API key)"] --> RL["Rate limiter"]
        RL --> CB["Circuit breaker + health filter"]
        CB --> LB["Load balancer"]
        LB --> RT["Retry engine"]
    end

    RT --> A["Service A instances"]
    RT --> B["Service B instances"]
    RT --> C["Service C instances"]

    GW <--> Redis[("Redis: rate-limit counters, cache")]
    GW <--> PG[("PostgreSQL: registry, users, request logs")]
    Prom["Prometheus"] -->|"scrapes /actuator/prometheus"| GW
    Graf["Grafana"] --> Prom
```

Two kinds of traffic go through the backend:

- **Control plane**: `/api/services`, `/api/service-instances`, `/api/health`. The dashboard uses these to
  manage *what* the gateway routes to. **Built in Phase 1.**
- **Data plane**: client requests the gateway forwards to backend services, e.g.
  `/gateway/orders/orders/123` → `GET /orders/123` on an `orders-service` instance. **Built in Phase 2.**

## Phase 1: built

```mermaid
flowchart LR
    Browser["Browser"] --> Vite["Vite dev server :5173<br/>React dashboard"]
    Vite -->|"proxy /api/*"| Boot["Spring Boot :8080"]
    Boot --> PG[("PostgreSQL")]
```

Inside the backend, each request passes through the layers top to bottom:

```mermaid
flowchart TD
    HTTP["HTTP request (JSON)"] --> C["Controller<br/>parse, validate DTO, pick status code"]
    C --> S["Service<br/>business rules, @Transactional"]
    S --> R["Repository<br/>Spring Data JPA interface"]
    R --> H["Hibernate<br/>entity to SQL"]
    H --> DB[("PostgreSQL")]
    C -. "exception" .-> EH["GlobalExceptionHandler<br/>to problem+json 4xx/5xx"]
    S -. "exception" .-> EH
```

| Layer | Package | Responsibility | Must not |
|---|---|---|---|
| Controller | `controller` | HTTP: paths, `@Valid`, status codes, `Location` header | contain business rules or touch repositories |
| DTO | `dto` | Shape of JSON in and out; validation annotations | be JPA entities |
| Service | `service` | Business rules, transactions, entity to DTO mapping | know about HTTP |
| Repository | `repository` | Data access queries | contain business rules |
| Entity | `entity` | Mapping of Java objects to tables | be returned directly from controllers |
| Exception | `exception` | Domain exceptions and their HTTP mapping | leak stack traces or SQL to clients |
| Config | `config` | Bean definitions (e.g. `Clock`) | |

Why the layers matter in *this* project: the Phase 2 status endpoint and the Phase 3 health checker
both change instance status through `ServiceInstanceService.updateStatus`, which also publishes the
event that refreshes the gateway's discovery cache. The gateway reads the registry through its own
read-only `RegistryLookup` (repositories directly, no DTO mapping), because it runs on every request.

## Phase 2: built

```mermaid
flowchart TD
    C["Client"] --> F["RequestIdFilter (web)"]
    F --> GC["GatewayController (controller)"]
    GC --> GS["GatewayService (routing): the pipeline"]
    GS --> RR["RouteResolver"]
    GS --> RL["RateLimiter (ratelimit)<br/>FixedWindow | SlidingWindow | TokenBucket | NoOp"]
    GS --> SD["ServiceDiscovery<br/>TTL cache + generation"]
    SD --> LK["RegistryLookup<br/>@Transactional read"]
    LK --> PG[("PostgreSQL")]
    GS --> HF["HealthFilter"]
    GS --> LB["LoadBalancer (loadbalancer)<br/>RoundRobin | WeightedRoundRobin | LeastConnections"]
    LB --> CT["ActiveConnectionTracker"]
    GS --> PX["ProxyClient<br/>RestClient + JDK HttpClient"]
    PX --> CT
    PX --> B["Backend instances<br/>(services/demo-service)"]
    REG["ServiceRegistryService /<br/>ServiceInstanceService"] -. "RegistryChangedEvent<br/>(after commit)" .-> SD
```

| Package | Responsibility | Depends on |
|---|---|---|
| `controller` | `GatewayController`: servlet request ↔ `GatewayRequest`/`GatewayResponse` | `routing`, `web` |
| `routing` | Pipeline, route parsing, discovery + cache, health filter, HTTP forwarding, access log | `ratelimit`, `loadbalancer`, `repository`, `exception`, `web` |
| `ratelimit` | `RateLimiter` interface, three algorithms, key resolution, eviction | `config` |
| `loadbalancer` | `LoadBalancingStrategy` interface, three algorithms, `BackendInstance`, connection counts | `entity` (enums) |
| `web` | Request id (MDC), client IP resolution | `config` |
| `exception` | `GatewayException` hierarchy; each subclass carries its HTTP status | `ratelimit` (decision for headers) |

Design rules:
- **Algorithms don't know about HTTP or Spring.** The rate limiters and load balancers are plain classes
  with a `Clock` or tracker passed in, so they are unit-tested with `new`, a fake clock and no Spring context.
- **No database transaction spans a backend call.** Discovery finishes (or hits the cache) before
  `ProxyClient` sends anything.
- **Package dependencies are acyclic.** `BackendInstance` lives in `loadbalancer` so that
  `routing → loadbalancer` is one-way.

## Phase 3: built

```mermaid
flowchart LR
    subgraph GW["Gateway process (any number of these)"]
        direction TB
        PIPE["GatewayService pipeline"] --> RF["ResilientForwarder<br/>circuit breakers + retries"]
        HC["HealthCheckService<br/>probe pool (8 threads)"]
        RLW["RequestLogWriter<br/>bounded queue → batch writer"]
        MET["GatewayMetrics<br/>(Micrometer)"]
    end
    PIPE <-->|"Lua: rl:*<br/>cache: rc:*"| REDIS[("Redis")]
    HC <-->|"leader lease"| REDIS
    REDIS -->|"pub/sub:<br/>registry changes"| PIPE
    HC -->|"status, heartbeats"| PG[("PostgreSQL")]
    RLW -->|"request_logs"| PG
    RF --> BE["Backends"]
    HC -->|"GET /health"| BE
    PROM["Prometheus"] -->|"scrape /actuator/prometheus"| MET
    GRAF["Grafana"] --> PROM
    DASH["React dashboard"] -->|"/api/*"| GW
```

| Package | Responsibility |
|---|---|
| `resilience` | `CircuitBreaker` (state machine), registry, `RetryPolicy` / `RetryBudget` / `RetryDecider` |
| `redis` | `RedisGuard` (circuit breaker around Redis), `LeaderLock`, pub/sub broadcaster and subscription |
| `cache` | response-cache interface, Redis implementation, HTTP caching rules |
| `health` | active probes, status rules with hysteresis, per-instance records, combined report |
| `logging` | request-log pipeline, queries, traffic statistics, retention |
| `metrics` | Prometheus metrics |

**What is shared and what is per process.** Shared through PostgreSQL: registry, instance status,
heartbeats, request logs. Shared through Redis: rate-limit budgets, response cache, leader lease,
invalidation messages. Per gateway process, by design: circuit-breaker state, active-connection
counts, discovery cache, Prometheus metrics (Prometheus aggregates them).

**Failure of shared dependencies.** Database down → `/api/health` 503, proxying of cached routes
continues until the discovery TTL expires. Redis down → the gateway keeps working with local
fallbacks (`/api/health` reports DEGRADED).

## Repository layout

| Path | Purpose | Phase |
|---|---|---|
| `backend/` | Spring Boot app: control-plane API now, gateway data plane from Phase 2 | 1 |
| `frontend/` | React + TypeScript dashboard (Vite) | 1 |
| `database/` | Schema documentation and ER diagram (migrations live in the backend) | 1 |
| `docs/` | Architecture and per-phase learning guides | 1 |
| `services/` | Demo backend services with failure-injection switches | 2 |
| `scripts/` | Start/stop/register the demo services | 2 |
| `monitoring/` | Prometheus scrape config, Grafana provisioning and dashboard | 3 |
| `legacy/` | Pre-rewrite copies of replaced files, not built | 3 |
| `docker/` | Dockerfiles and container configuration | 4 |
| `tests/` | Load tests against the running stack (`LoadTest.java` in Phase 2; k6 and failure scripts in Phase 4) | 2, 4 |
| `docs/results/` | Raw output of every measurement quoted in the docs | 2 |
| `docker-compose.yml` | Local infrastructure (Postgres now; full stack in Phase 4) | 1 |
