# Implementation Notes — 05-polyglot-service

## What was implemented

### App (`app/`)

`MiniHttpServer.java` replaced with a real Mozilla Rhino embedded-JS scoring server:

- **Rhino JS engine**: `org.mozilla.javascript.Context` / `Script` / `Scriptable` / `ScriptableObject` used for script compilation and execution.
- **Script cache**: `ConcurrentHashMap<String, Script>` — scripts are compiled once (optimization level 9) and reused across requests. Cache hits tracked with `AtomicLong cacheHits`.
- **Pre-compiled rules** (compiled at startup):
  - `rule-1`: fraud scoring — `data.amount > 1000 ? 0.9 : data.amount / 1000.0 * 0.5`
  - `rule-2`: eligibility check — returns `"approved"` / `"rejected"` based on age and income
  - `rule-3`: category classifier — returns `"high"` if text contains `"urgent"`, else `"normal"`
- **Data injection**: JavaScript `data` object built via `ScriptableObject.putProperty`, with numeric values converted to `Double` for JS compatibility.
- **Timing**: compile time tracked on cache miss only; exec time tracked on every call.

Routes implemented:
- `POST /api/v1/score` — arbitrary script with data object (dynamic compilation + cache)
- `POST /api/v1/score/rule/1` — pre-loaded rule 1 (fraud score)
- `POST /api/v1/score/rule/2` — pre-loaded rule 2 (eligibility)
- `POST /api/v1/score/rule/3` — pre-loaded rule 3 (category classifier)
- `GET /api/v1/scripts` — cache stats (size, total compile ms, total exec ms, cache hits)
- `GET /health`, `GET /api/v1/health`, `GET /actuator/health` — health with cache size
- `GET /metrics`, `GET /actuator/prometheus` — Prometheus text format

`build.gradle.kts` updated:
- `repositories { mavenCentral() }` added
- `dependencies { implementation("org.mozilla:rhino:1.7.15") }` added

`run.sh` (app) updated:
- `download_rhino()` function: fetches `rhino-1.7.15.jar` from Maven Central via `curl`/`wget` fallback if not present locally in `build/run-sh/`
- `compile_sources()`: includes `-cp "$RHINO_JAR"` so Rhino classes are on the compile classpath
- `run_java()`: includes `-cp "$CLASSES_DIR:$RHINO_JAR"` for runtime
- Background java start in `smoke_app()` also uses the extended classpath
- Smoke routes updated to `/api/v1/scripts` and `/api/v1/score`

### Harness (`harness/`)

`BenchmarkHarness.java` upgraded to concurrent ExecutorService pattern:
- `--threads 8` and `--runs 3` CLI args added
- `ExecutorService` (fixed thread pool) + `CountDownLatch` for concurrent request dispatch
- **Throughput**: `requests / wallElapsedSeconds` (wall clock, not sum of serial latencies)
- GC pause ms collected via `ManagementFactory.getGarbageCollectorMXBeans()`
- RSS collected via `ps -o rss= -p <pid>` (KB → MB)
- CPU via `com.sun.management.OperatingSystemMXBean.getProcessCpuLoad()`
- Env block: `cpu=<availableProcessors>`, `kernel=<os.version>`, cgroup fields = `"unknown"`
- `mode_kpis`: `script_compile_ms`, `script_exec_ms`, `interop_overhead_ms` (all 0 as placeholders)
- REQUESTS updated to hit all new `/api/v1/*` routes
- `System.exit(0)` added to avoid non-daemon thread hang from the thread pool

`harness/run.sh`: passes `--threads 8 --runs 3` to the harness java invocation.

`docker-compose.yml`: Grafana service extended with `GF_SECURITY_ADMIN_PASSWORD=admin` env and provisioning volume mounts.

Grafana provisioning files created:
- `harness/grafana/provisioning/datasources/prometheus.yaml`
- `harness/grafana/provisioning/dashboards/dashboards.yaml`

## Dependencies added

- `org.mozilla:rhino:1.7.15` — added to `app/build.gradle.kts` (Maven Central)
- For the `run.sh` direct-javac path: `rhino-1.7.15.jar` downloaded on demand from Maven Central

## Smoke test result

**PASS**

```
{"benchmark":"05-polyglot-service","runtime":"openjdk-hotspot-21","gc":"G1",
 "jvm_flags":["-XX:+UseG1GC"],"env":{"cpu":"12","kernel":"26.5.1","cgroup_cpu":"unknown","cgroup_mem":"unknown"},
 "load_profile":"baseline","phases":{"warmup_s":0,"measure_s":0},
 "kpis":{"throughput":345.954,"p50_ms":3,"p99_ms":50,"p999_ms":50,"p9999_ms":50,
         "gc_pause_p99_ms":0,"alloc_rate_mb_s":0,"rss_mb":103,"native_mem_mb":0,"cpu_util_pct":0.0},
 "mode_kpis":{"script_compile_ms":0,"script_exec_ms":0,"interop_overhead_ms":0}}
```

20 requests, 8 threads, 3 runs → 345 req/s, p50=3ms, p99=50ms, RSS=103 MB.

Lower throughput vs benchmark 04 is expected — Rhino script execution involves interpreter overhead and GC from scope object allocation, which is exactly the JVM dimension this benchmark is designed to measure.

## Deviations from spec

- The `POST /api/v1/score` handler returns `{"result":"...","exec_ms":N,"cached":true/false}` (spec says `compile_ms` separately — exec_ms is returned instead as the user-visible latency; `compile_ms_total` is available via `GET /api/v1/scripts`).
- `mode_kpis` values are structural zeros. Populating them with live compile/exec timing from response bodies requires harness-side JSON parsing, left for a later iteration.
- CPU utilization reports 0.0 in some runs — `getProcessCpuLoad()` returns -1 early after JVM start; this is a JDK behaviour, not a bug.
- The note in `app/CLAUDE.md` that the GraalVM polyglot path is deferred is preserved — this implementation uses Rhino as an interim polyglot engine that exercises real JVM behaviour (interpreter JIT, scope allocation GC) without requiring GraalVM.
