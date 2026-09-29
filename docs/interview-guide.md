# Interview guide

Everything here is about code in this repository. Deeper answers, with the code and test references,
are in the phase guides ([1](phase-1-guide.md), [2](phase-2-guide.md), [3](phase-3-guide.md)) and
[system-design.md](system-design.md).

Format of each question: **Q** · *what the interviewer is testing* · **Strong answer** ·
*Likely follow-up* · ⚠ *Common mistake*.

## Explaining the project

### 30 seconds

> I built an API gateway in Java and Spring Boot: the component that sits in front of backend services
> and decides whether a request may go through and where it goes. I implemented the core algorithms
> myself: three rate limiters, three load balancers, a circuit breaker and a retry engine with backoff
> and jitter. Shared state lives in Redis, so several gateway instances enforce one limit. It has
> automatic health checks, request logging, Prometheus/Grafana metrics, a React dashboard, JWT
> security, Docker and CI, and 197 automated tests including concurrency and failure tests.

### 1 minute

> The problem: an app depends on several services, each with several instances. One gets slow,
> another returns errors, a client floods the API, and naive retries make it worse. My gateway handles
> each of these. Clients are rate-limited per user, IP or endpoint. The token bucket is the default,
> and all three algorithms also run as atomic Lua scripts in Redis. I measured two gateways allowing
> exactly the limit, where in-memory state allowed double. Requests go only to healthy instances,
> chosen by round robin, smooth weighted round robin or least connections. A per-backend circuit
> breaker fails fast when a backend keeps failing; transient failures are retried with exponential
> backoff and jitter, only for idempotent requests, within a retry budget. Everything is observable:
> request logs with p95/p99, Prometheus and Grafana, and a dashboard where an admin can inject a
> failure and watch the instance go DEGRADED, DOWN, circuit OPEN, and recover.

### 2 minutes

Add to the 1-minute version:

> - **Architecture:** a Spring Boot gateway with a PostgreSQL registry. A request passes a security
>   filter (JWT), then a pipeline: rate limiter, response cache, service discovery with a TTL cache
>   that's invalidated across gateways by Redis pub/sub, health filter, then a forwarder that asks the
>   circuit breaker, the load balancer and the proxy, with retries.
> - **Concurrency:** limiters use `ConcurrentHashMap.compute` in memory and Lua in Redis;
>   round robin uses an atomic ticket; the retry budget is a CAS loop; each circuit breaker has its
>   own lock. I tested 1000 concurrent requests for exact counts.
> - **Resilience:** the gateway keeps working if Redis dies (local fallback limits, health DEGRADED)
>   and if PostgreSQL dies (cached routes keep flowing, management API returns 503). Both are tested.
> - **What I learned by measuring:** profiling showed 65% of gateway CPU was re-reading the registry
>   per request; a cache halved CPU per request. I also found and fixed a stale-cache race, a Redis
>   dependency that stopped startup, and a Prometheus label collision.

### 5 minutes (outline)

1. **Problem and scope** (30 s): the failure modes above; it's a learning project, measured on a laptop.
2. **Request lifecycle** (1 min): walk through README §5, step by step, with the status code at each exit.
3. **One algorithm in depth** (1 min): the token bucket with lazy refill, or smooth weighted round
   robin traced for weights 3:1 (A, A, B, A); give the complexity.
4. **Distributed part** (1 min): why a HashMap fails with two gateways (60 vs 30 measured), Lua
   atomicity, fail-soft when Redis is down, leader lease for health checks, pub/sub plus TTL.
5. **Failure demo** (1 min): the timeline from `demo-failure-scenario.sh`: HEALTHY → DEGRADED → DOWN,
   CLOSED → OPEN, recovery via two good probes and two trial calls. Why the breaker catches what health
   checks can't.
6. **Engineering practice** (30 s): tests at every level including concurrency and failure;
   Testcontainers in CI; static checks; Docker; honest limitations (per-process breakers, blocking
   retries, one Redis/PostgreSQL, not run under real traffic).

### Resume bullets

- Built an API gateway in Java 21 / Spring Boot with hand-written rate limiting (fixed window, sliding
  window, token bucket; atomic Redis Lua scripts shared across gateway instances), load balancing
  (round robin, smooth weighted round robin, least connections) and a circuit breaker with retry
  backoff, jitter and a retry budget; verified with 1,000-request concurrency tests allowing exactly
  the configured limit.
- Added resilience and observability: leader-elected health checks (HEALTHY/DEGRADED/DOWN), an
  asynchronous batched request log, Prometheus metrics with p95/p99 and a Grafana dashboard. In
  load tests on a laptop, the circuit breaker raised throughput from 48.5 to 195 req/s and removed
  all 504s with one backend timing out; the response cache raised throughput from 545 to 903 req/s.
