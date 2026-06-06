# CLAUDE.md — Benchmark 03: Streaming Analytics Pipeline

> Suite conventions live in the repo-root `../../CLAUDE.md`. **Read that first.**
> This file covers only what is specific to this benchmark and how its two
> halves fit together.

## Scenario

Real-time analytics over clickstream/IoT data (~1M events/sec) with sliding/tumbling windows, keyed aggregations and joins, used to stress long-running steady-state GC, on-heap vs off-heap state, and JIT/code-cache pressure from many small operators.

## How the pieces fit (and why they are split)

```
03-streaming-analytics/
  CLAUDE.md      <- you are here
  app/           <- the SHIPPABLE application (its own Gradle build, no harness deps)
  harness/       <- load gen, metrics capture, KPI extraction, runtime registry
```

- `app/` is a standalone project. It builds, tests, and runs on **OpenJDK 21**
  with no harness on the classpath, and can be copied out of this repo and
  shipped using only `app/CLAUDE.md`.
- `harness/` drives `app/` exclusively through its **external contract** (see
  `app/contract/`). It never imports an app internal class.
- When the contract changes, edit `app/contract/` first; the harness regenerates
  from it. A harness change must never force an app change.

## JVM dimensions this benchmark exists to stress

- **Long-running steady-state GC** — hours of sustained load reveal pause behaviour vs heap size, promotion rate, fragmentation (G1's pause floor vs ZGC/Shenandoah's throughput trade).
- **State management & memory layout** — on-heap vs off-heap (direct buffers, RocksDB) interact with GC very differently.
- **JIT & code-cache pressure** — many small operator methods test inlining and code-cache management under high throughput.

## Current scope (OpenJDK only)

GraalVM / native / CRaC likely irrelevant for long-running compute nodes; GC matrix is the focus. Deferred runtimes left as harness registry stubs.

## Run order for a full benchmark pass

1. From `harness/`: bring up dependencies — `docker-compose up -d`.
2. From `app/`: build and start the application (see `app/CLAUDE.md`).
3. From `harness/`: run the load profiles and collect results (see `harness/CLAUDE.md`).
4. Inspect `harness/results/*.json` (normalised schema in the suite root) and
   the Grafana dashboard.

## Comparative questions the results should answer

- G1 vs ZGC vs Shenandoah on moderate vs large state and strict vs relaxed latency.
- Does an off-heap backend shift the GC story enough to change the GC recommendation?
