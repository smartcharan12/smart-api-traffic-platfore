# Phase 1 guide: foundation and core backend

This guide explains every concept used in Phase 1, pointing at the actual code. Read it with the code
open. The goal is that you can explain each decision yourself, not just repeat it.

- [1. What we are building](#1-what-we-are-building)
- [2. Spring Boot, beans and dependency injection](#2-spring-boot-beans-and-dependency-injection)
- [3. Layered architecture: controller, service, repository, DTO, entity](#3-layered-architecture)
- [4. JPA and Hibernate](#4-jpa-and-hibernate)
- [5. Database design and Flyway migrations](#5-database-design-and-flyway-migrations)
- [6. REST API design and status codes](#6-rest-api-design-and-status-codes)
- [7. Validation and centralised exception handling](#7-validation-and-centralised-exception-handling)
- [8. Testing strategy](#8-testing-strategy)
- [9. The React dashboard](#9-the-react-dashboard)
- [10. Bugs found while building Phase 1](#10-bugs-found-while-building-phase-1)
- [11. Known limitations (honest review)](#11-known-limitations-honest-review)
- [12. Interview questions](#12-interview-questions)

---

## 1. What we are building

**Simple version.** Imagine a food-delivery app. The app itself doesn't do everything: it calls an
*orders* service, a *payments* service, a *restaurants* service. Each of those runs as several copies
(instances) on different machines. Something has to sit in front of them and decide which copy gets
each request, stop one user from flooding the system, notice when a copy is broken and stop sending
traffic to it, and retry sensibly. That "something" is an **API gateway**, and that is this project.

**Technical version.** A Spring Boot API gateway with a PostgreSQL-backed *service registry*
(which services and instances exist), which in later phases proxies client requests to backend
instances through a pipeline of rate limiting, health filtering, circuit breaking, load balancing and
retries, backed by Redis for shared state and observed through Prometheus and Grafana.

**Phase 1 builds the registry.** The gateway cannot route anything until it knows what exists. Phase 1
gives us:

- the database schema for users, services, instances and request logs
- REST APIs to register and list services and instances
- a health endpoint
- a dashboard showing the registry

---

## 2. Spring Boot, beans and dependency injection

**What it is.** A *bean* is an object whose creation and lifetime Spring manages. *Dependency
injection (DI)* means a class declares what it needs (in its constructor) and Spring supplies it,
instead of the class creating its dependencies with `new`.

**Why we need it / the real problem.** Look at
[`ServiceRegistryService`](../backend/src/main/java/com/smartapi/gateway/service/ServiceRegistryService.java).
It needs an `ApiServiceRepository`. If it did `new ApiServiceRepositoryImpl(new DataSource(...))`
itself:

- it would need to know how to build a database connection (not its job)
- every class would create its own connection pool
- a unit test could not replace the repository with a fake

**Example.**

```java
@Service
public class ServiceRegistryService {
    private final ApiServiceRepository serviceRepository;

    public ServiceRegistryService(ApiServiceRepository serviceRepository) {  // Spring calls this
        this.serviceRepository = serviceRepository;
    }
}
```

In the test we call the same constructor with a Mockito mock:
[`ServiceRegistryServiceTest`](../backend/src/test/java/com/smartapi/gateway/service/ServiceRegistryServiceTest.java)
uses `@InjectMocks`, which does exactly that.

**How it works at startup.**

1. `@SpringBootApplication` turns on **component scanning** of `com.smartapi.gateway` and sub-packages.
2. Every class annotated `@Component`, `@Service`, `@RestController`, `@Configuration` is registered as
   a bean definition. Spring Data creates an implementation for every `JpaRepository` interface.
3. Spring builds a dependency graph and creates beans in order: DataSource, then EntityManager, then
   repositories, then services, then controllers.
4. Beans are **singletons** by default: one `ServiceRegistryService` object serves all requests,
   concurrently, from many threads. **This is why services must not keep per-request state in fields.**

**Why constructor injection (not `@Autowired` on fields).** Fields can be `final` (cannot be
reassigned), dependencies are visible in one place, the object is never half-built, and tests can
construct it without Spring.

**`@Configuration` + `@Bean`.** For objects we don't own, like `java.time.Clock`, we write a factory
method: see [`TimeConfig`](../backend/src/main/java/com/smartapi/gateway/config/TimeConfig.java).
`SystemHealthService` asks for a `Clock` rather than calling `Instant.now()`. In Phase 2 this matters a
lot: rate limiters depend on time, and a test can inject a fixed clock and *advance* it instead of
sleeping.

**Failure cases.**
- *Circular dependency*: A needs B and B needs A. With constructor injection Spring fails at startup
  with a clear error, which is better than failing later.
- *Two beans of the same type*: Spring can't choose → `NoUniqueBeanDefinitionException`. Fix with
  `@Primary` or `@Qualifier`.
- *Mutable state in a singleton*: a field like `private int requestCount` in a service is shared by all
  threads, giving a race condition. We'll deal with exactly this in the Phase 2 rate limiter.

---

## 3. Layered architecture

**What it is.** Code is split by responsibility (see the table in [architecture.md](architecture.md)).
A request flows Controller → Service → Repository → Database.

**Why.** Each layer can change without breaking the others, and each can be tested separately:

| Layer | Example | Tested by |
|---|---|---|
| Controller | [`ServiceController`](../backend/src/main/java/com/smartapi/gateway/controller/ServiceController.java) | `ServiceControllerTest` (`@WebMvcTest`, service mocked) |
| Service | `ServiceRegistryService`, `ServiceInstanceService` | Mockito unit tests (repository mocked) |
| Repository | `ApiServiceRepository` | Integration test against real Postgres |

**DTOs vs entities: why not return the entity directly?**

1. **Security / over-posting.** If `POST /api/users` accepted the `User` entity, a client could send
   `"role": "ADMIN"` and Jackson would happily set it. A DTO only has the fields we allow.
2. **Stable API.** Renaming a column changes the entity; the DTO (the public contract) stays the same.
3. **Lazy-loading traps.** Serialising an entity with a lazy `service` field outside a transaction
   throws `LazyInitializationException`, or triggers extra queries.
4. **Different shapes.** `ServiceInstanceResponse` includes `serviceName`, which isn't a column on
   `service_instances`.

DTOs are Java **records** (`record CreateServiceRequest(...)`): immutable, with
`equals/hashCode/toString` generated. That suits data that just travels between layers.

**Where `@Transactional` goes.** On service methods. A transaction groups several database operations
into one all-or-nothing unit. `register()` does an existence check *and* an insert in one transaction.
`readOnly = true` on queries lets Hibernate skip dirty-checking (a small speedup) and documents intent.

---

## 4. JPA and Hibernate

**What they are.** **JPA** (Jakarta Persistence API) is a specification: annotations like `@Entity`,
`@Id`, `@ManyToOne` and the `EntityManager` interface. **Hibernate** is the implementation that
actually generates SQL. **Spring Data JPA** sits on top and generates repository implementations from
interfaces.

**Example.** From the method name alone:

```java
boolean existsByServiceIdAndHostAndPort(Long serviceId, String host, int port);
```

Spring Data generates roughly:

```sql
select count(*) > 0 from service_instances where service_id = ? and host = ? and port = ?
```

**Important mapping choices in this project.**

- `@ManyToOne(fetch = LAZY)` on `ServiceInstance.service`: loading an instance does not load its
  service until you touch it. The default for `@ManyToOne` is EAGER, which silently loads more than you
  need.
- **No `@OneToMany List<ServiceInstance>` on `ApiService`.** It's convenient but makes accidental
  loading of every instance easy. We query instances explicitly.
- `@Enumerated(EnumType.STRING)`: stores `'HEALTHY'`, not `1`. With `ORDINAL`, inserting a new enum
  constant in the middle silently changes the meaning of every stored row.
- `ddl-auto: validate`: Hibernate **never** changes the schema. It checks that entities match the tables
  Flyway created and refuses to start if they don't. `update` is dangerous in production: it can't
  rename or drop, it guesses, and you have no history.
- `open-in-view: false`: by default Spring keeps the Hibernate session open until the HTTP response
  is written, so lazy loads work anywhere, including JSON serialisation. That hides N+1 bugs and holds
  a DB connection for the entire request. We turned it off; all loading happens inside services.

**The N+1 problem (a very common interview question).** Listing 100 instances and reading
`instance.getService().getName()` for each one would run 1 query for the instances plus 100 queries
for their services. The fix is in
[`ServiceInstanceRepository`](../backend/src/main/java/com/smartapi/gateway/repository/ServiceInstanceRepository.java):

```java
@Query("select i from ServiceInstance i join fetch i.service order by i.service.name, i.id")
List<ServiceInstance> findAllWithService();
```

`join fetch` loads instances *and* their services in one SQL `JOIN`.

**Failure cases.**
- `LazyInitializationException`: touching a lazy field after the transaction closed. It shows up
  immediately *because* open-in-view is off, which is what we want: a loud failure beats a silent
  extra query.
- Entity/table mismatch: startup fails with `SchemaManagementException`. Fix the migration or entity.

---

## 5. Database design and Flyway migrations

The full design, ER diagram and reasoning are in [`database/README.md`](../database/README.md). Key concepts:

**Primary key.** Uniquely identifies a row; never null, never changes. We use surrogate `BIGINT`
identity columns rather than natural keys like `name`, because names can change and FKs would break.

**Foreign key.** `service_instances.service_id REFERENCES services(id)`. The database refuses an
instance pointing to a non-existent service, regardless of application bugs. `ON DELETE CASCADE`
deletes a service's instances with it. The integration test `deletingServiceCascadesToInstances`
proves it.

**Relationships.** services 1 : N service_instances. users / services / instances 1 : N
request_logs (optional, hence nullable FKs).

**Indexes.** An index is a sorted structure, a **B-tree**, that turns a full table scan (O(n)) into a
lookup (O(log n)). The cost: extra storage and slower writes, because every INSERT updates every
index. So we add indexes for real queries only. A composite index `(a, b, c)` can serve queries on `a`,
`(a, b)` and `(a, b, c)` but **not** `b` alone: the leftmost-prefix rule. That's why
`uq_instance_endpoint (service_id, host, port)` also serves "instances of service X".

**Normalisation.** 1NF: atomic columns, no lists in a cell. 2NF: every non-key column depends on the
whole key. 3NF: no column depends on another non-key column. Example of a violation: storing
`service_name` in `service_instances`. Renaming a service would then require updating every instance
row, and you could end up with inconsistent names. We store `service_id` and join instead.

**Flyway.** The schema is code:
[`V1__initial_schema.sql`](../backend/src/main/resources/db/migration/V1__initial_schema.sql).
On startup Flyway checks the `flyway_schema_history` table and applies any migrations not yet applied,
in version order. Rules: **never edit an applied migration** (Flyway checksums it and will refuse to
start); add `V2__...` instead. This is how every environment (your laptop, CI, Docker, production)
ends up with an identical schema.

**Failure cases.**
- Editing V1 after it ran → `Validate failed: Migration checksum mismatch`. Fix: revert the edit and
  write V2 (or, only on a throwaway local DB, drop the database).
- Two developers both create `V2__...` → conflict. Teams often use timestamps as versions.

---

## 6. REST API design and status codes

| Endpoint | Method | Success | Errors |
|---|---|---|---|
| `/api/services` | POST | **201** + `Location: /api/services/{id}` | 400 validation, 409 duplicate |
| `/api/services` | GET | 200, list sorted by name | |
| `/api/services/{id}` | GET | 200 | 404, 400 if id is not a number |
| `/api/services/{id}` | DELETE | **204** no body | 404 |
| `/api/service-instances` | POST | 201 + `Location` | 400 validation or unknown `serviceId`, 409 duplicate `host:port` |
| `/api/service-instances[?serviceId=]` | GET | 200 | |
| `/api/service-instances/{id}` | GET / DELETE | 200 / 204 | 404 |
| `/api/health` | GET | 200 `{"status":"UP",...}` | **503** when the database is down |

**Why these codes matter.** Clients and infrastructure act on status codes, not message text:

- **4xx** = *the client* did something wrong; retrying the same request won't help.
- **5xx** = *the server* failed; a retry might succeed.
- **503** specifically = temporarily unavailable. In Phase 3 our retry engine will retry 503 but
  not 400. Returning the wrong class of code breaks every client's retry logic.
- **404 vs 400 for an unknown `serviceId` in a POST body.** The URL `/api/service-instances`
  exists, so 404 would be misleading. The body references something invalid, so we return 400.

**Idempotency** (important in Phase 3 for retries): GET, PUT and DELETE are idempotent, meaning doing
them twice has the same effect as once. POST is not; two POSTs create two resources (or, here, a 409
on the second).

**Error format: RFC 9457 Problem Details.** Every error has the same shape:

```json
{"type":"about:blank","title":"Validation failed","status":400,
 "detail":"One or more fields are invalid","instance":"/api/services",
 "errors":{"name":"must be lowercase letters, digits and hyphens, e.g. orders-service"}}
```

---

## 7. Validation and centralised exception handling

**Validation.** Annotations on DTOs (`@NotBlank`, `@Pattern`, `@Min`, `@Max`) plus `@Valid` in the
controller. Spring validates **before** your method runs. Invalid input never reaches the service.

The `host` field is restricted to a hostname or IPv4 address pattern. That isn't just tidiness:
from Phase 2 the gateway *makes HTTP calls to whatever host is registered*. Unrestricted registration
would let someone point the gateway at internal addresses (SSRF). Phase 4 adds authentication so only
admins can register instances.

**Centralised handling.**
[`GlobalExceptionHandler`](../backend/src/main/java/com/smartapi/gateway/exception/GlobalExceptionHandler.java)
is a `@RestControllerAdvice`: one place that turns exceptions into HTTP responses. Services throw
meaningful exceptions (`ResourceNotFoundException`, `DuplicateResourceException`,
`InvalidRequestException`) and know nothing about HTTP.

| Exception | Status | Why |
|---|---|---|
| `ResourceNotFoundException` | 404 | URL points at nothing |
| `InvalidRequestException` | 400 | body references something invalid |
| `DuplicateResourceException` | 409 | uniqueness rule |
| `DataIntegrityViolationException` | 409 | DB constraint caught a race the service check missed |
| `CannotCreateTransactionException`, `DataAccessResourceFailureException` | 503 | database unreachable |
| `MethodArgumentNotValidException` | 400 + `errors` map | `@Valid` failure |
| anything else | 500, generic message | log full stack trace server-side only |

**Why extend `ResponseEntityExceptionHandler`?** Spring MVC throws its own exceptions for malformed
JSON, unknown URLs, wrong HTTP methods and `/api/services/abc`. The base class maps them to the
correct 4xx. Without it, our catch-all `Exception` handler would turn *all* of them into 500. Two
tests guard this: `nonNumericIdReturns400Not500` and `unknownUrlReturns404NotAnError500`.

**The check-then-act race condition.** `register()` does `existsByName` and then `save`. Two requests
arriving at the same moment can both see "doesn't exist" and both insert. The `UNIQUE` constraint
makes the second insert fail, and `DataIntegrityViolationException` becomes a 409. **Application
checks give nice messages; database constraints give correctness.**

**Never leak internals.** The 409 for a constraint violation says "conflicts with existing data", not
the SQL error, which would reveal table and constraint names. 500s never include stack traces
(`server.error.include-stacktrace: never`).

---

## 8. Testing strategy

```
        /  E2E / manual (curl, browser)   \      few, slow, most realistic
       /  Integration: real PostgreSQL     \     RegistryApiIntegrationTest (7)
      /  Web slice: MockMvc, service mocked \    ServiceControllerTest (7)
     /  Unit: Mockito, no Spring             \   *ServiceTest (8)
```

- **Unit tests** (`ServiceRegistryServiceTest`, `ServiceInstanceServiceTest`): no Spring, no DB,
  run in milliseconds. They test business rules, e.g. that "duplicate name throws **and never
  calls save**" (`verify(repository, never()).save(any())`).
- **Web slice** (`@WebMvcTest`): starts only the MVC layer. It tests HTTP concerns: status codes,
  headers, JSON shape, validation, error mapping. `@MockitoBean` replaces the service.
- **Integration** (`@SpringBootTest` + embedded PostgreSQL 17): the whole app against a *real*
  PostgreSQL started from real binaries by the `embedded-postgres` library. Flyway applies the
  real migration, so the tests prove the SQL, constraints, cascades and `JOIN FETCH` queries work.
  Why not H2 (an in-memory DB)? H2 is not PostgreSQL. Its SQL dialect, constraint behaviour and
  types differ, so tests can pass on H2 and fail in production.
- The database is truncated before each integration test, so tests don't depend on each other's
  data or run order.

Phase 4 moves integration tests to **Testcontainers**, which runs Postgres and Redis in Docker, once
Docker is installed.

**Run them:** `cd backend && ./mvnw verify` → **22 tests, 0 failures** at the end of Phase 1.

---

## 9. The React dashboard

**Structure** (`frontend/src`):

| File | Role |
|---|---|
| `api/types.ts` | TypeScript interfaces mirroring backend DTOs |
| `api/client.ts` | `fetch` wrapper; converts problem+json into an `ApiError` with `fieldErrors` |
| `App.tsx` | Holds state, loads data, polls every 5 s |
| `components/*` | Tables, forms, status badges, summary cards |

**Key React ideas used.**

- **State** (`useState`): data that, when changed, re-renders the UI.
- **Effects** (`useEffect`): side effects after render. Here: start polling, and **return a cleanup
  function** that clears the interval. Forgetting cleanup leaks timers that keep firing after the
  component unmounts.
- **Why polling?** Instance status will change on its own in Phase 3 (health checks). Polling every
  5 s is simple and good enough for a dashboard. Alternatives: Server-Sent Events or WebSockets for
  push updates. More efficient, more complex.
- **Controlled inputs**: form values live in React state (`value={name} onChange=...`).

**The dev proxy.** The browser only talks to `localhost:5173` (Vite). Vite forwards `/api/*` to
`localhost:8080`. From the browser's view it's the same origin, so no **CORS** configuration is
needed. CORS is a browser rule that blocks JavaScript from reading responses from a *different* origin
unless that server opts in. We configure CORS properly in Phase 4.

**Why TypeScript.** If the backend renames `basePath`, `types.ts` changes and the compiler flags
every place that used the old name, instead of the UI silently showing `undefined`.

---

## 10. Bugs found while building Phase 1

These are real issues found during development. They make good interview stories ("tell me about a bug
you found").

1. **Database outage returned 500 instead of 503.** A manual failure test stopped PostgreSQL while
   the app was running. `/api/health` correctly returned 503, but `/api/services` returned 500 because
   `CannotCreateTransactionException` fell through to the catch-all handler. Fixed by mapping
   connection failures to 503, with regression test `databaseOutageReturns503Not500`. The distinction
   matters because retry logic (Phase 3) treats 503 as retryable and 500 as not. After Postgres
   restarted, the app recovered by itself (HikariCP reconnects), with no restart needed.
2. **Health check could hang for 30 seconds.** HikariCP's default `connection-timeout` is 30 s, so
   during an outage every request (including `/api/health`) blocked a thread for 30 s. Set to 3 s:
   measured `/api/health` response during the outage was 3.0 s with HTTP 503.

---

## 11. Known limitations (honest review)

A strict reviewer would raise these. Some are fixed in later phases; they are listed so you can
discuss them in an interview.

| Issue | Why it matters | Plan |
|---|---|---|
| No authentication: anyone can register instances | Registered hosts will receive gateway traffic (SSRF risk) | Phase 4: JWT + ADMIN role |
| List endpoints aren't paginated | Fine for tens of services; `request_logs` will need it | Phase 3: paginate logs |
| Frontend types are hand-copied from DTOs | Can drift from the backend | Could generate from OpenAPI |
| Instance `status` is always `UNKNOWN` | Nothing probes instances yet | Phase 3: health checker |
| No update (PUT/PATCH) endpoints | You must delete and re-create to change weight | Add when needed |
| Dashboard polls every 5 s | Wasteful with many clients | Acceptable at this scale; SSE is the upgrade |
| Integration tests use embedded Postgres, not Testcontainers | Docker isn't installed yet on the dev machine | Phase 4 |

---

## 12. Interview questions

**Spring / Java**

1. *What is dependency injection, and why prefer constructor injection over field injection?*
   Testing: understanding of IoC, testability, immutability. Follow-up: what happens with a circular
   dependency under constructor injection?
2. *What is the default scope of a Spring bean, and what does that imply for thread safety?*
   Singleton; one instance handles concurrent requests, so no mutable per-request fields.
3. *What does `@Transactional` do? What happens if you call a `@Transactional` method from another
   method in the same class?* Spring's proxy is bypassed on self-invocation, so no transaction.
   A classic trap.
4. *Why do we have DTOs if we already have entities?* Over-posting, API stability, lazy loading,
   different shapes.
5. *`@RestControllerAdvice`: how does Spring decide which `@ExceptionHandler` handles an exception?*
   The most specific exception type in the hierarchy wins.

**Databases / JPA**

6. *What is the N+1 query problem? How did you avoid it?* `JOIN FETCH` in `ServiceInstanceRepository`.
7. *Why `ddl-auto: validate` with Flyway instead of `update`?*
8. *Explain the leftmost-prefix rule for composite indexes, using `(service_id, host, port)`.*
9. *Why `ON DELETE CASCADE` for instances but `SET NULL` for request logs?*
10. *Two requests register the same service name at the same moment. What happens in your system?*
    Both may pass `existsByName`; the UNIQUE constraint rejects one; the handler returns 409.
11. *Why store enums as strings?*
12. *IDENTITY vs SEQUENCE id generation in Hibernate: why does it matter for `request_logs`?*
    IDENTITY disables JDBC insert batching.

**REST / networking**

13. *400 vs 404 vs 409 vs 422 vs 500 vs 503: when did you use each?*
14. *Which HTTP methods are idempotent, and why will that matter for retries?*
15. *What is CORS and why didn't the Phase 1 dashboard need it?*

**Testing**

16. *Unit vs slice vs integration test: what does each one in this project prove?*
17. *Why not H2 for integration tests?*

**Design**

18. *Why is the service registry stored in PostgreSQL and not just in memory?* It survives
    restarts and is shared by multiple gateway instances. Follow-up: the gateway will read it on
    *every* request in Phase 2, so how do you avoid a DB query per request? Caching, coming in Phase 2/3.
