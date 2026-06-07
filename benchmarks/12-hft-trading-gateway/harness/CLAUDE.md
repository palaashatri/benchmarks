# CLAUDE.md — benchmark-4-trading-gateway-harness


> **Current implementation status:** this directory currently contains a local smoke-test harness scaffold, not the full load-generation and observability harness described below. The scaffold exists so `run.sh build` / `run.sh test` can produce schema-shaped smoke results. The full harness described in this file remains the target and should replace the scaffold incrementally. See `../../../IMPLEMENTATION_STATUS.md`.

## Overview

Custom Java load generator for the trading gateway. Uses gRPC client stubs
and HdrHistogram for nanosecond-precision latency measurement. k6 and
Gatling are NOT suitable for nanosecond-level trading benchmarks — they add
HTTP overhead that swamps the signal.

## JDK Target: 17

## Tech Stack

- Java 17, gRPC-Java client, HdrHistogram 2.2, Maven

## Project Structure

```
benchmark-4-trading-gateway-harness/
├── pom.xml
├── src/main/java/com/palaashatri/bench/harness/trading/
│   ├── TradingLoadGenerator.java
│   ├── OrderGenerator.java
│   ├── LatencyCollector.java
│   └── ReportGenerator.java
├── scripts/
│   ├── run-latency-test.sh
│   ├── run-throughput-test.sh
│   └── generate-histogram.sh
└── results/
```

## Key Implementation: Load Generator

`TradingLoadGenerator` opens a `ManagedChannel` to the gateway and holds a
blocking stub. It runs a steady-state loop at a caller-specified rps:

- Computes `intervalNanos = 1_000_000_000 / rps`.
- For each iteration: records `System.nanoTime()`, calls
  `stub.submitOrder(OrderGenerator.randomOrder())`, records
  `System.nanoTime() - sendTime` in an HdrHistogram (1-minute max, 3
  significant figures).
- **Busy-waits** for the next send interval using `Thread.onSpinWait()` — no
  `Thread.sleep` to avoid OS scheduling jitter in the timing loop.

At the end of a run, prints p50, p99, p99.9, p99.99, max, and total count.

## Pacing Contract

The harness must NOT be co-located on the same machine as the gateway for
production measurements; network RTT must be eliminated from the latency
sample or explicitly accounted for in the report.

## What NOT to Do

- Do NOT use Gatling or k6 — HTTP-based tools cannot measure nanosecond gRPC
  latency accurately.
- Do NOT use `Thread.sleep` for pacing — use busy-wait with
  `Thread.onSpinWait()`.
- Do NOT run the harness on the same machine as the gateway for production
  measurements.
