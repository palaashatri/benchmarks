# CLAUDE.md — Benchmark 02: Event-Driven Microservices Mesh

> Suite conventions live in the repo-root `../../CLAUDE.md`. **Read that first.**
> This file covers only what is specific to this benchmark and how its two
> halves fit together.

## Scenario

A 3–5 service mesh processing events asynchronously, implemented twice over identical logic — once reactive (non-blocking), once on Loom (virtual threads + blocking style) — to compare concurrency, scheduling, and GC in message storms.

## How the pieces fit (and why they are split)

```
02-microservices-mesh/
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

- **High-concurrency async I/O** — tens/hundreds of thousands of in-flight ops; thread-pool+NIO event loop vs many virtual threads stresses context switching, park/unpark, lock contention.
- **Scheduler overhead & fairness** — does the vthread scheduler distribute work evenly under load; do millions of vthreads create GC pressure or scheduler pathologies?
- **GC in message-heavy systems** — headers, payload wrappers, futures, callbacks: young-gen pressure with a short-pause requirement.

## Current scope (OpenJDK only)

Reactive vs Loom is the live axis. GraalVM comparison deferred. Loom is GA in 21 — no preview flags needed.

## Run order for a full benchmark pass

1. From `harness/`: bring up dependencies — `docker-compose up -d`.
2. From `app/`: build and start the application (see `app/CLAUDE.md`).
3. From `harness/`: run the load profiles and collect results (see `harness/CLAUDE.md`).
4. Inspect `harness/results/*.json` (normalised schema in the suite root) and
   the Grafana dashboard.

## Comparative questions the results should answer

- Loom vs reactive throughput/latency across concurrency scales — is there a crossover point?
- Do Loom and reactive need different GC tuning, or is there a config that serves both?
