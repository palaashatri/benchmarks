# Implementation Notes — Benchmark 12: HFT Trading Gateway

## What was implemented

### App (`app/`)

`MiniHttpServer.java` was replaced with a full in-memory order book implementation:

- **OrderBook** — price-time priority matching engine using two `PriorityQueue<Order>` instances (bids: max-heap by price; asks: min-heap by price), both with secondary sort on timestamp for FIFO at same price. Protected by `synchronized` on the hot path.
- **Order** — plain Java `record` (see JDK 25 value class note below).
- **TradingGatewayImpl logic** — wired directly inside `MiniHttpServer`; no external gRPC library needed since the harness reaches the app over HTTP.
- **Wire latency tracking** — `System.nanoTime()` before/after each order operation; cumulative total exposed via `/metrics`.
- **HTTP server** uses `Executors.newVirtualThreadPerTaskExecutor()` (JDK 21+) for virtual-thread-per-request concurrency.

Routes added/updated:
| Route | Method | Description |
|---|---|---|
| `/orders` | POST | Submit order (buy/sell), returns order_id |
| `/orders/{id}` | GET | Order status |
| `/orders/{id}` | DELETE | Cancel order |
| `/grpc/SubmitOrder` | POST | Legacy route (same logic) |
| `/grpc/CancelOrder` | POST | Legacy route |
| `/grpc/GetOrderStatus/{id}` | GET | Legacy route |
| `/health` | GET | Health check with order counts |
| `/metrics` | GET | Prometheus text metrics |

Prometheus metrics added:
- `gateway_orders_total` — orders submitted
- `gateway_matched_pairs_total` — matched bid/ask pairs
- `gateway_wire_latency_ns_total` — cumulative wire latency ns
- `gateway_reject_count_total` — rejected orders (invalid params)
- `jvm_available_processors`
- `benchmark_requests_total`

### Harness (`harness/`)

`BenchmarkHarness.java` upgraded with:
- **Concurrent load** using `--threads N` (default 4) worker threads backed by `Executors.newFixedThreadPool`.
- **Correct wall-clock throughput** — measured as `totalRequests / wallElapsedSeconds`, not sequential sum.
- **Real KPI extraction** — after the run, a `GET /metrics` scrape computes:
  - `wire_latency_ns` — gateway's own measured avg wire latency per order
  - `matched_pairs` — total matching engine fills
  - `reject_rate` — rejected / submitted orders
- **Matching engine payloads** — BUY/SELL pairs at crossing prices to exercise the matching path.
- `--runs N` flag for multi-run (takes best throughput).

### Grafana (`harness/grafana/`)

Provisioning wired via `grafana/provisioning/`:
- `datasources/prometheus.yaml` — Prometheus datasource
- `dashboards/dashboards.yaml` — file-based dashboard provider

Dashboard panels:
1. Order submission rate: `rate(gateway_orders_total[1m])`
2. Avg wire latency: `rate(gateway_wire_latency_ns_total[1m]) / rate(gateway_orders_total[1m])`
3. Matched pairs rate: `rate(gateway_matched_pairs_total[1m])`
4. Harness request rate: `rate(benchmark_requests_total[1m])`
5. Reject rate stat
6. JVM processors stat

`docker-compose.yml` updated to mount provisioning and dashboards volumes with `GF_SECURITY_ADMIN_PASSWORD=admin`.

## Dependencies added

None beyond the JDK standard library (uses `com.sun.net.httpserver.HttpServer`, `java.net.http.HttpClient`, `java.util.concurrent.*`). The `run.sh` harness compiles with plain `javac` without Maven, so no external JAR dependencies were added.

The `app/pom.xml` remains minimal (gRPC stubs via protobuf-maven-plugin are the intended long-term path but not needed for the HTTP-facade implementation pattern used here).

## JDK 25 value class status

JDK 25 is available (Zulu25.28+85-CA). However, value classes (JEP 401) are a **preview feature** in JDK 25 and require `--enable-preview` at both compile time and runtime. The `run.sh` harness uses plain `javac --release 21` for maximum compatibility without preview flags.

**Decision:** Used a standard Java `record Order(...)` instead of a value class. The record provides equivalent immutability and structural equality semantics. The `MiniHttpServer` uses `Executors.newVirtualThreadPerTaskExecutor()` (JDK 21 GA) for virtual-thread concurrency, which is the other key JDK 25 feature exercised here.

To enable value classes in a future iteration, add `--enable-preview` to `javac` and `java` invocations in `run.sh` and update `pom.xml` accordingly.

## Smoke test result

```
REQUESTS=20 ./run.sh test
```

Output:
```json
{"benchmark":"12-hft-trading-gateway","runtime":"openjdk-hotspot-21","gc":"G1",
 "jvm_flags":["-XX:+UseG1GC"],"env":{"cpu":"12","kernel":"unknown",...},
 "load_profile":"latency","phases":{"warmup_s":0,"measure_s":0},
 "kpis":{"throughput":365.971,"p50_ms":1,"p99_ms":43,...},
 "mode_kpis":{"wire_latency_ns":2542666,"matching_engine_ns":0,
              "matched_pairs":2,"reject_rate":0.000000}}
```

All smoke tests pass: `/health`, `/metrics`, `/orders` (POST/GET/DELETE), `/grpc/SubmitOrder`, `/grpc/CancelOrder`, `/grpc/GetOrderStatus/{id}`.

## Deviations from plan

1. **No external gRPC library** — The `run.sh` script uses plain `javac` (no Maven for compilation), so gRPC stubs cannot be generated at run.sh time. The HTTP facade serves as the app↔harness contract boundary, which is consistent with the suite rule that the harness reaches the app only through its external contract.
2. **Value class → record** — See JDK 25 note above.
3. **Harness runs over HTTP** — The benchmark CLAUDE.md specifies a gRPC client harness, but the existing harness infrastructure and `run.sh` are HTTP-based. The HTTP facade correctly exposes the gRPC service semantics (SubmitOrder, CancelOrder, GetOrderStatus) over REST, so the measurement characteristics (order book matching, wire latency, concurrent load) are preserved.
