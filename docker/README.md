# Docker

The Dockerfiles live next to the code they build (`backend/Dockerfile`, `services/demo-service/Dockerfile`,
`frontend/Dockerfile`); the whole stack is `docker-compose.yml` at the repository root.

```bash
cp .env.example .env       # set DB_PASSWORD, JWT_SECRET, ADMIN_USERNAME/PASSWORD, GRAFANA_ADMIN_PASSWORD
docker compose up -d --build
docker compose ps          # all containers "healthy"/"running"; demo-setup "exited (0)"
```

| URL on the host | Container |
|---|---|
| http://localhost:5173 | `frontend`: nginx serving the dashboard, proxying `/api` and `/gateway` to the gateway |
| http://localhost:8080 | `gateway`: `/gateway/**` for API clients, `/api/**` management |
| http://localhost:9090 | `prometheus` |
| http://localhost:3000 | `grafana` (anonymous read-only; admin password from `.env`) |

## How the containers find each other

Compose puts every service on one private bridge network and runs a DNS server on it: each
container can reach the others **by service name**. The gateway connects to `postgres:5432` and
`redis:6379`; Prometheus scrapes `gateway:8080`; nginx proxies to `gateway:8080`; the demo backends are
registered as `orders-1:9001`, `orders-2:9002`, ... and the gateway's health checker probes them there.

Only the ports under `ports:` are published to the host. PostgreSQL, Redis and the demo backends are
**not** published: nothing outside the Docker network can reach them. Inside the network, containers
use the container port (`9001`), not a host port.

## Startup order and health

`depends_on` with `condition: service_healthy` makes the gateway wait for PostgreSQL (`pg_isready`)
and Redis (`redis-cli ping`), and makes `demo-setup` wait for the gateway (`/actuator/health`).
`demo-setup` is a one-shot container that logs in as the admin and registers the demo services, then
exits with code 0. Flyway migrates the schema when the gateway starts.

## Image design

- **Multi-stage builds:** a JDK/Node stage compiles; the runtime image has only a JRE (or nginx) and
  the artifact. There's no compiler or source code in production images.
- **Dependency layer first:** `pom.xml` / `package-lock.json` are copied and resolved before the
  source, so code-only changes reuse the cached dependency layer.
- **Non-root:** the Java images run as a dedicated system user.
- **Container-aware heap:** `-XX:MaxRAMPercentage=75` sizes the gateway heap from the container's
  memory limit.
- Tests are not run during `docker build` (they need PostgreSQL/Redis); CI runs them before building
  the images.

## Verification status

The images and the full stack are built and smoke-tested by the CI `docker` job
(`scripts/ci-smoke-test.sh`). **The development machine used for this project has no Docker** (and
no root access), so `docker compose up` was not run there. The same components were run as native
processes with the same configuration instead (see the README's local setup).
