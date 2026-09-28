-- V2 (Phase 3): automatic health checks and richer request logs. Additive only: existing rows keep working.

-- Which path the gateway probes on each instance of the service (the demo services expose /health).
ALTER TABLE services
    ADD COLUMN health_check_path VARCHAR(200) NOT NULL DEFAULT '/health';

-- The caller identity used for rate limiting (X-User-Id now, JWT subject in Phase 4). Kept as text:
-- gateway clients are not necessarily rows in `users`, so user_id (FK) stays for authenticated users.
ALTER TABLE request_logs
    ADD COLUMN client_id VARCHAR(100);

-- "Recent failures" and error-rate queries filter on status and time.
CREATE INDEX idx_request_logs_status_created ON request_logs (status_code, created_at DESC);
