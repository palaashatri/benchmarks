# OpenJDK Production Workload Benchmarks

This repository is an OpenJDK HotSpot workload and regression suite. It contains 13 production-shaped workloads, one Java 8-bytecode compatibility workload, and a Java controller that discovers installed JDKs, probes supported collectors and runtime capabilities, plans valid experiment combinations, and keeps smoke output separate from measurement-valid benchmark output.

It runs the same way on Linux, Windows and macOS. The only requirements are a JDK 17 or newer and git. There is no Python, and no shell logic beyond the two thin launchers.

## Current readiness

Eleven workloads are catalogued **Tier 2** (measurement-ready in code): `00` through `05` and `09` through `13`. The Tier 2 engine launches each application JVM, drives it with the shared open-loop HdrHistogram generator, collects application-process telemetry, and applies statistical and host-preflight validity gates. A passing process exit is not sufficient: `measurement_valid` stays `false` unless every gate passes. No measurement-valid results exist yet, and benchmark execution waits for controlled hardware.

Workloads `02`, `10` and `11` use process-tree telemetry: the gateway and every child JVM are sampled, logged and captured. Each child writes its own native memory summary and flight recording as it exits. Workloads `06`, `07` and `08` are Tier 1. They run five repetitions through the controller's lanes (`benchctl lane chat|startup|jobs`) under the same gates as the Tier 2 engine, and their envelopes stay invalid until they are promoted.

See `IMPLEMENTATION_STATUS.md` and `METHODOLOGY.md` for the audited per-workload state.

## Scope

- OpenJDK HotSpot only.
- JDK 8 through JDK 25 runtime discovery and experiment planning.
- A Java 8-bytecode compatibility lane that builds one hashed JAR per experiment and reuses that exact artifact across JDK 8–25.
- Runtime-supported Serial, Parallel, CMS, G1, ZGC, Shenandoah and Epsilon collectors.
- Startup, warm-up, JIT, code cache, GC, allocation, memory, concurrency, Vector API, FFM and container behaviour.
- No proprietary JVM, compiler-service or confidential product integration.

## Quick start

Linux and macOS:

```bash
./benchctl doctor
./benchctl discover-runtimes
./benchctl list-runtimes
./benchctl list-workloads
./benchctl plan experiments/quick.yaml
./benchctl run experiments/quick.yaml
```

Windows (PowerShell or cmd):

```bat
benchctl.cmd doctor
benchctl.cmd discover-runtimes
```

The first call builds the controller with the Maven wrapper; later calls reuse the jar. The experiment files use JSON syntax because JSON is valid YAML, so the controller needs no YAML library.

Measurement, once a controlled host is available:

```bash
./benchctl preflight                         # the host must pass every check
./benchctl fetch-jdks --feature 17 --feature 21   # Adoptium builds, SHA-256 checked, into .benchctl/jdks
./benchctl validate experiments/tier2-matrix.yaml
./benchctl plan experiments/tier2-matrix.yaml
./benchctl run experiments/tier2-matrix.yaml
./benchctl report results/<run-id>           # readable HTML report with charts
```

Each application and harness builds with its own Maven wrapper, pinned to Maven 3.9.11 and verified by SHA-256:

```bash
(cd benchmarks/01-fintech-ledger/app && ./mvnw -B test)
(cd benchmarks/01-fintech-ledger/harness && ./mvnw -B test)
```

The compatibility workload targets Java 8 bytecode and can be tested directly on any JDK 8 or newer:

```bash
(cd benchmarks/00-runtime-compatibility/app && ./mvnw -B test)
(cd benchmarks/00-runtime-compatibility/harness && ./mvnw -B test)
```

During a `benchctl` experiment, the controller selects one discovered JDK, builds the compatibility JAR once, stores its SHA-256 digest, and passes the same absolute artifact path to every selected runtime/collector combination.

## Result safety

Every normalized result carries:

```json
{
  "run_kind": "smoke",
  "implementation_tier": "tier-1",
  "measurement_valid": false,
  "invalid_reasons": ["..."],
  "warnings": ["..."]
}
```

Unknown measurements are `null`, never fake zeroes. `benchctl compare` rejects invalid results and treats overlapping bootstrap confidence intervals as inconclusive.

## Development checks

```bash
./mvnw -B -f tools/benchctl/pom.xml test     # controller unit tests
./benchctl hygiene                           # repository hygiene and scope gate
./benchctl validate experiments/quick.yaml
./benchctl validate experiments/standard.yaml
./benchctl validate experiments/gc-focused.yaml
./benchctl validate experiments/ledger-benchmark.yaml
./benchctl validate experiments/tier2-matrix.yaml
./benchctl discover-runtimes
./mvnw -B -f benchmarks/common/pom.xml test  # shared lifecycle and framed-TCP regressions
./mvnw -B -f tools/loadgen/pom.xml test      # open-loop load generator self-test
```

Workload `mvnw test` commands are listed under Quick start. CI runs the controller, shared modules and every workload on Ubuntu, Windows and macOS.

## Standalone benchmark tooling and Raspberry Pi efficiency

**Benchmarks is excluded from SLOPOS/PARAMA bundled desktop applications and the curated first-party Software catalog**, including optional developer-app entries. It remains an independent command-line/controller and test-workload project; no desktop launcher, OS-specific UI or PARAMA port is planned by this requirement. It can run on any platform independently meeting its documented Java/runtime requirements.

A future Raspberry Pi Zero 2 W **edge-small** measurement profile should reduce harness overhead, bound logs and memory, report thermal throttling and JVM/GC support accurately, and preserve a reproducible result. These are **not current verified Pi capabilities**. See [AGENTS.md](AGENTS.md#independent-benchmark-tooling-and-low-memory-efficiency).