- Secured and shipped it: JWT authentication with role-based access, BCrypt, CORS and size limits;
  197 automated tests (unit, concurrency, Testcontainers integration, failure injection); Docker
  Compose for 12 containers; a GitHub Actions pipeline with static analysis and a full-stack smoke
  test. Found and fixed bugs via profiling and load tests (e.g. per-request DB lookups using 65% of
  CPU; a token-bucket race under load).

---


---

## Java

**Q1. What's the difference between `synchronized`, `volatile` and `AtomicInteger`, and where did you use each?**
*Testing:* memory model, atomicity vs visibility.
**Strong answer:** `volatile` gives visibility (every thread sees the latest write) but no atomic
read-modify-write; I use it for flags with one writer (the leader flag, the demo's chaos settings,
swapped as one immutable object). `AtomicInteger` gives lock-free atomic updates of one variable via
CAS; it's used for round-robin tickets, active-connection counts and the retry budget (a CAS loop).
`synchronized` gives mutual exclusion plus visibility for several fields that must change together:
the circuit breaker's state machine and the smooth-WRR weights, one lock per breaker/service.
*Follow-up:* does `volatile int count; count++` work? No: it's three operations. I measured a plain
`int++` from 8 threads losing up to 87% of 8 million increments.
⚠ "volatile makes it thread-safe."

**Q2. How does `ConcurrentHashMap.compute` differ from `get` + `put`?**
*Testing:* compound atomicity.
**Strong answer:** get-then-put is two operations; two threads can interleave and lose an update.
`compute` runs the remapping function atomically for that key while other keys proceed in parallel. All
in-memory rate limiters use it for check-and-update. Keep the lambda short, do no I/O in it, and don't
touch other keys.
⚠ "ConcurrentHashMap makes every operation on it atomic."

**Q3. Why records for DTOs and immutable snapshots?**
**Strong answer:** records are immutable value carriers with equals/hashCode/toString; immutable
objects can be shared between threads without locks (`BackendInstance`, `CircuitBreaker.Snapshot`,
the rate-limit `Window`, which is replaced, never mutated, inside `compute`).

**Q4. What happens if an exception is thrown inside a `finally` block?**
*Testing:* exception semantics.
**Strong answer:** it replaces the original exception. That's why the request-log submission in the
gateway's `finally` is wrapped in its own try/catch: a logging failure must not hide the real outcome.

**Q5. Why `Math.floorMod` instead of `%` in round robin?**
**Strong answer:** the ticket counter overflows to negative after 2³¹ requests, and `negative % n`
is negative, so it would be an invalid list index. `floorMod` is always in [0, n).

## OOP and design

**Q6. Where did you use the Strategy pattern and what does it buy you?**
*Testing:* polymorphism and open/closed.
**Strong answer:** `LoadBalancingStrategy` has three implementations, looked up by the service's
configured type through an `EnumMap` built from all strategy beans (startup fails if one is missing).
`RateLimiter` has fixed-window, sliding-window, token-bucket, Redis, fallback and no-op
implementations, chosen by one factory method. The gateway depends only on the interfaces
(dependency inversion); adding an algorithm is a new class, not an edit (open/closed).
*Follow-up:* where's the Null Object pattern? `NoOpRateLimiter`.
⚠ Describing the pattern without naming where it's used.

**Q7. Why is the circuit breaker reusable for Redis too?**
**Strong answer:** it's a plain class with a `Clock`, a config and a listener: no Spring, no HTTP.
`RedisGuard` wraps every Redis call in one, so a Redis outage is detected once and then skipped for
5 s instead of costing a timeout per request. That's single responsibility plus composition.

**Q8. Why do exceptions carry their HTTP status (`GatewayException`) instead of mapping in one big switch?**
**Strong answer:** each failure type owns its status and extra headers (429 with Retry-After, 503
circuit-open with Retry-After). One handler renders them all. Adding a failure type doesn't touch the
handler.

## Data structures and algorithms

**Q9. Compare fixed window, sliding window log and token bucket.**
**Strong answer:** fixed window keeps one counter per key, O(1), but allows up to 2 × limit around a
boundary (tested). The sliding log keeps a deque of timestamps: exact, amortised O(1), but O(limit)
memory per key. The token bucket keeps two numbers with lazy refill: O(1), allows bursts up to
capacity, exact long-run rate. It's the default.
*Follow-up:* the Redis versions: INCR + PEXPIRE, a sorted set with ZREMRANGEBYSCORE/ZCARD/ZADD, a
hash {tokens, ts}, each an atomic Lua script.

