# CLAUDE.md — Event-Driven Microservices Mesh · HARNESS

This is the **benchmark harness** for benchmark 02. Its job is to drive the
application through its external contract, apply realistic load, capture JVM
behaviour, and emit a normalised `results.json`. It is a separate Gradle build
from `../app/`.

## The one rule for this directory

**Reach the app only through its external contract** (`../app/contract/`):
HTTP/gRPC endpoints, Kafka topics + schemas, the CLI, or published artifacts.
**Never import an app internal class.** Generate clients from `../app/contract/`;
treat that directory as read-only here.

## What this harness does

- Applies the load profiles below against a running app instance.
- Captures latency (HdrHistogram), throughput, GC (unified GC logs / JFR),
  allocation rate, native memory (`jcmd VM.native_memory`), RSS, CPU.
- Emits one normalised `results.json` per run (core schema in the suite root;
  this benchmark's `mode_kpis` listed below).
- Renders a Grafana dashboard correlating GC/JIT with tail-latency spikes.

## Load generator

Kafka producer load generator (deterministic event stream) + end-to-end latency tracing that stamps ingress→final-sink time; concurrency sweep harness.

## Load profiles

- `ramp` — step concurrency from low to saturation.
- `storm` — sustained high-rate message burst.
- `partial-failure` — inject downstream slowness/errors to exercise retries + tails.

Every generator takes a **fixed seed** — runs must be reproducible.

## Mode-specific KPIs (added to the core KPI block)

- End-to-end flow completion rate (msg/sec ingress→sink).
- Context switches (OS + JFR) and memory footprint per service and per logical connection.
- Crossover analysis: concurrency level where Loom per-thread overhead starts to dominate vs reactive.

## Runtime registry

`runtimes/` holds the launch + flag definitions the harness can target.
**Currently `openjdk-hotspot-21` only.** GC presets come from
`jvm-flags/` (`g1.opts`, `zgc.opts`, `shenandoah.opts`, `throughput.opts`,
`lowlatency.opts`, shared `logging.opts`). Adding a future runtime (GraalVM,
native, CRaC) means adding an entry here — **never** editing app code.

> Current scope: Reactive vs Loom is the live axis. GraalVM comparison deferred. Loom is GA in 21 — no preview flags needed.

## Build & run

```bash
docker-compose up -d                 # external deps (pinned versions)
./gradlew build
./gradlew bench --args="--profile <profile> --runtime openjdk-hotspot-21 --gc G1 --runs 5"
```

Results land in `results/`. Compare runs with the suite-level comparison tooling
(reads the normalised `results.json`).

## Measurement protocol (enforced here)

Distinct warmup + measurement phases (recorded in `results.json`), stepped load
to capture the saturation curve, repeated runs (default 5), and a full env
descriptor (CPU, kernel, cgroup limits) attached to every result. Report
distributions, not single points.
