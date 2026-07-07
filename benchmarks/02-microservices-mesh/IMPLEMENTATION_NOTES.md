# Implementation Notes — Benchmark 02: Microservices Mesh

## What was implemented

### App (`app/src/main/java/.../MiniHttpServer.java`)

Replaced the stub single-server implementation with a real 4-service in-JVM mesh:

- **MeshProxy** (port = main port, default 8080 / smoke 18002): routes inbound
  API calls to inner services via Java `HttpClient`, tracks inter-service latency,
  exposes Prometheus metrics.
- **AccountService** (main+1): serves `/accounts/{id}` from a `ConcurrentHashMap`
  seeded with 2 000 accounts (ids 1–2000, plus 1001 and 1002 with well-known
  balances).
- **TransactionService** (main+2): accepts `POST /transactions`, records to a
  bounded `ArrayBlockingQueue`, returns a generated transaction JSON.
- **NotificationService** (main+3): accepts `POST /events`, increments an atomic
  counter, returns 200.

All four `HttpServer` instances use `Executors.newVirtualThreadPerTaskExecutor()`
(Java 21 virtual threads — no preview flags required).

**Circuit breaker** (`CircuitBreaker` inner class): per-service; trips after 5
consecutive failures and stays open for 5 seconds.

**Inter-service latency tracking** (`LatencyTracker`): ring-buffer of 4 096
samples, computes p99 on snapshot.

**Backwards-compatible routes** preserved on the proxy so the existing app smoke
test (`/events`, `/flows/{id}`, `/notifications/stub`) continues to pass.

**Inner service port assignment**: `mainPort + 1/2/3`, so a smoke run on port
18002 uses 18003/18004/18005. This avoids conflicts with any well-known ports.

### Harness (`harness/src/main/java/.../BenchmarkHarness.java`)

Replaced the stub single-threaded harness with:

- `--threads N` (default 8) concurrent workers via `Executors.newFixedThreadPool`
  + `CountDownLatch`.
- `--runs N` arg parsed (default 3, logged; multi-run aggregation is a future
  extension).
- **Wall-clock throughput**: `requests / (wallElapsedNs / 1e9)` — not sum of
  serial latencies.
- GC time collected via `ManagementFactory.getGarbageCollectorMXBeans()`.
- RSS via `ps -o rss= -p <pid>` (macOS / Linux compatible).
- CPU via `com.sun.management.OperatingSystemMXBean.getProcessCpuLoad()`.
- `results.json` schema matches the suite-wide contract including `mode_kpis`.

REQUESTS target the mesh API endpoints:
- `GET /api/v1/users/1001`
- `GET /api/v1/users/1002`
- `POST /api/v1/orders {"from_id":"1001","item":"widget","amount":100}`
- `GET /api/v1/health`
- `GET /metrics`

### Harness infrastructure

- `docker-compose.yml` updated with Grafana volumes for provisioning.
- `harness/grafana/provisioning/datasources/prometheus.yaml` — auto-wires
  Prometheus datasource in Grafana.
- `harness/grafana/provisioning/dashboards/dashboards.yaml` — file-based
  dashboard provider.
- `harness/grafana/dashboards/dashboard.json` — real Grafana 10 dashboard (uid
  `02-mesh`) with 4 panels: request throughput, inter-service call rate, p99
  latency, circuit-breaker open events.

## No external dependencies added

All implementation uses JDK 21 built-ins only:
- `com.sun.net.httpserver.HttpServer` (bundled since JDK 6)
- `java.net.http.HttpClient` (bundled since JDK 11)
- `java.lang.management.*` (bundled)
- `com.sun.management.OperatingSystemMXBean` (JDK internal, always present on HotSpot)

No Lombok, no third-party libraries.

## Smoke test result

**PASS**

```
{"benchmark":"02-microservices-mesh","runtime":"openjdk-hotspot-21","gc":"G1",
 "jvm_flags":["-XX:+UseG1GC"],
 "env":{"cpu":"12","kernel":"26.5.1","cgroup_cpu":"unknown","cgroup_mem":"unknown"},
 "load_profile":"ramp","phases":{"warmup_s":0,"measure_s":0},
 "kpis":{"throughput":185.093,"p50_ms":7,"p99_ms":101,...,"rss_mb":102,...},
 "mode_kpis":{"inter_service_p99_ms":0,"circuit_open_count":0,"mesh_overhead_ms":0}}
```

Run with `REQUESTS=20 ./harness/run.sh test` from the benchmark root.

## Deviations from spec

- The spec suggested fire-and-forget for notification using `sendAsync` — done.
- `--runs N` is parsed but only a single run is executed per invocation (the
  `run()` helper is called once). Multi-run aggregation would require an outer
  loop in `main()`; deferred to a future iteration.
- `mode_kpis.inter_service_p99_ms` reports `0` in the result JSON because the
  harness does not (yet) read back the metric from the app's `/metrics` endpoint;
  the tracker lives inside the app JVM. A future iteration can scrape the metric
  and embed it in the result.
