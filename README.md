# OpenJDK Production Workload Benchmarks

This repository is an OpenJDK HotSpot workload and regression suite. It contains 13 production-shaped workload prototypes, one Java 8-bytecode compatibility workload, and a controller that discovers installed JDKs, probes supported collectors and runtime capabilities, plans valid experiment combinations, and keeps smoke output separate from measurement-valid benchmark output.

## Current readiness

Three workloads are **Tier 2** (measurement-ready in code): `01-fintech-ledger`, `09-onnx-inference` and `12-hft-trading-gateway`. Each runs through the same engine, which launches the application JVM, drives it with the shared open-loop HdrHistogram generator, collects application-process telemetry, and applies statistical and host-preflight validity gates. A passing process exit is not sufficient: `measurement_valid` stays `false` unless every gate passes, and no measurement-valid results exist yet. Benchmark execution waits for controlled hardware.

All other workloads are honest Tier 0/Tier 1 prototypes. Their `run.sh test` entry points and Maven lifecycle verify contracts and correctness, not publication-grade performance.

See `IMPLEMENTATION_STATUS.md` and `METHODOLOGY.md` for the audited per-workload state.

## Scope

- OpenJDK HotSpot only.
- JDK 8 through JDK 25 runtime discovery and experiment planning.
- A Java 8-bytecode compatibility lane that builds one hashed JAR per experiment and reuses that exact artifact across JDK 8–25.
- Runtime-supported Serial, Parallel, CMS, G1, ZGC, Shenandoah and Epsilon collectors.
- Startup, warm-up, JIT, code cache, GC, allocation, memory, concurrency, Vector API, FFM and container behaviour.
- No proprietary JVM, compiler-service or confidential product integration.

## Quick start

```bash
./benchctl doctor
./benchctl discover-runtimes
./benchctl list-runtimes
./benchctl list-workloads
./benchctl plan experiments/quick.yaml
./benchctl run experiments/quick.yaml
```

The experiment files use JSON syntax because JSON is valid YAML and lets the controller remain dependency-free.

Measurement, once a controlled host is available:

```bash
./benchctl preflight                         # the host must pass every check
./benchctl fetch-jdks --feature 21           # OpenJDK 21 from the Ubuntu archive, signature-checked, into .benchctl/jdks
./benchctl validate experiments/tier2-workloads.yaml
./benchctl plan experiments/tier2-workloads.yaml
./benchctl run experiments/tier2-workloads.yaml
./benchctl report results/<run-id>           # readable HTML report with charts
```

Each application and harness builds with its own Maven wrapper, pinned to Maven 3.9.11 and verified by SHA-256:

```bash
(cd benchmarks/01-fintech-ledger/app && ./mvnw -B test)
(cd benchmarks/01-fintech-ledger/harness && ./mvnw -B test)
```

The compatibility workload can also be tested directly:

```bash
(cd benchmarks/00-runtime-compatibility/app && ./run.sh test)
(cd benchmarks/00-runtime-compatibility/harness && ./run.sh test)
```

Its classes target Java 8 bytecode. During a `benchctl` experiment, the controller selects one discovered JDK with `javac`, builds the compatibility JAR once, stores its SHA-256 digest, and passes the same absolute artifact path to every selected runtime/collector combination.

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
python3 -m unittest discover -s tools/tests -v
python3 tools/check_repository.py
./benchctl validate experiments/quick.yaml
./benchctl validate experiments/standard.yaml
./benchctl validate experiments/ledger-benchmark.yaml
./benchctl validate experiments/tier2-workloads.yaml
./benchctl discover-runtimes
bash tools/loadgen/run.sh test
bash tools/workload_tests/run_common.sh
```

Workload-specific `run.sh` files remain compatibility smoke entry points. They are not authoritative benchmark orchestration except through `benchctl`.
