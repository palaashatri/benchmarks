# OpenJDK Production Workload Benchmarks

This repository is an OpenJDK HotSpot workload and regression suite. It contains 13 production-shaped workload prototypes, one Java 8-bytecode compatibility workload, and a controller that discovers installed JDKs, probes supported collectors and runtime capabilities, plans valid experiment combinations, and keeps smoke output separate from measurement-valid benchmark output.

## Current readiness

Benchmark `01-fintech-ledger` is the reference **Tier 2** workload. `benchctl run experiments/ledger-benchmark.yaml` launches the application JVM, drives it with the shared open-loop HdrHistogram generator, collects application-process telemetry, and applies statistical validity gates. A passing process exit is not sufficient: `measurement_valid` remains `false` unless those gates pass.

All other workloads are honest Tier 0/Tier 1 prototypes. Their `run.sh test` entry points verify contracts and correctness, not publication-grade performance.

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

Measurement of the reference ledger workload:

```bash
./benchctl validate experiments/ledger-benchmark.yaml
./benchctl plan experiments/ledger-benchmark.yaml
./benchctl run experiments/ledger-benchmark.yaml
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
./benchctl discover-runtimes
bash tools/loadgen/run.sh test
```

Workload-specific `run.sh` files remain compatibility smoke entry points. They are not authoritative benchmark orchestration except through `benchctl`.
