# Implementation status

Last audited: 2026-08-03.

## Readiness

**Overall classification: smoke/prototype-ready.**

- Tier 2 workloads: **0**
- Measurement-valid benchmark harnesses: **0**
- JDK 8–25 controller support: runtime discovery, collector probing, implementation-band filtering and planning implemented
- Compatibility lane: Java 8-bytecode smoke workload implemented; one hashed JAR is reused across all selected JDK/GC runs
- Publication-ready comparisons: **none**

The old committed results were removed because they mixed smoke traffic with hardcoded runtime metadata and load-generator JVM telemetry. They must not be used for JVM conclusions.

## Workload matrix

| # | Workload | Tier | Audited state |
|---|---|---:|---|
| 00 | Runtime compatibility | 1 | Deterministic Java 8-bytecode sort/compress/hash workload; `benchctl` builds one JAR and records/reuses its SHA-256 digest across runtimes. |
| 01 | Fintech ledger | 1 | Real H2/Hikari transactions, isolated process identity and balance-conservation checks; Tier 2 app telemetry still pending. |
| 02 | Microservices mesh | 1 | Multiple HTTP servers, but all services share one JVM/process. |
| 03 | Streaming analytics | 1 | In-memory queue/window processor; no real broker/state backend. |
| 04 | Vector/FFM inference | 1 | JDK 21 preview Vector API and FFM paths with scalar/SIMD equivalence smoke checks. |
| 05 | Dynamic/polyglot service | 1 | Rhino execution prototype; not a cross-runtime OpenJDK feature lane. |
| 06 | Massive chat | 0 | Subscriber-aware HTTP room simulator; deliveries are explicitly simulated and persistent connection count is zero. |
| 07 | Cold start | 1 | Isolated child-process time-to-health with runtime-token verification; no complete CDS/AppCDS matrix. |
| 08 | ETL batch | 1 | Real local NIO pipeline; lacks reference digests and Tier 2 telemetry. |
| 09 | ONNX inference | 0 | Truthful deterministic Java fallback; never reports ONNX active without a real session. |
| 10 | Microservices fleet | 0 | Single-process service simulation; not independently restartable JVM fleet. |
| 11 | Autoscaling burst | 0 | Local thread-pool simulation; not process/Kubernetes capacity scaling. |
| 12 | HFT gateway | 0 | HTTP order-book prototype; not gRPC, not HdrHistogram, and matching semantics still require repair. |
| 13 | Large monolith | 0 | Creates 500 distinct generated proxy classes and labels JIT compilation time correctly; still not an enterprise monolith. |

## Foundation delivered on the repair branch

- Repository-wide generated-artifact cleanup and ignore rules.
- Single authoritative `AGENTS.md`; duplicated per-benchmark agent instructions removed.
- Public-scope hygiene gate rejects proprietary/runtime-out-of-scope terminology.
- Dependency-free `benchctl` controller.
- OpenJDK runtime and collector capability discovery.
- Workload JDK compatibility bands and invalid-combination skipping.
- Experiment validation and matrix planning.
- Truthful smoke result envelopes and invalid-comparison refusal.
- All legacy load-generator JVM/OS metrics are discarded during normalization.
- Versioned experiment, runtime and result schemas.
- Workload catalog with audited tiers.
- Controller unit tests and JDK 8/11/17/21/25 discovery CI.
- Compatibility smoke CI on JDK 8 and JDK 25.
- Correctness smoke checks for repaired ledger, Vector/FFM, chat, cold-start, fallback inference and class-loading prototypes.

## Highest-priority remaining work

1. Make benchmark 01 Tier 2 with application-process JFR/GC/NMT/CPU/RSS telemetry.
2. Replace legacy closed-loop harnesses with a shared open-loop HdrHistogram load engine.
3. Add per-repetition statistics, steady-state detection and controlled environment comparison gates.
4. Repair HFT symbol/partial-fill correctness and implement real gRPC before promotion.
5. Add correctness/invariant tests for every remaining workload.
6. Promote workloads individually; never mark all workloads complete based on smoke checks.
