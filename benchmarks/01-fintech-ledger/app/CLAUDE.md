# CLAUDE.md — High-Throughput Fintech Ledger Service · APPLICATION


> **Current implementation status:** this directory currently contains a local smoke-test scaffold, not the full benchmark application described below. The scaffold exists so `run.sh build` / `run.sh test` and the harness/result pipeline can be exercised without external services. The full implementation described in this file remains the target and should replace the scaffold incrementally. See `../../../IMPLEMENTATION_STATUS.md`.

This is the **shippable application** for benchmark 01. It is a standalone
project: it must build, test, and run on **OpenJDK 21** with no benchmark
harness anywhere on its classpath, and it can be lifted out of the suite and
shipped as its own product using only this file.

## The one rule for this directory

**Never add a dependency on, or an import from, `../harness/`.** If you need
something the harness has, either it belongs behind this app's external
contract, or it is a measurement concern that stays in the harness. Adding a
harness reference here breaks the ability to ship this app.

## What it is

A transactional ledger backend (transfers, balances, history) modelling banking/payments, used to stress GC under heavy short-lived allocation, tail latency under bursts + DB contention, and JIT on deep validation/rules call graphs.

## Stack

Spring Boot 3 (Java 21), REST + gRPC, PostgreSQL via JDBC/JPA, optional Redis read cache, TLS, Micrometer/Prometheus.

## Responsibilities

- Expose endpoints: POST transfer, GET balance, GET recent transactions (REST and gRPC, same logical contract).
- Persist to PostgreSQL with transactional semantics; accounts / ledger_entries / balances schema; optimistic locking for consistency.
- Run validation (limits, balances) and a fraud/AML check step (a real call-out shape, backed by a stub service by default).
- Emit business metrics (successful_txns_total, fraud_rejects_total) and structured JSON logs.

## External contract (the only surface the harness may use)

Published under `contract/`:
- `contract/openapi.yaml` — REST surface.
- `contract/ledger.proto` — gRPC surface (same operations).
- `contract/schema.sql` — Postgres DDL.
- `contract/fraud-api.md` — the fraud/AML call-out shape the harness can stub or replace.

Change `contract/` first when the surface changes; everything downstream
(harness clients, generated stubs) follows from it.

## Build & run (OpenJDK 21)

```bash
./gradlew build        # compile + unit tests
./gradlew test         # tests only
./gradlew bootRun   # serves REST on :8443 (TLS), gRPC on :9443, metrics on :8081/metrics
```

JDK is pinned via the Gradle toolchain (`JavaLanguageVersion.of(21)`); do not
depend on the host `JAVA_HOME`.

## External dependencies

PostgreSQL (and Redis if the cache profile is enabled). For standalone shipping, point `DB_URL`/`REDIS_URL` at real instances; the app does not embed them.

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
