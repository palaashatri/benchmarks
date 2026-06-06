# CLAUDE.md — Benchmark 01: High-Throughput Fintech Ledger Service

> Suite conventions live in the repo-root `../../CLAUDE.md`. **Read that first.**
> This file covers only what is specific to this benchmark and how its two
> halves fit together.

## Scenario

A transactional ledger backend (transfers, balances, history) modelling banking/payments, used to stress GC under heavy short-lived allocation, tail latency under bursts + DB contention, and JIT on deep validation/rules call graphs.

## How the pieces fit (and why they are split)

```
01-fintech-ledger/
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

- **GC under intense short-lived allocation** — every txn spawns DTOs, JSON buffers, ORM entities, rule objects; the goal is predictable pauses, not minimal ones.
- **Tail latency under contention + bursts** — salary-day / campaign spikes combined with DB contention and fraud call-outs push p99+ where GC pauses and JIT deopts surface as SLO violations.
- **JIT on deep, branch-heavy call graphs** — validation + rules engines exercise inlining, escape analysis, and deopt behaviour.

## Current scope (OpenJDK only)

GraalVM JIT / native-image comparison is deferred; only the GC matrix (G1/ZGC/Shenandoah on HotSpot 21) is active now.

## Run order for a full benchmark pass

1. From `harness/`: bring up dependencies — `docker-compose up -d`.
2. From `app/`: build and start the application (see `app/CLAUDE.md`).
3. From `harness/`: run the load profiles and collect results (see `harness/CLAUDE.md`).
4. Inspect `harness/results/*.json` (normalised schema in the suite root) and
   the Grafana dashboard.

## Comparative questions the results should answer

- Does a low-pause GC (ZGC/Shenandoah) trade throughput for better tails vs G1 on this allocation pattern?
- Where do non-JVM overheads (DB, network) dominate the tail, and where does GC/JIT?
