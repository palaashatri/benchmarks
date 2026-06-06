# CLAUDE.md — Benchmark 04: ML Inference & Feature Pipeline (Panama & Vector)

> Suite conventions live in the repo-root `../../CLAUDE.md`. **Read that first.**
> This file covers only what is specific to this benchmark and how its two
> halves fit together.

## Scenario

An online inference service whose model-execution path can be pure-Java, JNI, or Panama FFM, with hot numeric kernels in scalar vs Vector API form — to measure FFI overhead and SIMD gains in a real service with allocation, parsing, and GC in the mix.

## How the pieces fit (and why they are split)

```
04-ml-inference-panama-vector/
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

- **FFI overhead in context** — not microbench-only; measure how JNI vs FFM choice moves end-to-end request latency + GC when parsing, feature compute, and allocation are all present.
- **Vector API performance** — scalar vs vectorised feature transforms; how HotSpot vectorises the kernels.
- **Startup vs warmup** — model loading + JIT of numeric kernels create distinctive warmup curves.

## Current scope (OpenJDK only)

GraalVM JIT / native-image preservation of Vector gains is deferred to the harness runtime registry; HotSpot 21 only now.

## Run order for a full benchmark pass

1. From `harness/`: bring up dependencies — `docker-compose up -d`.
2. From `app/`: build and start the application (see `app/CLAUDE.md`).
3. From `harness/`: run the load profiles and collect results (see `harness/CLAUDE.md`).
4. Inspect `harness/results/*.json` (normalised schema in the suite root) and
   the Grafana dashboard.

## Comparative questions the results should answer

- Does FFM consistently beat JNI across call granularities (single vs batched)?
- How much of the SLA is feature compute vs model execution vs FFI overhead?
