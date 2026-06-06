# CLAUDE.md — Benchmark 05: Polyglot Service vs Pure-Java Baseline

> Suite conventions live in the repo-root `../../CLAUDE.md`. **Read that first.**
> This file covers only what is specific to this benchmark and how its two
> halves fit together.

## Scenario

A business service that delegates one piece of logic (pricing rules / transform) to an embedded script, compared against an equivalent pure-Java implementation — to quantify polyglot engine overhead. NOTE: meaningful polyglot numbers need GraalVM; under OpenJDK-now we build the baseline + seam only.

## How the pieces fit (and why they are split)

```
05-polyglot-service/
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

- **(Deferred) polyglot engine overhead** — startup/warmup of the polyglot runtime, extra footprint for dynamic-language runtimes, tail-latency impact when polyglot calls are frequent.
- **(Deferred) interop allocations & dynamic dispatch** — cross-language conversions/wrappers and their GC impact.
- **(Now) baseline characterisation** — establish the pure-Java throughput/latency/GC baseline the polyglot path will later be measured against.

## Current scope (OpenJDK only)

This benchmark is GraalVM-defined. OpenJDK-now delivers: pure-Java baseline (shippable), the adapter seam, and the harness. The GraalVM polyglot impl + runtime are a separate phase and must NOT add `org.graalvm.*` to app/ until then.

## Run order for a full benchmark pass

1. From `harness/`: bring up dependencies — `docker-compose up -d`.
2. From `app/`: build and start the application (see `app/CLAUDE.md`).
3. From `harness/`: run the load profiles and collect results (see `harness/CLAUDE.md`).
4. Inspect `harness/results/*.json` (normalised schema in the suite root) and
   the Grafana dashboard.

## Comparative questions the results should answer

- (Deferred) When is GraalVM polyglot worth the overhead vs generating the logic in pure Java?
- (Deferred) Does the polyglot path break the latency SLO without enough flexibility to justify it?
