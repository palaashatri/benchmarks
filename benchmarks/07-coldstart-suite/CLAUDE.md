# CLAUDE.md — Benchmark 07: Serverless / CRaC / Native-Image Cold-Start Suite

> Suite conventions live in the repo-root `../../CLAUDE.md`. **Read that first.**
> This file covers only what is specific to this benchmark and how its two
> halves fit together.

## Scenario

A small set of serverless-style functions runnable in multiple launch modes, to measure cold-start, warm-invocation latency, and footprint. NOTE: CRaC + native-image are the point of this benchmark but are OUT OF SCOPE now; OpenJDK-now delivers the functions + HotSpot cold/warm harness behind a LaunchMode seam.

## How the pieces fit (and why they are split)

```
07-coldstart-suite/
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

- **Startup & time-to-first-request** — the cost that dominates FaaS billing and user-visible latency; HotSpot baseline established now.
- **(Deferred) snapshot/restore behaviour** — CRaC lifecycle (beforeCheckpoint/afterRestore), resources that must close before checkpoint, restore time + success rate across environments.
- **(Deferred) JIT vs AOT trade-offs** — native avoids warmup but caps peak throughput; CRaC keeps warmed JIT state at the cost of resource management + checkpoint size.

## Current scope (OpenJDK only)

CRaC + native-image are the reason this benchmark exists but are explicitly OUT OF SCOPE now. Deliver the functions, the HotSpot cold/warm harness, and the `LaunchMode` seam. Do NOT add `org.crac.*` or native-image config to app/ yet.

## Run order for a full benchmark pass

1. From `harness/`: bring up dependencies — `docker-compose up -d`.
2. From `app/`: build and start the application (see `app/CLAUDE.md`).
3. From `harness/`: run the load profiles and collect results (see `harness/CLAUDE.md`).
4. Inspect `harness/results/*.json` (normalised schema in the suite root) and
   the Grafana dashboard.

## Comparative questions the results should answer

- (Deferred) When is CRaC superior to native (full JIT + reflection freedom) and vice versa?
- (Deferred) How do Spring Boot / Micronaut / Quarkus behave per mode?
