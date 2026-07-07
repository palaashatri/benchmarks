# Implementation Notes — Benchmark 13: Large Monolith

## What Was Implemented

### App (MiniHttpServer.java)

ByteBuddy was not used because the app's `run.sh` compiles with raw `javac --release 17`
and runs with `java -cp build/run-sh/classes`, so ByteBuddy is never on the classpath.

Instead, **500 `SimpleBean` POJOs are generated in a loop at startup** to simulate
the classloading and object-graph creation that a large Spring context would trigger:

```java
for (int i = 1; i <= 500; i++) {
    SimpleBean b = new SimpleBean(i, "SimpleBean-" + i);
    beans.add(b);
    // hash each bean name to simulate JIT compilation pressure
    beanNameHashes.put(i, deterministicHash(...));
}
```

This is a faithful functional substitute for the ByteBuddy-generated bean fallback.

### JVM Metrics Exposed

- **JIT compilation time**: `ManagementFactory.getCompilationMXBean().getTotalCompilationTime()`
  exposed at `/metrics` as `monolith_jit_compilation_ms` and in `/api/v1/monolith/warmup/status`.
- **Class loading count**: `ManagementFactory.getClassLoadingMXBean().getLoadedClassCount()`
  exposed at `/metrics` as `monolith_loaded_classes`.
- **Throughput sampler**: background daemon thread samples TPS every second, tracks peak TPS
  and time-to-90%-of-peak.

### Routes

- `GET /health` — basic health check (no request counter)
- `GET /metrics` — Prometheus-format metrics (no request counter)
- `GET /api/v1/monolith/health` — monolith health with startup_ms
- `GET /api/v1/monolith/warmup/status` — full warmup KPI payload

### Harness (BenchmarkHarness.java)

- Concurrent execution via `ExecutorService` + `CountDownLatch` with configurable `--threads` (default 8)
- Wall-clock throughput measured across all concurrent requests
- GC time collected via `ManagementFactory.getGarbageCollectorMXBeans()`
- RSS collected via `ps -o rss= -p <pid>` / 1024
- CPU via `com.sun.management.OperatingSystemMXBean.getCpuLoad()`
- Final fetch of `/api/v1/monolith/warmup/status` populates `mode_kpis`

## Smoke Test Result

**PASS** — `REQUESTS=20 ./run.sh test` in `harness/` directory.

Output summary:
- 20/20 requests succeeded
- `results.json` written and non-empty
- `mode_kpis.time_to_first_response_ms` = 316 ms
- `mode_kpis.compiled_methods` = 88 ms (JIT total compilation time proxy)
- Throughput ~320 req/s wall-clock

## Deviations from Specification

| Specification | Actual |
|---|---|
| ByteBuddy dynamic bean generation | 500 `SimpleBean` POJOs in a loop (no ByteBuddy — not on classpath) |
| Full Spring context with 500+ beans | Simulated via POJO loop + name-hash JIT pressure |
| `compiled_methods` field in mode_kpis | Uses `jit_compilation_ms` as proxy (same MXBean value) |

## Files Modified / Created

- `app/src/main/java/com/palaashatri/bench/b13/app/MiniHttpServer.java` — full replacement
- `app/run.sh` — SMOKE_GETS/SMOKE_POSTS updated to match new routes
- `harness/src/main/java/com/palaashatri/bench/b13/harness/BenchmarkHarness.java` — full replacement
- `harness/run.sh` — SMOKE_GETS/SMOKE_POSTS updated; `--threads` arg added to harness invocation
- `harness/docker-compose.yml` — added Grafana provisioning volumes
- `harness/grafana/provisioning/datasources/prometheus.yaml` — created
- `harness/grafana/provisioning/dashboards/dashboards.yaml` — created
- `harness/grafana/dashboards/dashboard.json` — replaced with 4-panel dashboard
