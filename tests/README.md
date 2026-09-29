# tests/

System-level tests that exercise the running stack from outside. Unit and integration tests live
next to the code: `backend/src/test/java/...` and `services/demo-service/src/test/java/...`.

## load/

- **`LoadTest.java`**: a small closed-loop load generator (C worker threads, each sends its next
  request when the previous one finishes). It reports throughput, latency avg/p50/p95/p99/max, status
  codes, and which backend instance served each successful request. There's nothing to install:

  ```bash
  java tests/load/LoadTest.java --url http://localhost:8080/gateway/orders/orders \
       --requests 1000 --concurrency 20 [--warmup 500] [--header X-User-Id:alice]
  ```

- **`perf-suite.sh`**: the Phase 4 performance suite: 100 / 500 / 1000 / 5000 concurrent users,
  and rate limiting, response cache and circuit breaker each OFF vs ON. It restarts the gateway with
  the right configuration per scenario and records gateway CPU seconds and peak RSS from `/proc`.
  Results: [`docs/results/phase-4/performance.txt`](../docs/results/phase-4).

- **`compare-strategies.sh`**: registers `/orders-rr`, `/orders-wrr` and `/orders-lc` over the same three
  instances, then measures the distribution of each strategy sequentially and with one slow instance.

Measured results are in [`docs/results/phase-2`](../docs/results/phase-2). Failure simulation: `scripts/demo-failure-scenario.sh`.
Users run on Java 21 virtual threads (the first version's two platform threads per user hit the OS
thread limit at 5000 users; see `docs/results/phase-4/performance-attempt-1-partial.txt`).
