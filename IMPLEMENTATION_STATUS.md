# Implementation status

Last audited: 2026-09-30.

## Readiness

**Overall classification: measurement-ready for benchmark 01; smoke/prototype-ready for all other workloads.**

- Tier 2 workloads: **1** (`01-fintech-ledger`)
- Measurement-valid published result sets: **0** (each run still has to pass validity gates)
- JDK 8–25 controller support: runtime discovery, collector probing, implementation-band filtering and planning implemented
- Compatibility lane: Java 8-bytecode smoke workload implemented; one hashed JAR is reused across all selected JDK/GC runs
- Publication-ready hardware comparisons: **none**

The old committed results were removed because they mixed smoke traffic with hardcoded runtime metadata and load-generator JVM telemetry. They must not be used for JVM conclusions.

## Workload matrix

| # | Workload | Tier | Audited state |
|---|---|---:|---|
| 00 | Runtime compatibility | 1 | Deterministic Java 8-bytecode sort/compress/hash workload; `benchctl` builds one JAR and records/reuses its SHA-256 digest across runtimes. |
| 01 | Fintech ledger | 2 | Real H2/Hikari transactions, isolated process identity, balance-conservation checks, application-process JFR/GC/NMT/CPU/RSS telemetry, open-loop HdrHistogram load and statistical gates. |
| 02 | Microservices mesh | 1 | Gateway plus three independent service JVMs; actual account/transaction/notification HTTP calls and child identity/readiness/cleanup checks. |
| 03 | Streaming analytics | 1 | In-memory queue/window processor; no real broker/state backend. |
| 04 | Vector/FFM inference | 1 | JDK 21 preview Vector API and FFM paths with scalar/SIMD equivalence smoke checks. |
| 05 | Dynamic/polyglot service | 1 | Rhino execution prototype; not a cross-runtime OpenJDK feature lane. |
| 06 | Massive chat | 1 | Persistent virtual-thread TCP subscriptions and room fan-out; bounded outbound queues, real socket delivery counters and disconnect cleanup. |
| 07 | Cold start | 1 | Isolated child-process time-to-health with runtime-token verification; no complete CDS/AppCDS matrix. |
| 08 | ETL batch | 1 | Real local NIO pipeline; lacks reference digests and Tier 2 telemetry. |
| 09 | ONNX inference | 0 | Truthful deterministic Java fallback; never reports ONNX active without a real session. |
| 10 | Microservices fleet | 1 | Five independent replica JVMs; readiness-gated replacement, generation/PID identity and concurrent-deploy checks. |
| 11 | Autoscaling burst | 1 | Bounded gateway admission and queued work across one to four worker JVMs; automatic backlog scale-up, idle scale-down and failure accounting. |
| 12 | HFT gateway | 1 | Synthetic symbol-isolated FIFO/partial-fill engine shared by HTTP and persistent framed TCP; gRPC remains inactive. |
| 13 | Large monolith | 0 | Creates 500 distinct generated proxy classes and labels JIT compilation time correctly; still not an enterprise monolith. |

## Foundation delivered

- Repository-wide generated-artifact cleanup and ignore rules.
- Single authoritative `AGENTS.md`.
- Public-scope hygiene gate rejects proprietary/runtime-out-of-scope terminology.
- Dependency-free `benchctl` controller.
- OpenJDK runtime and collector capability discovery.
- Workload JDK compatibility bands and invalid-combination skipping.
- Experiment validation, matrix planning, smoke orchestration and Tier 2 ledger execution.
- Truthful smoke result envelopes and invalid-comparison refusal.
- Versioned experiment, runtime and result schemas.
- Workload catalog with audited tiers.
- Controller unit tests and JDK 8/11/17/21/25 discovery CI.
- Compatibility smoke CI on JDK 8 and JDK 25.
- Correctness smoke checks for every numbered workload.
- Shared open-loop HdrHistogram load generator.
- Statistical comparison using median throughput and bootstrap confidence-interval overlap.

## Remaining work after this completion pass

1. Produce measurement-valid ledger result sets on controlled hardware (current CI is not a quiet machine).
2. Port the shared measurement engine from benchmark 01 to other workloads only after their claimed architecture exists.
3. Implement real gRPC for HFT and real ONNX sessions; add workload-specific correctness and shared measurement integration before Tier 2 promotion.
4. Keep publication claims out of documentation until a Tier 3 controlled-environment run exists.
