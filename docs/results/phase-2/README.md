# Phase 2 measurement outputs

Raw console output of every demo and measurement quoted in the README and the Phase 2 guide.
Nothing here was edited except for the NOTE lines added at the top of some files, which record
known problems with a measurement (a tool bug, a wrong PID).

**Environment:** Intel Core i5-1035G1 (4 cores / 8 threads), 7.6 GB RAM, Linux Mint 22.3, JDK 21.0.12.
The gateway, 5 demo-service JVMs, PostgreSQL 17 and the load generator all ran on this one laptop
(plus a desktop session and browser). Numbers are therefore only meaningful relative to each other,
and single runs varied by up to about 30%.

| File | What | Produced by |
|---|---|---|
| `demo1.txt` | Normal routing, request-id propagation, 404s | curl |
| `demo2.txt` | Weighted round robin 3/2/1 distribution and pick order | `tests/load/LoadTest.java` + curl |
| `demo3.txt` | 5 requests / 10 s: requests 1-5 allowed, 6 rejected with 429, 7 allowed after 10 s | curl |
| `demo4-failures.txt` | Killed instance, DOWN instance, backend 500, 504 timeout, all-down 503 | `scripts/*`, `LoadTest.java`, curl |
| `demo5.txt` | RR vs WRR vs least connections, sequential and with one slow instance | `tests/load/compare-strategies.sh` |
| `perf-no-ratelimit.txt` | First throughput run, direct vs through gateway (before the discovery cache) | `LoadTest.java` |
| `perf-cache-comparison.txt` | Same jar, discovery cache off vs on, 100/500/1000 requests | `LoadTest.java` |
| `perf-cpu-per-request.txt` | Gateway CPU-ms per request (from /proc), cache off vs on, 2 runs each | `LoadTest.java` + /proc |
| `perf-ratelimit.txt` | 1000 requests against the default 100/minute token bucket | `LoadTest.java` |
