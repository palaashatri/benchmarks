# Benchmark 06: Massive Chat Loom — Implementation Notes

## What was implemented

- **MiniHttpServer.java**: Stateful in-memory chat server with virtual threads.
  Routes: POST/GET /rooms/{id}/messages, GET /rooms/{id}/subscribers, GET /rooms,
  GET /api/v1/stats, GET /health, GET /metrics.
- **BenchmarkHarness.java**: Concurrent load generator using a fixed thread pool +
  CountDownLatch per run. Collects real GC ms, RSS (via ps), CPU (via OperatingSystemMXBean).
  Wall-clock throughput measurement. Emits normalised results.json.
- **app/run.sh**: Updated smoke GET/POST paths to match new routes.
- **harness/run.sh**: Updated smoke paths + added --threads arg passing.
- **docker-compose.yml**: Added Grafana provisioning volumes.
- **Grafana provisioning**: datasources/prometheus.yaml + dashboards/dashboards.yaml.
- **Grafana dashboard**: 4 panels — request rate, active rooms, broadcast rate, JVM thread count.

## Key dependencies

None — pure JDK 21 APIs only (com.sun.net.httpserver, java.util.concurrent, java.lang.management).

## Key JVM feature

Virtual threads via `Executors.newVirtualThreadPerTaskExecutor()` as the HttpServer executor.
Each HTTP request is handled on a separate virtual thread, enabling massive concurrency
with minimal memory overhead compared to platform threads.

Warmup: On startup, a virtual thread seeds 50 rooms with 10 messages each to pre-populate state.

## Smoke test result

pass

## Deviations from spec

None.
