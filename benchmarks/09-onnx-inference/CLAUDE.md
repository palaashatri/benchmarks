# CLAUDE.md — Benchmark 09: Spring Boot + ONNX ML Inference Service

> Suite conventions live in the repo-root `../../CLAUDE.md`. **Read that first.**
> This file covers only what is specific to this benchmark and how its two
> halves fit together.

## Scenario

A production-grade Spring Boot REST service that performs ML inference using
ONNX Runtime. Stresses JVM warm-up time, JIT compilation of the tokenisation
and tensor-construction hot paths, thread-per-request model under sustained
concurrency, and GC behaviour on short-lived tensor/array allocations.

## How the pieces fit (and why they are split)

```
09-onnx-inference/
  CLAUDE.md      <- you are here
  app/           <- the SHIPPABLE application (Maven build, no harness deps)
  harness/       <- Gatling load gen, JVM metric collection, comparison runner
```

- `app/` is a standalone project. It builds, tests, and runs on **JDK 17**
  with no harness on the classpath, and can be copied out of this repo and
  shipped using only `app/CLAUDE.md`.
- `harness/` drives `app/` exclusively through its **HTTP endpoint**
  (`/api/v1/inference/classify`). It never imports an app internal class.
- When the contract changes, update `app/` first; the harness follows.

## JVM dimensions this benchmark exists to stress

- **JIT warm-up** — WordPiece tokenisation and ONNX tensor construction are
  tight loops that the JIT must optimise; cold vs warm latency delta is the
  primary signal.
- **Thread-per-request concurrency** — servlet-based model, no async/reactive,
  so thread-pool sizing and contention under burst load are visible.
- **Short-lived allocation** — every request allocates token arrays, tensor
  wrappers, and result objects; GC pressure and allocation rate are tracked.
- **JIT on native FFI boundary** — ONNX Runtime is a native library; the
  JVM↔native boundary cost under profiling load is measurable here.

## Current scope

OpenJDK HotSpot 17, CPU-only ONNX provider. GPU provider and GraalVM native
are explicitly out of scope.

## Run order for a full benchmark pass

1. Download/export the DistilBERT SST-2 ONNX model and vocab (see
   `app/CLAUDE.md` for the download script).
2. From `app/`: build with Maven and start the service.
3. From `harness/`: bring up Prometheus + Grafana with `docker-compose up -d`,
   then run the desired simulation.
4. Inspect `harness/results/` and the Grafana dashboard.

## Comparative questions the results should answer

- How much does JIT warm-up degrade p99 in the first 60 seconds vs steady state?
- What is the allocation rate at 200 rps, and which GC (G1 vs ZGC) handles it
  more predictably for this workload?
- How does the cold-start latency compare to the fully-warmed steady-state
  throughput ceiling?
