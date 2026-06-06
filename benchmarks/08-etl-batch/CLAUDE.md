# CLAUDE.md — Benchmark 08: Data-Engineering ETL Batch Job

> Suite conventions live in the repo-root `../../CLAUDE.md`. **Read that first.**
> This file covers only what is specific to this benchmark and how its two
> halves fit together.

## Scenario

A nightly ETL pipeline over multi-GB CSV/Parquet — parse, cleanse, join, aggregate, enrich, write Parquet — used to stress throughput over long runs, on-heap vs off-heap (Arrow) memory management, and mixed numeric/string JIT hotspots.

## How the pieces fit (and why they are split)

```
08-etl-batch/
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

- **Throughput over long runs** — total job-completion time dominates; small latency spikes are acceptable if throughput is high.
- **Memory management & off-heap** — columnar off-heap stores (Arrow) interact with GC differently than on-heap; measure both.
- **Mixed workloads** — numeric aggregation + heavy string handling (CSV/JSON parsing) create diverse JIT hotspots.

## Current scope (OpenJDK only)

GC matrix on HotSpot 21 is the focus. GraalVM JIT comparison deferred to the harness registry. Shares dataset/harness components with 03-streaming-analytics where possible.

## Run order for a full benchmark pass

1. From `harness/`: bring up dependencies — `docker-compose up -d`.
2. From `app/`: build and start the application (see `app/CLAUDE.md`).
3. From `harness/`: run the load profiles and collect results (see `harness/CLAUDE.md`).
4. Inspect `harness/results/*.json` (normalised schema in the suite root) and
   the Grafana dashboard.

## Comparative questions the results should answer

- Do ZGC/Shenandoah help long-running ETL, or does G1's higher throughput dominate?
- Does aggressive JIT optimisation help heavy numeric + string processing here?
