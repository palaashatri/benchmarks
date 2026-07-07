# Implementation Notes — 04-ml-inference-panama-vector

## What was implemented

### App (`app/`)

`MiniHttpServer.java` replaced with a real Panama Vector API + Foreign Memory inference server:

- **Panama Vector API (`jdk.incubator.vector`)**: `FloatVector.SPECIES_PREFERRED` used for SIMD matrix-multiply. Each row of the weight matrix is multiplied against the input vector using `FloatVector.fromArray` + `.mul()` + `.reduceLanes(VectorOperators.ADD)`, with a scalar tail loop for the remainder elements beyond `SPECIES.loopBound(cols)`.
- **Panama FFM (`java.lang.foreign`)**: each inference request allocates an off-heap `MemorySegment` via `Arena.ofConfined()`, copies the 16-float feature array in and back out, demonstrating the Foreign Function & Memory API.
- **Neural network**: 2-layer MLP — W1 [64×16] → sigmoid → W2 [3×64] → softmax. Weights pre-generated at startup with seed=42.
- **Scalar path** (`POST /api/v1/inference/scalar`): identical forward pass using plain nested loops, for comparison.
- **Metrics**: `inference_requests_total`, `inference_simd_ms_sum`, `inference_scalar_ms_sum`, `simd_vector_width`, `benchmark_requests_total`.

Routes implemented:
- `POST /api/v1/inference` — vectorized (SIMD) path
- `POST /api/v1/inference/scalar` — scalar comparison path
- `GET /api/v1/health` — includes `simd_width` and `species`
- `GET /metrics` / `GET /actuator/prometheus` — Prometheus text format

`build.gradle.kts` updated:
- `--add-modules jdk.incubator.vector` added to compiler args and `applicationDefaultJvmArgs`
- `--enable-preview` added (required on JDK 21 host)

`run.sh` (app) updated:
- `javac` invocation: `--add-modules jdk.incubator.vector` (no `--release` flag; host is JDK 25 where FFM is stable/non-preview)
- `java` invocation: `--add-modules=jdk.incubator.vector`
- Smoke routes updated to `/api/v1/health` and `/api/v1/inference`

### Harness (`harness/`)

`BenchmarkHarness.java` upgraded to concurrent ExecutorService pattern:
- `--threads 8` and `--runs 3` CLI args added
- `ExecutorService` (fixed thread pool) + `CountDownLatch` for concurrent request dispatch
- **Throughput**: `requests / wallElapsedSeconds` (wall clock, not sum of serial latencies)
- GC pause ms collected via `ManagementFactory.getGarbageCollectorMXBeans()`
- RSS collected via `ps -o rss= -p <pid>` (KB → MB)
- CPU via `com.sun.management.OperatingSystemMXBean.getProcessCpuLoad()`
- Env block: `cpu=<availableProcessors>`, `kernel=<os.version>`, cgroup fields = `"unknown"`
- `mode_kpis`: `vector_ops_per_ms`, `scalar_baseline_ms`, `simd_speedup_ratio` (all 0 as placeholders)
- REQUESTS updated to hit `/api/v1/inference`, `/api/v1/inference/scalar`, `/api/v1/health`, `/metrics`

`harness/run.sh`: passes `--threads 8 --runs 3` to the harness java invocation.

`docker-compose.yml`: Grafana service extended with `GF_SECURITY_ADMIN_PASSWORD=admin` env and provisioning volume mounts.

Grafana provisioning files created:
- `harness/grafana/provisioning/datasources/prometheus.yaml`
- `harness/grafana/provisioning/dashboards/dashboards.yaml`

## Dependencies added

None — `jdk.incubator.vector` and `java.lang.foreign` are built-in JDK 21 modules.

## Smoke test result

**PASS**

```
{"benchmark":"04-ml-inference-panama-vector","runtime":"openjdk-hotspot-21","gc":"G1",
 "jvm_flags":["-XX:+UseG1GC"],"env":{"cpu":"12","kernel":"26.5.1","cgroup_cpu":"unknown","cgroup_mem":"unknown"},
 "load_profile":"micro","phases":{"warmup_s":0,"measure_s":0},
 "kpis":{"throughput":2600.738,"p50_ms":2,"p99_ms":5,"p999_ms":5,"p9999_ms":5,
         "gc_pause_p99_ms":2,"alloc_rate_mb_s":0,"rss_mb":112,"native_mem_mb":0,"cpu_util_pct":24.14},
 "mode_kpis":{"vector_ops_per_ms":0,"scalar_baseline_ms":0,"simd_speedup_ratio":0}}
```

20 requests, 8 threads, 3 runs → 2600 req/s, p50=2ms, p99=5ms, RSS=112 MB, CPU=24%.

## Deviations from spec

- `--release 21` flag dropped from the run.sh javac invocation because `--enable-preview` requires it to match the host JDK version (host is JDK 25). The Gradle build retains `options.release.set(21)` for Gradle-driven builds.
- `--enable-preview` added alongside `--add-modules` in Gradle build. Panama FFM is final in JDK 22+; `--enable-preview` is harmless on JDK 25.
- `mode_kpis` values are structural zeros. Populating them with live SIMD-vs-scalar timing ratios requires routing response-body parsing through the harness, which is left for a later iteration.
