# CLAUDE.md — JVM Benchmark Suite (suite root)

This repository is a suite of **13 real-world JVM benchmark projects** across
two cohorts. Each benchmark stresses a different dimension of JVM behaviour
(startup, warmup, throughput, tail latency, GC, JIT, FFI, concurrency,
polyglot) under operationally realistic conditions, so the numbers are
decision-relevant rather than synthetic scores.

- **Cohort A — benchmarks 01–08:** Gradle / Kotlin DSL, JDK 21 LTS, suite-wide
  conventions apply in full (sections 3–8 below).
- **Cohort B — benchmarks 09–13:** Maven, JDK 17 (09–11, 13) or JDK 25 (12),
  load tools vary per benchmark (Gatling, k6, custom Java generator). The
  app↔harness contract rules (sections 0, 4) and observability requirements
  (section 5) still apply; build-tool and JDK conventions in section 3 are
  superseded by each benchmark's own CLAUDE.md.

> **Read this file first, then the `CLAUDE.md` inside the specific benchmark
> you are working in.** This root file owns the conventions shared across all
> benchmarks; the per-benchmark files only describe what is unique to them.

## 0. The two rules that override everything

1. **`app/` MUST NOT depend on `harness/`.** Ever. Not in build files, not in
   imports, not in config. The application is a standalone, shippable project
   that happens to live next to a harness. If you find yourself adding a
   harness import to app code, stop — the thing you need belongs behind the
   app's external contract or in the harness.
2. **The harness reaches the app ONLY through its external contract** —
   HTTP/gRPC endpoints, Kafka topics + schemas, the CLI, or published
   artifacts (OpenAPI / `.proto` / Avro / SQL DDL). The harness never imports
   an app internal class. This is the invariant that lets any `app/` be lifted
   out of the repo and shipped as its own product.

Everything else below is in service of those two rules.

## 1. Target runtime (current scope: OpenJDK only)

- **Primary:** OpenJDK HotSpot, **JDK 21 LTS**. Secondary smoke target: JDK 17.
- **GraalVM JIT, GraalVM native-image, and CRaC are OUT OF SCOPE for now.**
  Do not add `org.graalvm.*` or `org.crac.*` dependencies to any `app/`.
- The suite is *designed* to compare runtimes later, so the runtime matrix is a
  **harness concern**. Each harness keeps a `runtimes/` registry; today it
  contains only `openjdk-hotspot`. New runtimes are added there, never by
  editing app code.
- Two benchmarks are runtime-defined (05 polyglot → GraalVM; 07 cold-start →
  CRaC/native). For those, "OpenJDK now" means: implement the pure-Java
  baseline + the HotSpot path + the full harness, and leave the non-HotSpot
  modes behind a documented seam (`ScriptEngineAdapter`, `LaunchMode`) that the
  harness selects. The seam must compile and pass tests with only OpenJDK
  present.

## 2. Repository layout

```
jvm-bench-suite/
  CLAUDE.md                      <- you are here (suite-wide)
  benchmarks/
    # Cohort A — Gradle / JDK 21
    01-fintech-ledger/
      CLAUDE.md                  <- benchmark root: scenario, contract, how the pieces fit
      app/      CLAUDE.md         <- the shippable application (no harness deps)
      harness/  CLAUDE.md         <- load gen, metrics capture, KPI extraction, runtimes
    02-microservices-mesh/ ...
    ... 03..08

    # Cohort B — Maven / JDK 17–25
    09-onnx-inference/            <- Spring Boot + ONNX ML Inference (JDK 17, Gatling)
    10-microservices-fleet/       <- 5-service fleet + rolling deploys (JDK 17, Gatling)
    11-autoscaling-burst/         <- Catalog API + HPA burst traffic (JDK 17, k6)
    12-hft-trading-gateway/       <- gRPC order gateway, value classes (JDK 25, custom gen)
    13-large-monolith/            <- 500+ bean monolith warm-up cycles (JDK 17, Gatling)
```

Each `app/` is its own self-contained Gradle build (its own `settings.gradle.kts`
and wrapper) so it can be copied out wholesale. Each `harness/` is a separate
Gradle build. They are siblings, not a parent/child Gradle multi-project — that
physical separation enforces rule #1 at the build level.

## 3. Build & tooling conventions (Cohort A — benchmarks 01–08)

> Cohort B benchmarks (09–13) use Maven and different JDK targets; their own
> CLAUDE.md files are authoritative for build commands and toolchain config.

- **Gradle (Kotlin DSL)** with the wrapper (`./gradlew`). Pin the JDK with the
  Gradle toolchain block — never rely on the machine's `JAVA_HOME`:
  ```kotlin
  java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
  ```
- App build commands (run from inside `app/`): `./gradlew build`,
  `./gradlew test`, `./gradlew run` (or `bootRun` for Spring Boot apps).
- Harness build commands (from inside `harness/`): `./gradlew build`, then the
  documented runner (each harness exposes `./gradlew bench --args="..."` or a
  `bin/run` script).