**Q10. Why does a deque work for the sliding window?**
**Strong answer:** timestamps arrive in order, so the oldest is always at the head. Expire from the
head, append at the tail. Each timestamp is added and removed once, so O(1) amortised.

**Q11. Trace smooth weighted round robin for weights 3 and 1.**
**Strong answer:** current weights (A, B): +(3,1) → pick A → (−1,1); +(3,1) → (2,2), tie → A → (−2,2);
→ (1,3) → B → (1,−1); → (4,0) → A → (0,0). Sequence AABA, exact weights per cycle, interleaved
rather than AAAB.

**Q12. How is the circuit breaker's failure rate computed in O(1)?**
**Strong answer:** a ring buffer of the last N outcomes plus a running failure count. Overwrite the
oldest slot, decrement the count if it was a failure, increment if the new one is.

**Q13. Complexity of least connections? Why the rotating start index?**
**Strong answer:** O(n) scan. Without rotation, under light sequential traffic every count is 0 and
the first instance always wins; rotation spreads ties (measured 200/200/200).

## DBMS

**Q14. Why IDENTITY for most tables but a pooled sequence for `request_logs`?**
*Testing:* ORM and database interplay.
**Strong answer:** with IDENTITY, Hibernate must execute each INSERT to learn the id, so it can't batch.
`request_logs` is the high-volume table, written in batches of up to 500 by the log writer, so it uses
a sequence with INCREMENT 50 and `allocationSize = 50`.

**Q15. Two admins register the same service name simultaneously. What happens?**
**Strong answer:** both may pass the `existsByName` check; the UNIQUE constraint rejects the second
insert; `DataIntegrityViolationException` becomes 409. Application checks give nice messages; the
constraint gives correctness.

**Q16. Which indexes exist on `request_logs` and which queries use them?**
**Strong answer:** `(created_at DESC)` for "latest", `(service_id, created_at DESC)` for "latest for a
service", `(status_code, created_at DESC)` for failures and error rate (added in V2), `(request_id)`
for lookup by trace id. Every index slows inserts, so there are no others.

**Q17. How do you compute p95 in SQL, and why `generate_series` in the per-minute query?**
**Strong answer:** `percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms)`. Without
`generate_series`, minutes with no rows are missing, and charts would draw 03:55 next to 04:02. It
generates every minute and LEFT JOINs the counts.

**Q18. What happens to the gateway when PostgreSQL goes down?**
**Strong answer:** tested: `/api/health` 503, management API 503 (not 500), JWTs still verify (no DB
needed), and proxying continues for routes in the discovery cache. Request-log rows are dropped and
counted.

## Operating systems

**Q19. Thread pool vs a thread per task: where do you use pools and how are they sized?**
**Strong answer:** Tomcat's request pool (200 by default); the health-probe pool
(`newFixedThreadPool(8)`, daemon threads, reused every cycle); Spring's scheduler (raised from 1 to 4
threads because one slow health-check cycle was delaying every other scheduled task); the JDK
HttpClient's executor. Fixed pools bound resource use; each probe also has a timeout.

**Q20. What is a deadlock and why can't your code deadlock?**
**Strong answer:** a cycle of threads each holding a lock another needs. No code path here holds two
locks at once; the breaker listener takes only its own history lock, always after the breaker's.

**Q21. What went wrong when you rebuilt the jar while the gateway was running?**
**Strong answer:** the JVM loads classes lazily from the jar. Overwriting it made later class loads
fail (`ClassNotFoundException` for a logging class) and request threads parked. The fix is operational:
run from an immutable artifact (a copy, or a container image).

## Computer networks and HTTP

**Q22. 502 vs 503 vs 504 in your gateway.**
**Strong answer:** 502: reached the backend but got a broken or oversized response. 503: no usable
backend (none registered, all DOWN, all refused, every circuit open), with Retry-After where known.
504: the backend accepted the request but didn't answer within 5 s. A backend's own 500 is passed
through unchanged.

**Q23. What are hop-by-hop headers?**
**Strong answer:** Connection, Keep-Alive, Transfer-Encoding, Upgrade, TE, Trailer, Proxy-*. They
describe one connection, so a proxy strips them and sets its own (plus X-Forwarded-For/Host/Proto).

**Q24. Why don't you trust X-Forwarded-For?**
**Strong answer:** any client can set it and rotate it to escape per-IP limits. It's trusted only
behind a proxy you control, and then only the rightmost entry. I even removed an XFF-trust setting
from my own docker-compose, because port 8080 is published directly.

