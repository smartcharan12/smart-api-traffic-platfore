# Smart API Traffic & Failure Management Platform

An API gateway that manages traffic to backend services: service registry, rate limiting, load
balancing, circuit breaking, retries, health monitoring and metrics. It is built in four phases as a
learning project, and **this README only describes what is implemented so far**. The complete README
is written at the end of Phase 4.

## Status

| Phase | Scope | Status |
|---|---|---|
| 1. Foundation & core backend | Service registry (PostgreSQL), REST APIs, health endpoint, React dashboard | **Done** |
| 2. Intelligent traffic management | Rate limiters, load balancers, request routing/proxying, failure handling | Planned |
| 3. Distributed features & monitoring | Circuit breaker, retries, Redis, concurrency, health checks, request logs, Prometheus/Grafana | Planned |
| 4. Productionisation | Testcontainers, failure simulation, Docker, CI/CD, load tests, security | Planned |

## What works today

- Register **services** (e.g. `orders-service` at `/orders`, with a load-balancing strategy) and their
  **instances** (`host:port`, weight) through a REST API backed by PostgreSQL.
- Validation with per-field error messages; consistent RFC 9457 `problem+json` errors
  (400 / 404 / 409 / 503).
- `GET /api/health` reports database status (503 when the database is down).
- A React dashboard listing services and instances, with forms to register them.
- 22 automated tests: unit (Mockito), web slice (MockMvc) and integration (real PostgreSQL 17).

## Quick start

**Prerequisites:** JDK 21, Node.js ≥ 20.19, and PostgreSQL 16+ (installed locally or via Docker).
Maven is **not** required; the repo includes the Maven Wrapper (`./mvnw`).

```bash
git clone <your-repo-url> smart-api-traffic-platform
cd smart-api-traffic-platform
cp .env.example .env              # then edit DB_PASSWORD
```

**1. Start PostgreSQL**, either with Docker:

```bash
docker compose up -d postgres
```

or natively (Ubuntu/Mint), using the password you put in `.env`:

```bash
sudo apt install postgresql
sudo -u postgres psql -c "CREATE ROLE smart_api LOGIN PASSWORD 'your-password';"
sudo -u postgres psql -c "CREATE DATABASE smart_api OWNER smart_api;"
```

**2. Start the backend** (Flyway creates the tables on first start):

```bash
cd backend
set -a; source ../.env; set +a    # export the variables from .env
./mvnw spring-boot:run
```

**3. Start the dashboard** in another terminal:

```bash
cd frontend
npm install
npm run dev                       # open http://localhost:5173
```

**4. Try the API:**

```bash
curl -X POST localhost:8080/api/services -H 'Content-Type: application/json' \
     -d '{"name":"orders-service","basePath":"/orders","loadBalancingStrategy":"WEIGHTED_ROUND_ROBIN"}'
curl -X POST localhost:8080/api/service-instances -H 'Content-Type: application/json' \
     -d '{"serviceId":1,"host":"localhost","port":9001,"weight":3}'
curl localhost:8080/api/service-instances
curl localhost:8080/api/health
```

## Tests

```bash
cd backend && ./mvnw verify       # unit + web + integration tests (downloads embedded PostgreSQL once)
cd frontend && npm run build      # type-check and production build
```

## API (Phase 1)

| Method | Path | Description |
|---|---|---|
| POST | `/api/services` | Register a service → 201 + `Location` |
| GET | `/api/services` | List services |
| GET | `/api/services/{id}` | Get one service |
| DELETE | `/api/services/{id}` | Delete a service and its instances → 204 |
| POST | `/api/service-instances` | Register an instance → 201 + `Location` |
| GET | `/api/service-instances?serviceId=` | List instances (optionally for one service) |
| GET | `/api/service-instances/{id}` | Get one instance |
| DELETE | `/api/service-instances/{id}` | Remove an instance → 204 |
| GET | `/api/health` | Backend + database health (200 / 503) |

## Documentation

- [Architecture](docs/architecture.md): target design, what's built, layer responsibilities
- [Database design](database/README.md): ER diagram, keys, indexes, normalisation
- [Phase 1 guide](docs/phase-1-guide.md): every concept explained, bugs found, interview questions

## Project structure

```
backend/     Spring Boot app (Java 21, Spring Boot 3.5): REST API, JPA, Flyway migrations, tests
frontend/    React 19 + TypeScript dashboard (Vite)
database/    Schema documentation and ER diagram
docs/        Architecture and learning guides
services/    Demo backend services (Phase 2)
monitoring/  Prometheus + Grafana config (Phase 3)
docker/      Dockerfiles (Phase 4)
tests/       Load and failure tests (Phase 4)
```

## License

[MIT](LICENSE)
