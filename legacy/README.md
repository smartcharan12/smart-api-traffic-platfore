# legacy/

Copies of files as they were **before** a later phase substantially rewrote them. They are kept for
comparison and as a rollback path. They are **not compiled or used**: nothing on the build path
references this folder. Git history holds the same content; these copies make before/after
comparison easy without digging through commits. The tag `phase-2-complete` marks the full Phase 2 state.

| Legacy copy | Original location | Taken at | Replaced by |
|---|---|---|---|
| `phase-2/backend/GatewayService.java` | `backend/src/main/java/com/smartapi/gateway/routing/GatewayService.java` | tag `phase-2-complete` | Same file. Forwarding moved to `routing/ResilientForwarder.java` (circuit breaker + retry engine replaced the "connection refused only" failover loop). |
| `phase-2/backend/GatewayProperties.java` | `backend/src/main/java/com/smartapi/gateway/config/GatewayProperties.java` | tag `phase-2-complete` | Same file. `proxy.maxConnectAttempts` was replaced by `retry.maxAttempts`; `circuitBreaker` and `retry` sections were added. |
| `phase-2/frontend/App.tsx` | `frontend/src/App.tsx` | tag `phase-2-complete` | Same file, now only the router and layout. The Phase 2 single page (registry tables + forms) moved unchanged to `frontend/src/pages/ServicesPage.tsx`. |
| `phase-1/docker-compose.yml` | `docker-compose.yml` | tag `phase-2-complete` (unchanged since Phase 1) | Same file, now the full stack (Phase 4). The Phase 1 version ran PostgreSQL only. |

Remove a copy only when its replacement is verified and you decide the rollback is no longer needed.