**Q25. What does CORS protect, and why is CSRF disabled?**
**Strong answer:** CORS lets a browser page from another origin read our responses; only the
dashboard origin is allowed. CSRF abuses cookies that browsers attach automatically. This API uses an
`Authorization: Bearer` header, which browsers never attach on their own, so there's nothing to forge.

## Spring Boot

**Q26. How does a request become authenticated?**
**Strong answer:** the resource-server filter reads `Authorization: Bearer`, the `JwtDecoder` verifies
the HS256 signature, expiry (against the injected Clock) and issuer, and the `role` claim becomes
`ROLE_ADMIN`/`ROLE_USER`. There are two filter chains: `/gateway/**` (token optional but validated;
Cache-Control left to the backend) and everything else (reads for any user, changes ADMIN-only).
*Follow-up:* a forged `"role":"ADMIN"` payload keeps the old signature, so it's rejected (tested).

**Q27. Why self-invocation matters for `@Transactional` in this project.**
**Strong answer:** the discovery cache and the transactional registry lookup are separate beans. A
`this.load()` call would bypass the Spring proxy (no transaction), and a transaction around the cache
check would borrow a DB connection even on cache hits.

**Q28. How did you test timing-dependent behaviour without sleeping?**
**Strong answer:** a `Clock` bean everywhere time matters; tests swap in a `MutableClock` and call
`advance()`. That covers circuit half-open after 10 s, token refill, JWT expiry, and fresh rate-limit
windows between tests.

**Q29. What does `ddl-auto: validate` + Flyway give you?**
**Strong answer:** the schema is versioned SQL (V1, V2, additive); Hibernate only checks that entities
match it and never alters production tables.

## Redis

**Q30. Why Redis at all? Prove it's needed.**
**Strong answer:** measured with two gateway processes: a client alternating between them got 60 of
60 requests through a "30 per 10 s" limit with in-memory state, and exactly 30 with Redis; 1000
concurrent requests across both gateways allowed exactly 30.

**Q31. How do you make a Redis rate limit atomic across gateways?**
**Strong answer:** one Lua script per check. Redis runs scripts to completion without interleaving,
so read-compare-write can't race. Each script touches one key, so it's cluster-compatible.

**Q32. Redis is down: what happens?**
**Strong answer:** RedisGuard's circuit opens after 3 failures; limits fall back to local
(fail-soft); the cache is skipped; the leader lock says "run anyway"; health is DEGRADED (200). The
gateway still starts without Redis. That was a bug I found: the pub/sub subscriber blocked startup,
so it's now started in the background.

**Q33. Cache hit/miss/invalidation: how does your response cache decide?**
**Strong answer:** only anonymous GETs, and only 200 responses whose `Cache-Control` has max-age or
s-maxage and no private/no-store/no-cache, no Set-Cookie, no Vary. TTL = the backend's max-age (capped
at 5 min); Redis expires entries. `X-Cache` and `Age` headers. Invalidation of the *discovery* cache is
by pub/sub plus TTL, with a generation counter for the in-flight-load race.

## Concurrency

**Q34. Walk me through a race condition you found and fixed.**
**Strong answer:** discovery cache: request A loads the registry, an admin marks an instance DOWN,
the commit clears the cache, then A stores its stale result, so the DOWN instance keeps getting traffic
for 5 s. Fixed with a generation counter read before loading and bumped on invalidation. A test
reproduces the interleaving deterministically by triggering the invalidation from inside the mocked
database call.

