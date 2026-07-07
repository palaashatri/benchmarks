# Implementation Notes — Benchmark 07: Coldstart Suite

## What was implemented

### app/src/main/java/.../MiniHttpServer.java (replaced)

The stub server was replaced with a startup-measurement server that:

- Tracks JVM start time via `ManagementFactory.getRuntimeMXBean().getStartTime()` (epoch ms).
- Records `startedAtNano` at constructor time and `firstRequestNano` on the very first request
  using an `AtomicLong` CAS so it is thread-safe and captured exactly once.
- Maintains a 60-slot circular buffer (`AtomicLong[] perSecondCounts` + `long[] secondTimestamps`)
  for per-second throughput tracking used by `/api/v1/warmup`.
- Uses `Executors.newVirtualThreadPerTaskExecutor()` for the HTTP server executor.
- Routes:
  - `GET /health`, `GET /actuator/health` — uptime, startup timing, JIT compilation ms, JVM uptime.
  - `GET /metrics`, `GET /actuator/prometheus` — Prometheus text with `jvm_startup_ms`,
    `jvm_compilation_ms_total`, `benchmark_requests_total`, `jvm_loaded_classes`,
    `jvm_available_processors`.
  - `GET /api/v1/jfr/stats` — JSON with CompilationMXBean and ClassLoadingMXBean data.
  - `GET /api/v1/coldstart/measure` — spawns a child JVM via `ProcessBuilder` on port 18070,
    polls `http://localhost:18070/health` every 100ms for up to 10s, measures time-to-first-response,
    then `destroyForcibly()` in `finally` block. Returns `{"time_to_first_response_ms":-1,"error":"timeout"}`
    on timeout, or `{"time_to_first_response_ms":N}` on success.
  - `GET /api/v1/warmup` — returns last 60 seconds of per-second RPS from the circular buffer.
  - Catch-all `/` — increments counter, returns 404 JSON.

### harness/src/main/java/.../BenchmarkHarness.java (replaced)

Key changes from the stub:

- Weighted `REQUESTS` array: GET /health x4, GET /api/v1/jfr/stats x3, GET /metrics x2,
  GET /api/v1/coldstart/measure x1.
- Concurrent execution via `Executors.newFixedThreadPool(threads)` + `CountDownLatch`.
- `--threads` (default 8) and `--runs` (default 3) CLI args.
- Wall-clock throughput: `requestCount / wallElapsedSeconds` (not sum of latencies).
- Circuit breaker for `/api/v1/coldstart/measure`: opens for 30 seconds on failure,
  substitutes with `GET /health` while open.
- Extended timeout (15s) for coldstart endpoint vs 5s for others.
- Runtime metric collection:
  - GC pause ms via `ManagementFactory.getGarbageCollectorMXBeans()`.
  - RSS via `ps -o rss= -p <pid>` (KB -> MB), graceful on failure.
  - CPU via `com.sun.management.OperatingSystemMXBean.getProcessCpuLoad()`.
- `mode_kpis`: `time_to_first_response_ms`, `time_to_90pct_s`, `compiled_methods`.
- `Result.toJson()` produces valid JSON matching the suite schema.

### harness/docker-compose.yml (updated)

Added Grafana provisioning volume mounts so datasources and dashboards are auto-wired:
```
./grafana/provisioning:/etc/grafana/provisioning:ro
./grafana/dashboards:/var/lib/grafana/dashboards:ro
```

### harness/grafana/provisioning/datasources/prometheus.yaml (created)

Auto-provisions Prometheus datasource at `http://prometheus:9090` as default.

### harness/grafana/provisioning/dashboards/dashboards.yaml (created)

Auto-provisions dashboards from `/var/lib/grafana/dashboards`.

### harness/grafana/dashboards/dashboard.json (updated)

Real Grafana 10 dashboard (uid `07-coldstart`, title `07 Coldstart Suite`) with 4 panels:
1. JVM Startup Time — `jvm_startup_ms` timeseries.
2. JIT Compilation Rate — `rate(jvm_compilation_ms_total[1m])` timeseries.
3. Request Throughput — `rate(benchmark_requests_total[1m])` timeseries.
4. Loaded Classes — `jvm_loaded_classes` gauge panel with thresholds.

## External dependencies added

None. Uses only JDK 21 built-ins:
- `java.lang.management.ManagementFactory`, `CompilationMXBean`, `ClassLoadingMXBean`
- `com.sun.net.httpserver.HttpServer`
- `java.net.http.HttpClient` (Java 11+)
- `java.lang.ProcessBuilder`, `ProcessHandle`
- `java.util.concurrent.*`

## Smoke test result

PASS. Running `REQUESTS=20 ./run.sh test` from the harness directory produces:

```json
{"benchmark":"07-coldstart-suite","runtime":"openjdk-hotspot-21","gc":"G1",
 "jvm_flags":["-XX:+UseG1GC"],"env":{"cpu":"12","kernel":"26.5.1",
 "cgroup_cpu":"unknown","cgroup_mem":"unknown"},"load_profile":"cold",
 "phases":{"warmup_s":0,"measure_s":0},
 "kpis":{"throughput":61.456,"p50_ms":2,"p99_ms":271,"p999_ms":271,"p9999_ms":271,
 "gc_pause_p99_ms":0,"alloc_rate_mb_s":0,"rss_mb":108,"native_mem_mb":0,"cpu_util_pct":0.00},
 "mode_kpis":{"time_to_first_response_ms":0,"time_to_90pct_s":0,"compiled_methods":245}}
```

## Deviations from spec

- `MiniHttpServer` constructor was updated from `(String benchmark, String title)` to
  `(String benchmark)` — the `title` parameter was unused by any route logic, and the
  BenchmarkApp.java callsites were updated accordingly.
- `HttpTimeoutException` is a subclass of `IOException`, so the multi-catch was collapsed
  to a single `IOException` catch to satisfy `javac --release 21`.
- The `/api/v1/coldstart/measure` endpoint always returns HTTP 200 (even on timeout/error),
  consistent with the circuit breaker not needing to trip on schema-valid error responses.
