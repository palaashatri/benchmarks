# CLAUDE.md — Data-Engineering ETL Batch Job · APPLICATION


> **Current implementation status:** this directory currently contains a local smoke-test scaffold, not the full benchmark application described below. The scaffold exists so `run.sh build` / `run.sh test` and the harness/result pipeline can be exercised without external services. The full implementation described in this file remains the target and should replace the scaffold incrementally. See `../../../IMPLEMENTATION_STATUS.md`.

This is the **shippable application** for benchmark 08. It is a standalone
project: it must build, test, and run on **OpenJDK 21** with no benchmark
harness anywhere on its classpath, and it can be lifted out of the suite and
shipped as its own product using only this file.

## The one rule for this directory

**Never add a dependency on, or an import from, `../harness/`.** If you need
something the harness has, either it belongs behind this app's external
contract, or it is a measurement concern that stays in the harness. Adding a
harness reference here breaks the ability to ship this app.

## What it is

A nightly ETL pipeline over multi-GB CSV/Parquet — parse, cleanse, join, aggregate, enrich, write Parquet — used to stress throughput over long runs, on-heap vs off-heap (Arrow) memory management, and mixed numeric/string JIT hotspots.

## Stack

Java 21; two implementations: a distributed engine (Spark/Flink in local or small-cluster mode) and a custom single-JVM pipeline (Streams / hand-rolled). Columnar off-heap (Apache Arrow) option. Micrometer.

## Responsibilities

- Read large CSV/Parquet inputs (several GB).
- Parse + cleanse, join + aggregate (by user, by day), optional enrichment from a lookup table.
- Compress and write Parquet output.
- Offer both the distributed-engine and single-JVM implementations behind one CLI contract.

## External contract (the only surface the harness may use)

Published under `contract/`:
- `contract/cli.md` — job invocation, input/output paths, flags.
- `contract/input-schema.md`, `contract/output-schema.md` — file schemas (CSV/Parquet columns + types).

Change `contract/` first when the surface changes; everything downstream
(harness clients, generated stubs) follows from it.

## Build & run (OpenJDK 21)

```bash
./gradlew build        # compile + unit tests
./gradlew test         # tests only
./gradlew run --args="--in data/in --out data/out --impl single-jvm"   # or --impl spark
```

JDK is pinned via the Gradle toolchain (`JavaLanguageVersion.of(21)`); do not
depend on the host `JAVA_HOME`.

## External dependencies

A filesystem or object store for inputs/outputs; Spark/Flink for the distributed impl (local mode for laptop scale).

## Observability (built in, harness-independent)

- Prometheus metrics at `/metrics` (Micrometer): business + JVM + infra metrics.
- Structured JSON logging, one event per line.
- JFR-ready: the app does nothing that blocks `-XX:StartFlightRecording`. It
  **does not hard-code a garbage collector** — GC selection is supplied
  externally (by the harness, or by whoever runs the shipped app).

## Standalone shipping checklist

- Builds and tests green with only OpenJDK 21 present.
- No `../harness` references anywhere (grep before you commit).
- `contract/` is complete and matches the running surface.
- README/run instructions reproducible from this file alone.

## Code style

Java 21 records / sealed types / pattern matching. No Lombok. Pin dependency
versions in the per-build version catalog.
