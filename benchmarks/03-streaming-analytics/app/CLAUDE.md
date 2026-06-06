# CLAUDE.md — Streaming Analytics Pipeline · APPLICATION

This is the **shippable application** for benchmark 03. It is a standalone
project: it must build, test, and run on **OpenJDK 21** with no benchmark
harness anywhere on its classpath, and it can be lifted out of the suite and
shipped as its own product using only this file.

## The one rule for this directory

**Never add a dependency on, or an import from, `../harness/`.** If you need
something the harness has, either it belongs behind this app's external
contract, or it is a measurement concern that stays in the harness. Adding a
harness reference here breaks the ability to ship this app.

## What it is

Real-time analytics over clickstream/IoT data (~1M events/sec) with sliding/tumbling windows, keyed aggregations and joins, used to stress long-running steady-state GC, on-heap vs off-heap state, and JIT/code-cache pressure from many small operators.

## Stack

Java 21; Apache Flink (default) or Kafka Streams topology. On-heap AND off-heap (RocksDB / direct buffers) state backends, switchable. Kafka in/out. Micrometer.

## Responsibilities

- Ingest a high-volume event stream from Kafka.
- Apply sliding (e.g. 30s window sliding every 0.1s) and tumbling windows.
- Perform keyed aggregations and joins.
- Write results to an output Kafka topic / store.
- Expose a state-backend switch (on-heap vs off-heap) as config, not code change.

## External contract (the only surface the harness may use)

Published under `contract/`:
- `contract/topics.md` — input/output topics, key distribution, window semantics.
- `contract/event.avsc`, `contract/result.avsc` — schemas.
- `contract/state-backends.md` — how to select on-heap vs off-heap.

Change `contract/` first when the surface changes; everything downstream
(harness clients, generated stubs) follows from it.

## Build & run (OpenJDK 21)

```bash
./gradlew build        # compile + unit tests
./gradlew test         # tests only
./gradlew run -Pbackend=offheap   # or -Pbackend=onheap ; submits the topology to a local Flink mini-cluster by default
```

JDK is pinned via the Gradle toolchain (`JavaLanguageVersion.of(21)`); do not
depend on the host `JAVA_HOME`.

## External dependencies

Kafka; Flink (local mini-cluster for laptop scale, real cluster for high-load extension).

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
