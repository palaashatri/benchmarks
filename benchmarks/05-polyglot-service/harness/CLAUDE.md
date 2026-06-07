# CLAUDE.md — Polyglot Service vs Pure-Java Baseline · HARNESS


> **Current implementation status:** this directory currently contains a local smoke-test harness scaffold, not the full load-generation and observability harness described below. The scaffold exists so `run.sh build` / `run.sh test` can produce schema-shaped smoke results. The full harness described in this file remains the target and should replace the scaffold incrementally. See `../../../IMPLEMENTATION_STATUS.md`.

This is the **benchmark harness** for benchmark 05. Its job is to drive the
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

HTTP load generator driving the API; identical load applied to baseline now and to polyglot later for an apples-to-apples delta.

## Load profiles

- `baseline` — pure-Java under stepped load (active now).
- `polyglot` — same load against the polyglot engine (defined, runs in the GraalVM phase).

Every generator takes a **fixed seed** — runs must be reproducible.

## Mode-specific KPIs (added to the core KPI block)

- Baseline throughput/latency/GC (now).
- (Deferred) polyglot vs baseline throughput/latency delta, footprint attributable to polyglot layers, time-until-engine-hot.

## Runtime registry

`runtimes/` holds the launch + flag definitions the harness can target.
**Currently `openjdk-hotspot-21` only.** GC presets come from
`jvm-flags/` (`g1.opts`, `zgc.opts`, `shenandoah.opts`, `throughput.opts`,
`lowlatency.opts`, shared `logging.opts`). Adding a future runtime (GraalVM,
native, CRaC) means adding an entry here — **never** editing app code.

> Current scope: This benchmark is GraalVM-defined. OpenJDK-now delivers: pure-Java baseline (shippable), the adapter seam, and the harness. The GraalVM polyglot impl + runtime are a separate phase and must NOT add `org.graalvm.*` to app/ until then.

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
