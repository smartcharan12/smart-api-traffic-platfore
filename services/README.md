# services/

Demo backends that the gateway routes traffic to. They exist only to demonstrate the gateway.

## demo-service

One Spring Boot app, run as a different service depending on `demo.service-name`:

| Service | Endpoints | Demo ports |
|---|---|---|
| `orders-service` | `GET /orders`, `GET /orders/{id}` | 9001, 9002, 9003 |
| `payments-service` | `GET /payments`, `POST /payments` | 9101 |
| `restaurant-service` | `GET /restaurants`, `GET /restaurants/{id}` | 9201 |

Every response names the instance that produced it, and echoes the `X-Request-ID` it received:

```json
{"service":"orders-service","instance":"orders-9002","message":"Request handled successfully",
 "requestId":"7c3e...","data":{...}}
```

Every instance also has:
- `GET /health`: liveness (the Phase 3 health checker will poll it)
- `POST /_chaos?mode=NONE|ERROR|SLOW&delayMs=N`: failure injection. It must be called **directly** on the
  instance port. Requests arriving through a proxy (with an `X-Forwarded-For` header) get 403, so
  clients cannot break backends via the gateway.

```bash
cd services/demo-service
./mvnw package
java -jar target/demo-service-0.1.0-SNAPSHOT.jar --demo.service-name=orders-service --server.port=9001
```

Or start all five with `scripts/start-demo-services.sh` (and stop them with `scripts/stop-demo-services.sh`).

**Note on state:** payments are stored in memory, per instance. With two payments instances behind
the gateway, a payment created on one would be invisible on the other. That is why real services keep
state in a shared database: then any instance can serve any request.