- **No Lombok in app code.** Apps must read cleanly when shipped standalone;
  use Java 21 records, sealed types, and pattern matching instead.
- Pin every dependency version explicitly (a version catalog `libs.versions.toml`
  per build). Reproducibility is a measurement requirement, not a nicety.

## 4. The app↔harness contract (per benchmark)

Each `app/` publishes its contract under `app/contract/`:
- HTTP services: `openapi.yaml`.
- gRPC services: `*.proto`.
- Kafka/streaming: Avro/JSON schemas + a `topics.md` describing topic names,
  partitioning, and key layout.
- Batch jobs: the CLI spec + input/output file schemas.
- Any DB: `schema.sql` (DDL only).

The harness generates its clients from `app/contract/` (read-only). When a
contract changes, update `app/contract/` first; the harness is regenerated from
it. A harness change must never force an app change.

## 5. Observability (every app, mandatory)

Every `app/` ships with, by default and with zero harness involvement:
- **Prometheus metrics** at `/metrics` via Micrometer: business metrics
  (e.g. successful-txn/sec, event lag) + JVM metrics (GC, threads, memory) +
  infra metrics (pool stats, consumer lag) where relevant.
- **Structured JSON logging** (one event per line), sampled where hot.
- **JFR-readiness**: the app does nothing to prevent `-XX:StartFlightRecording`;
  the harness owns when/whether to record.

Every `harness/` ships:
- `docker-compose.yml` for external deps (Postgres, Kafka, …), pinned versions.
- Pre-wired **Prometheus + Grafana** with a dashboard per scenario that
  correlates GC pauses / JIT compilation with tail-latency spikes and plots
  warmup curves (QPS vs time, code-cache usage).
- JVM flag presets in `harness/jvm-flags/`: `g1.opts`, `zgc.opts`,
  `shenandoah.opts`, `throughput.opts`, `lowlatency.opts`, plus a shared
  `logging.opts` with `-Xlog:gc*,jit+compilation` and JFR start flags. **Apps
  never hard-code a GC**; the harness supplies the flag set.

## 6. KPIs and the results contract

Core KPIs collected for every run (harness responsibility):
- Throughput (ops/sec, events/sec, or records/sec).
- Latency distribution via **HdrHistogram**: p50, p95, p99, p99.9, p99.99 + jitter.
- GC pause-time distribution + frequency + total GC time (from unified GC logs / JFR).
- Allocation rate, committed heap, metaspace, and **native memory**
  (`jcmd <pid> VM.native_memory summary`) and RSS.
- CPU utilisation (total and per-core).

Each harness emits a normalised **`results.json`** so runtimes/configs compare
apples-to-apples. Schema (stable across all benchmarks):
```json
{
  "benchmark": "01-fintech-ledger",
  "runtime": "openjdk-hotspot-21",
  "gc": "G1",
  "jvm_flags": ["-Xms4g", "-Xmx4g", "..."],
  "env": {"cpu": "...", "kernel": "...", "cgroup_cpu": "4", "cgroup_mem": "8Gi"},
  "load_profile": "salary-day-burst",
  "phases": {"warmup_s": 900, "measure_s": 1800},
  "kpis": {
    "throughput": 0, "p50_ms": 0, "p99_ms": 0, "p999_ms": 0, "p9999_ms": 0,
    "gc_pause_p99_ms": 0, "alloc_rate_mb_s": 0, "rss_mb": 0, "native_mem_mb": 0,
    "cpu_util_pct": 0
  },
  "mode_kpis": {}
}
```
`mode_kpis` holds the benchmark-specific extras (e.g. cold-start ms, checkpoint
size, FFI call overhead). Never reshape the core block — downstream comparison
tooling depends on it.

## 7. Measurement protocol (don't shortcut these)

- **Distinct warmup and measurement phases**, both recorded in `results.json`.
- **Stepped load**: ramp in steps to capture the saturation/degradation curve,
  not a single point.
- **Repeat runs** (default 5) and report the distribution, not one number.
- **Relative comparisons under identical conditions** are the deliverable;
  absolute p99.9 numbers are environment-sensitive (OS noise, neighbours,
  network) and must always travel with their full env descriptor.
- **Deterministic synthetic data**: every generator takes a fixed seed.

## 8. What "done" looks like for a benchmark

- `app/` builds, tests, and runs on OpenJDK 21 with no harness on the classpath,
  and can be zipped out and run elsewhere using only its own `CLAUDE.md`.
- `harness/` brings up deps via compose, drives the app through its contract,
  produces a valid `results.json`, and renders its Grafana dashboard.
- A laptop-scale minimal config exists (single broker, local DB, one JVM); the
  high-load multi-node config is an optional extension.

## 9. House style for agents

- Prefer small, reviewable diffs. Touch `app/` and `harness/` in separate
  commits — mixing them is a smell that rule #1 is being violated.
- When unsure whether logic belongs in app or harness: if it would still make
  sense in the shipped product, it's app; if it only exists to *measure* the
  product, it's harness.
- Keep this file and the per-benchmark files updated when you change build
  commands, contracts, or the results schema.
