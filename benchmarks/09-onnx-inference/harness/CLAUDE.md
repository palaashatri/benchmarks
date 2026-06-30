# CLAUDE.md — benchmark-1-onnx-inference-harness


> **Current implementation status:** this directory currently contains a local smoke-test harness scaffold, not the full load-generation and observability harness described below. The scaffold exists so `run.sh build` / `run.sh test` can produce schema-shaped smoke results. The full harness described in this file remains the target and should replace the scaffold incrementally. See `../../../IMPLEMENTATION_STATUS.md`.

## Overview

Test harness for Benchmark 09. Drives load against the ONNX inference
application, collects JVM-level metrics, and produces comparison reports.
Completely separate from the application — connects only via HTTP.

## JDK Target: 17

## Tech Stack

- Java 17, Gatling 3.11 (Scala DSL), Docker Compose, Prometheus, Grafana,
  shell scripts

## Project Structure

```
benchmark-1-onnx-inference-harness/
├── pom.xml
├── docker-compose.yml          # Prometheus + Grafana + app
├── src/main/scala/com/palaashatri/bench/harness/
│   ├── ColdStartSimulation.scala
│   ├── SteadyStateSimulation.scala
│   ├── BurstLoadSimulation.scala
│   └── FleetScaleSimulation.scala
├── src/main/resources/
│   ├── test-data/
│   │   ├── positive-reviews.txt
│   │   ├── negative-reviews.txt
│   │   └── mixed-reviews.txt
│   ├── gatling.conf
│   └── bodies/
│       └── inference-request.json
├── prometheus/
│   └── prometheus.yml
├── grafana/
│   └── dashboards/
│       └── onnx-benchmark.json
├── scripts/
│   ├── run-cold-start-test.sh
│   ├── run-steady-state-test.sh
│   ├── run-comparison.sh       # runs all JVM configs
│   ├── collect-jvm-metrics.sh
│   └── generate-report.sh
└── results/
    └── .gitkeep
```

## Dependencies (pom.xml)

```xml
<dependencies>
    <dependency>
        <groupId>io.gatling.highcharts</groupId>
        <artifactId>gatling-charts-highcharts</artifactId>
        <version>3.11.5</version>
    </dependency>
    <dependency>
        <groupId>io.gatling</groupId>
        <artifactId>gatling-app</artifactId>
        <version>3.11.5</version>
    </dependency>
</dependencies>
```

## Key Simulations

### ColdStartSimulation

Immediately hits the app with 50 rps for 10 s (cold measurement window),
then sustains 200 rps for 60 s, then continues at 200 rps for 120 s as a
steady-state reference — no warm-up ramp. Target: p99 < 500 ms, success
rate > 99%.

### SteadyStateSimulation

Ramps from 10 rps to 500 rps over 120 s (warm-up), then holds 500 rps for
300 s (measurement window).

### Comparison Runner (`run-comparison.sh`)

Starts the app with OpenJDK 17 + G1GC (`-Xms512m -Xmx1g -XX:+UseG1GC`),
routes GC logs to `$RESULTS_DIR/openjdk-gc.log`, runs the target Gatling
simulation, kills the app, and repeats for each JVM config defined in the
script. Results land in a timestamped subdirectory under `results/`.

## docker-compose.yml

Brings up:
- `app` — builds from `../benchmark-1-onnx-inference-app`, port 8080, with
  GC logging enabled.
- `prometheus` — prom/prometheus:v2.53.0, port 9090, scrapes `/actuator/prometheus`.
- `grafana` — grafana/grafana:11.1.0, port 3000, loads dashboard from
  `grafana/dashboards/`. Admin password: `benchmark`.

## Metrics to Collect

- `inference.latency` (p50, p90, p99, p99.9) via Prometheus
- `jvm_gc_pause_seconds` (count + sum) — from Spring Actuator
- `jvm_compilation_time_ms` — total JIT compilation time
- `process_cpu_usage` — app CPU utilisation
- Time to first successful response after startup
- Time to reach 90% of steady-state throughput (TTCOB proxy)

## What NOT to Do

- Do NOT import or depend on the application source code — harness connects
  via HTTP only.
- Do NOT run Gatling from inside the application container.
- Do NOT measure in the same JVM as the application.
