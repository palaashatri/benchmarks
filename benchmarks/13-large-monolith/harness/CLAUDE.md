# CLAUDE.md — benchmark-5-large-monolith-harness

## Overview

Test harness for the large monolith benchmark. Drives HTTP load against the
monolith, orchestrates repeated restart cycles, and captures warm-up curves
(throughput and latency vs time since cold start). Connects to the app via
HTTP only.

## JDK Target: 17

## Tech Stack

- Java 17, Gatling 3.11 (Scala DSL), shell scripts, Prometheus, Grafana

> **Note:** The harness CLAUDE.md for this benchmark was not included in the
> initial specification document. This file documents the intended structure;
> fill in simulation details when implementing.

## Project Structure

```
benchmark-5-large-monolith-harness/
├── pom.xml
├── docker-compose.yml              # Prometheus + Grafana + app stack
├── src/main/scala/com/palaashatri/bench/harness/
│   ├── WarmupCurveSimulation.scala     # captures throughput ramp from cold
│   ├── SteadyStateSimulation.scala
│   └── RestartCycleSimulation.scala    # kill + restart + measure loop
├── scripts/
│   ├── run-warmup-test.sh
│   ├── run-restart-cycle.sh            # repeats N restart cycles
│   └── collect-jit-metrics.sh          # jcmd VM.compilation / JFR export
├── prometheus/
│   └── prometheus.yml
├── grafana/
│   └── dashboards/
│       └── monolith-warmup.json
└── results/
```

## Key Measurements

- **Time to first successful response** after cold start (ms).
- **Time to reach 90% of steady-state throughput** (TTCOB proxy, s).
- **p99 latency vs time** during warm-up window (first 5 min).
- **JIT compilation events** — total methods compiled, peak compilation
  rate (methods/s), CPU spike width. Collected via JFR or
  `jcmd <pid> VM.compilation`.
- **GC pause distribution** during warm-up vs steady state.
- Repeat N=5 restart cycles and report the distribution of warm-up metrics.

## What NOT to Do

- Do NOT import or depend on the application source code.
- Do NOT measure warm-up with the JVM running warm from a prior test run —
  each cycle must start from a fresh JVM process.
- Do NOT run Gatling in the same JVM as the application.
