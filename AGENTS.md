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

Keep current implementations as Tier 0/Tier 1 prototypes until evidence supports promotion. Benchmark 01 is the reference Tier 2 workload; clone its measurement engine only after another workload has equivalent correctness, process identity and telemetry. Production-shaped implementations must exercise the behaviour they claim: real gRPC for HFT, real ONNX sessions for ONNX, separate processes for fleet/mesh claims, persistent connections for massive chat, and a real broad class graph for the monolith.

## Build and test policy

- Never commit build outputs, downloaded dependencies, logs, PIDs, JFRs or generated results.
- A command named `test` must execute tests.
- Do not impersonate Gradle Wrapper with a custom `javac` script.
- Pin dependencies and tool versions.
- Run controller unit tests, schema/manifest validation and appropriate workload correctness tests after changes.

## Definition of done

The suite is complete at the public OpenJDK-controller level when installed JDK 8–25 runtimes can be discovered, supported combinations are planned safely, benchmark 01 is Tier 2, all workloads have honest tiers and correctness tests, telemetry belongs to the application process, comparisons are statistically defensible, CI passes, and documentation matches what was actually executed. Publication-grade numbers still require controlled hardware and a measurement-valid result set.

## PR 5 workload architecture completion

Intent: finish the executable architectures promised by PR 5 while preserving the existing external HTTP contracts and OpenJDK-only boundary. These workloads remain Tier 1: this change does not add multi-process measurement orchestration, ONNX, grpc-java, or Kubernetes.

Shared application utilities belong in `benchmarks/common`: JSON encoding, process identity/port publication, an owned child-JVM lifecycle, and bounded persistent TCP sessions. They must compile on Java 17 and never import harness code. Each workload keeps its own application state. All listeners bind loopback; children use the parent's Java executable and independently bound ephemeral ports. Child readiness must verify PID, unique run token, and role. Failed startup, redeploy and parent shutdown must reap owned children and delete their temporary readiness artifacts. Child heap sizing is explicit and child flags are reported; Tier 1 traffic is not a runtime performance comparison.

Implementation plan (executed in this PR):

- [x] Shared lifecycle: tests for inherited PORT, failed readiness, malformed/wrong process identity, cleanup and concurrent launches; implement common utilities and wire all five build entry points.
- [x] Mesh: gateway plus account, transaction and notification JVMs; verify three distinct child PIDs, actual inter-service transactions/events, failure health and parent cleanup.
- [x] Fleet: five replica JVMs with inventory state and independent readiness; publish a ready replacement before retiring the old generation; verify one replica's PID changes and other replicas remain available.
- [x] Autoscaling: bounded gateway admission and queued work dispatched to real replicas; automatic scale-up from backlog and idle scale-down without losing accepted work; verify process count, completion, bounds and shutdown.
- [x] Chat: virtual-thread sessions, newline-framed JSON, room subscriptions, persistent delivery, bounded outbound queues and disconnect cleanup; verify isolation, repeated messages, malformed frames and actual socket deliveries.
- [x] Trading: persistent newline-framed JSON SubmitOrder/CancelOrder/GetOrderStatus sharing the HTTP order book; verify symbol isolation, partial fills, ordering, invalid frames and transport metadata. gRPC remains inactive.
- [x] Integration: align catalog, manifests, contracts, implementation notes and CI; run controller tests, repository hygiene, manifest checks, lifecycle tests and workload correctness on supported JDKs; review and push to PR 5.

Review focus: partial startup after an earlier child becomes ready; an inherited fixed PORT; child death during traffic; simultaneous fleet deploys; slow/disconnected TCP readers and oversized frames. Tests must exercise real process/socket behavior, with bounded waits and unconditional cleanup.
