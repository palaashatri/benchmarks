# OpenJDK Production Workload Benchmarks

This repository is an OpenJDK HotSpot workload and regression suite. It contains 13 production-shaped workload prototypes, one Java 8-bytecode compatibility workload, and a controller that discovers installed JDKs, probes supported collectors and runtime capabilities, plans valid experiment combinations, and keeps smoke output separate from measurement-valid benchmark output.

## Current readiness

The repository is **smoke/prototype-ready**. No workload is currently Tier 2, therefore `benchctl` deliberately refuses publication-style benchmark execution. Existing throughput and latency numbers are diagnostic only until application-process telemetry, warm-up phases, open-loop load and statistical gates are implemented.

See `IMPLEMENTATION_STATUS.md` for the audited per-workload state.

## Scope

- OpenJDK HotSpot only.
- JDK 8 through JDK 25 runtime discovery and experiment planning.
- A Java 8-bytecode compatibility lane that runs unchanged source semantics across JDK 8–25.
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

The compatibility workload can also be tested directly:

```bash
(cd benchmarks/00-runtime-compatibility/app && ./run.sh test)
(cd benchmarks/00-runtime-compatibility/harness && ./run.sh test)
```

Its classes target Java 8 bytecode. The current smoke script recompiles the same sources with each selected toolchain; a future measurement lane must build one hashed artifact once and reuse that exact artifact across all runtime runs.

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

Unknown measurements are `null`, never fake zeroes. `benchctl compare` rejects invalid results.

## Development checks

```bash
python3 -m unittest discover -s tools/tests -v
python3 tools/check_repository.py
./benchctl validate experiments/quick.yaml
./benchctl validate experiments/standard.yaml
./benchctl discover-runtimes
```

Workload-specific `run.sh` files remain compatibility smoke entry points. They are not authoritative benchmark orchestration.
