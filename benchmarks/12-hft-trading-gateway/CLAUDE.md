# CLAUDE.md — Benchmark 12: High-Frequency Trading Order Gateway

> Suite conventions live in the repo-root `../../CLAUDE.md`. **Read that first.**
> This file covers only what is specific to this benchmark and how its two
> halves fit together.

## Scenario

A simulated high-frequency trading order gateway. Accepts orders via gRPC,
validates through 8 rule validators (sealed interface chain), maintains an
in-memory order book per symbol, and writes an async audit trail via virtual
threads. Designed for sub-millisecond, nanosecond-precision latency
measurement.

## How the pieces fit (and why they are split)

```
12-hft-trading-gateway/
  CLAUDE.md      <- you are here
  app/           <- the shippable gRPC gateway (Maven, JDK 25, no harness deps)
  harness/       <- custom Java load generator using gRPC stubs + HdrHistogram
```

- `app/` targets **JDK 25** to use value classes (JEP 401) for zero-allocation
  order/fill/quote DTOs and virtual threads for audit I/O.
- `harness/` is a custom Java 17 load generator — k6 and Gatling are
  **not suitable** for nanosecond-level gRPC latency measurement. It connects
  only via the gRPC stub generated from `trading.proto`.

## JVM dimensions this benchmark exists to stress

- **Zero-allocation hot path** — value classes eliminate object header and
  heap allocation for `Order`, `Fill`, `Quote`; the benchmark quantifies the
  GC pause reduction vs reference-type equivalents.
- **Polymorphic validation dispatch** — 8 sealed `OrderValidator`
  implementations are chained per request; JIT inlining and escape analysis
  across the chain is the key JIT dimension.
- **Virtual-thread I/O overhead** — audit trail writes use
  `Thread.startVirtualThread`; the benchmark measures the scheduling overhead
  on the hot-path latency distribution.
- **Nanosecond tail latency** — p99.9 and p99.99 at sub-millisecond scale are
  the deliverable; HdrHistogram is used throughout.

## Current scope

JDK 25 (value classes under JEP 401). Spring Boot is explicitly out of scope
for the app; a raw gRPC server is used to minimise framework overhead. The
harness targets JDK 17.

## Run order for a full benchmark pass

1. From `app/`: build with Maven (JDK 25 toolchain), start the gRPC server.
2. From `harness/`: run `scripts/run-latency-test.sh` for the steady-state
   latency profile, or `run-throughput-test.sh` for max-throughput search.
3. `scripts/generate-histogram.sh` produces an HdrHistogram report and the
   `results.json` entry.

## Comparative questions the results should answer

- What is the p99.9 / p99.99 latency for the full validate → match → audit
  pipeline?
- How much does GC pause contribute to the tail at various heap sizes?
- What is the allocation rate with value classes vs equivalent records?
- What is the maximum sustainable orders/sec before p99 exceeds 1 ms?
