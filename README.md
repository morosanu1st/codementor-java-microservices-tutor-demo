# Java Microservices Tutor Demo

A small, runnable two-service system I use when teaching microservices to students who already know some Java, C or C++. No framework magic: plain JDK 17+ (`HttpServer` + `HttpClient`), so every concept is visible in about 200 lines before we move on to Spring Boot.

- **inventory-service** (`:8081`) owns stock data and reserves items atomically.
- **order-service** (`:8082`) takes orders and calls inventory over HTTP with timeouts, retry with backoff, a small circuit breaker, and a correlation id passed downstream.

## Run it

```bash
./scripts/demo.sh            # needs JDK 17+ and curl
# or
docker compose up --build
```

The script walks through a successful order, an out-of-stock 409 (a business error, not an outage), and then kills inventory-service so you can watch retries fail and the circuit breaker open:

```
{"orderId":"…","sku":"BOOK-1","qty":2,"correlationId":"…"}
{"sku":"BOOK-1","available":8}
{"error":"insufficient stock"}
{"error":"inventory call failed: ConnectException", …}   x3
{"error":"inventory unavailable (circuit open)", …}
{"status":"UP","breaker":"OPEN"}
```

## How I'd teach it (part-time course outline)

| Week | Topic | Hands-on in this repo |
|---|---|---|
| 1 | Monolith vs. microservices, bounded contexts, owning your data | Split stock and orders into two services |
| 2 | REST contracts, status codes, idempotency | 201 vs. 409 vs. 503 in `OrderService` |
| 3 | Resilience: timeouts, retries, backoff, circuit breakers | `reserveWithRetry` and `CircuitBreaker` |
| 4 | Observability: correlation ids, structured logs, health checks | `X-Correlation-Id`, `/health` |
| 5 | Containers and Compose, config through env vars | `Dockerfile`, `docker-compose.yml` |
| 6 | Moving to Spring Boot: Spring Web, Resilience4j, Actuator | Rewrite one service and compare |
| 7 | Async messaging (Kafka/RabbitMQ), sagas, eventual consistency | Replace the sync reserve call with events |
| 8 | API gateway, service discovery, deploying to Kubernetes | Capstone: students extend the system |

Each session is live coding plus a short exercise; students push their own branch and I review it before the next class. For learners coming from C/C++, I map concepts like threads, sockets and memory ownership onto the JVM equivalents as we go.

## Exercises

1. Add a `payment-service` and make order creation call it after inventory.
2. Make reservations idempotent with an `Idempotency-Key` header.
3. Add a compensating "release stock" call when payment fails (saga pattern).
4. Replace the hand-written breaker with Resilience4j and compare behavior.

---
Mihai Chindriș, software engineer and Codementor mentor (Java, distributed systems, cloud).
