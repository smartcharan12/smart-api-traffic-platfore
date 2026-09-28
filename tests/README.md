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

- **`compare-strategies.sh`**: registers `/orders-rr`, `/orders-wrr` and `/orders-lc` over the same three
  instances, then measures the distribution of each strategy sequentially and with one slow instance.

Measured results are in [`docs/results/phase-2`](../docs/results/phase-2). A full load-test suite (k6,
100 to 5000 users) and failure-simulation scripts are planned for Phase 4.