**Q35. How do you log every request without blocking requests?**
**Strong answer:** a bounded `ArrayBlockingQueue`: `offer()` from request threads never blocks; one
writer `poll()`s then `drainTo()`s up to 500 rows into one JDBC batch. Queue full or DB down → drop and
count. Tested with 1000 concurrent producers (0 lost when there's room).

**Q36. Your least-connections has a race. Why keep it?**
**Strong answer:** two threads can see the same minimum and pick the same instance. Fixing it needs
one lock around read-all + pick + increment, which serialises the whole gateway. The counters stay
exact (atomic, tested back to 0 after 1000 concurrent acquire/release pairs); only the pick is
slightly uneven.

## System design

**Q37. How would you scale this to 100× traffic?**
**Strong answer:** see [system-design.md](system-design.md). In short: more gateway processes behind a
load balancer, which already works because state is in Redis/PostgreSQL; virtual threads for blocking
retries; Kafka for request events instead of PostgreSQL; Redis Cluster (single-key scripts already
compatible); Kubernetes discovery; OpenTelemetry tracing.

**Q38. Which parts are CP and which AP?**
**Strong answer:** registry writes are consistent (single primary, constraints). Rate limits choose
availability (local fallback during a Redis partition, N × limit worst case). Routing uses a cache with
bounded staleness (≤ 5 s TTL, pub/sub for the fast path).

**Q39. Why is the circuit breaker per gateway and not shared?**
**Strong answer:** a shared breaker needs a network round-trip per request and becomes a dependency
itself. Each gateway learns from its own traffic within a few calls. Resilience4j and Hystrix are
local too.

## Docker

**Q40. How do your containers talk to each other?**
**Strong answer:** Compose puts them on one bridge network with built-in DNS: the gateway uses
`postgres:5432` and `redis:6379`, and Prometheus scrapes `gateway:8080`. Only the gateway, dashboard,
Prometheus and Grafana ports are published; the database, Redis and backends are unreachable from the
host.

**Q41. What's in your gateway image and why?**
**Strong answer:** multi-stage: JDK stage resolves dependencies first (cached layer), then builds; the
runtime stage has a JRE, curl for the health check, and the jar; a non-root user;
`MaxRAMPercentage=75` so the heap respects the container memory limit.
*Follow-up:* was it run? Honestly, not on my development machine (no Docker/root there). The CI
pipeline builds the images, starts the whole stack and runs a smoke test.

## Testing

**Q42. What does each level of your test pyramid prove?**
**Strong answer:** unit tests (algorithms with a fake clock); concurrency tests (exact counts under 50
threads); web-slice tests (status codes, validation); integration tests against real PostgreSQL and
Redis (Testcontainers in CI, local binaries otherwise) over real HTTP to real stub backends; failure
tests (DB outage, Redis down, timeouts, refused connections, malformed input, 413); a CI smoke test of
the Docker stack.

**Q43. A test failed only in the full suite. How did you handle it?**
**Strong answer:** I didn't loosen it blindly. I captured the access log and found legitimate retries
after 500 ms timeouts under a loaded JVM. So the test now asserts invariants that always hold (hits =
requests + retries reported) and requires the exact distribution only when no retry happened. The exact
distribution is proven separately in a deterministic unit test.

## Monitoring

**Q44. How do you get p99 latency in Prometheus, and why not average p99s across gateways?**
**Strong answer:** a histogram with fixed buckets plus `histogram_quantile(0.99, sum by (le)(rate(..._bucket[1m])))`.
Buckets add across instances; percentiles don't average.

**Q45. What's label cardinality and what bug did you hit with labels?**
**Strong answer:** every label combination is a series. Service labels are registered names or
`unresolved`, never raw paths. The bug: I labelled backends `instance`, which Prometheus reserves for
the scrape target, so every backend showed as `localhost:8080`. It was spotted in the first Grafana
screenshot and renamed to `upstream`.

---

## 10 trap questions (and honest answers)

1. **"So this handles millions of requests?"** No. Measured on a laptop shared with everything else:
   see the README's performance section for the exact numbers. The design scales horizontally; the
   numbers are for relative comparison only.
2. **"Is it a distributed system?"** Partly, and I can say exactly which parts: multiple gateway
   processes share Redis/PostgreSQL state (tested with two). Circuit breakers are per process. There's
   one Redis and one PostgreSQL, with no replication.
3. **"Did you run it in Docker?"** Not on my machine (no Docker/root). CI builds the images and
   smoke-tests the Compose stack; locally I ran the same components as processes.
4. **"Your rate limiter is exact, right?"** Exactly the limit under 1000 concurrent requests with frozen
   time. The fixed window allows 2 × limit at a boundary by design, and during a Redis outage N gateways
   allow up to N × limit.
5. **"Why not use Resilience4j / Spring Cloud Gateway?"** To understand and test the algorithms. In
   production I'd seriously consider them; my versions are small and well tested but less
   battle-proven.
6. **"Isn't HMAC JWT fine forever?"** A shared secret means every verifier could also mint tokens. At
   scale: asymmetric keys (JWKS) with rotation.
7. **"You retry on timeout: can a payment be charged twice?"** Only idempotent methods, or requests
   with an Idempotency-Key, are retried after a timeout, and the backend must honour the key. A plain
   POST is never retried once it was sent.
8. **"What if the health checker's leader is paused mid-cycle?"** It may probe after losing the lease.
   That's harmless for idempotent probes; work that must run exactly once would need fencing tokens.
9. **"Why do cache hits show no service in the logs?"** Hits are served before discovery. That's a
   known limitation, listed in the review.
10. **"Are your load-test numbers reproducible?"** The script is in the repo
    (`tests/load/perf-suite.sh`), but absolute numbers depend on the machine. Single runs on this
    laptop varied by up to about 30%.
