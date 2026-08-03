# AGENTS.md — OpenJDK benchmark suite

This is the single authoritative development specification for the repository.

## Mission

Build a credible OpenJDK HotSpot production-workload and regression suite covering JDK 8 through JDK 25, supported garbage collectors, startup/warm-up, JIT/code-cache behaviour, allocation, memory, concurrency, native interfaces, Vector API, container behaviour and realistic application operations.

## Scope boundary

This public repository is OpenJDK-only. Do not add proprietary JVMs, proprietary collectors, vendor-private compiler services, confidential features, customer systems or private performance results. Do not add speculative public SPIs for confidential systems.

## Non-negotiable truth rules

1. Smoke output is never benchmark output.
2. Unknown data is `null` or unavailable with a reason, never numeric zero.
3. Telemetry must identify the measured application process, not the load generator.
4. Runtime, collector and JVM flags come from the launched process/configuration, never hardcoded labels.
5. A result is measurement-valid only after correctness, telemetry, phase and statistical gates pass.
6. Do not mark a workload done because it compiles or serves HTTP.

Every result requires `run_kind`, `implementation_tier`, `measurement_valid`, `invalid_reasons` and `warnings`.

## Architecture

- `benchctl`: controller, runtime discovery, capability probing, planning, execution, validation, comparison and reporting.
- `benchmarks/`: independent applications and harnesses. Application code never imports harness code. Harnesses communicate only through external contracts.
- `schemas/`: versioned experiment/runtime/result schemas.
- `experiments/`: reproducible manifests.
- `results/`: generated and gitignored.

## Runtime matrix

Discover actual OpenJDK HotSpot installations and probe their capabilities. Support multiple builds of the same JDK. Plan JDK 8–25 combinations without assuming every JDK or collector exists.

Required collector probes where available: Serial, Parallel, CMS, G1, ZGC, Shenandoah and Epsilon. Never apply unsupported flags blindly.

Maintain two lanes:

- Java 8 bytecode compatibility lane for cross-version runtime comparison.
- Feature lane for modules, CDS/AppCDS, records, sealed classes, virtual threads, Vector API, FFM, generational ZGC, compact headers and preview features when actually supported.

## Measurement requirements

Tier 2 requires:

- deterministic workload data and correctness invariants;
- separate warm-up, measurement and cooldown phases;
- open-loop load and coordinated-omission-safe histograms;
- at least five repetitions;
- raw results plus statistical aggregation;
- application-process GC/JIT/code-cache/CPU/RSS/native-memory telemetry;
- environment and artifact fingerprints;
- comparison validity checks.

Shared measurement infrastructure must be implemented once, not copied across 13 harnesses.

## Workload policy

Keep current implementations as Tier 0/Tier 1 prototypes until evidence supports promotion. Complete benchmark 01 as the reference Tier 2 workload before cloning measurement patterns elsewhere. Production-shaped implementations must exercise the behaviour they claim: real gRPC for HFT, real ONNX sessions for ONNX, separate processes for fleet/mesh claims, persistent connections for massive chat, and a real broad class graph for the monolith.

## Build and test policy

- Never commit build outputs, downloaded dependencies, logs, PIDs, JFRs or generated results.
- A command named `test` must execute tests.
- Do not impersonate Gradle Wrapper with a custom `javac` script.
- Pin dependencies and tool versions.
- Run controller unit tests, schema/manifest validation and appropriate workload correctness tests after changes.

## Definition of done

The suite is not complete until installed JDK 8–25 runtimes can be discovered, supported combinations are planned safely, benchmark 01 is Tier 2, all workloads have honest tiers and correctness tests, telemetry belongs to the application process, comparisons are statistically defensible, CI passes, and documentation matches what was actually executed.
