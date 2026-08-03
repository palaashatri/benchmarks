# Implementation status

Last audited: 2026-08-03.

## Readiness

**Overall classification: smoke/prototype-ready.**

- Tier 2 workloads: **0**
- Measurement-valid benchmark harnesses: **0**
- JDK 8–25 controller support: runtime discovery and planning implemented; full workload compatibility not yet implemented
- Publication-ready comparisons: **none**

The old committed results were removed because they mixed smoke traffic with hardcoded runtime metadata and load-generator JVM telemetry. They must not be used for JVM conclusions.

## Workload matrix

| # | Workload | Tier | Audited state |
|---|---|---:|---|
| 01 | Fintech ledger | 1 | Real H2/Hikari transaction prototype; dependency/build path and app telemetry still need Tier 2 work. |
| 02 | Microservices mesh | 1 | Multiple HTTP servers, but all services share one JVM/process. |
| 03 | Streaming analytics | 1 | In-memory queue/window processor; no real broker/state backend. |
| 04 | Vector/FFM inference | 1 | Real Vector API and FFM operations, tied to a modern JDK feature lane. |
| 05 | Dynamic/polyglot service | 1 | Rhino execution prototype; not a cross-runtime OpenJDK feature lane. |
| 06 | Massive chat | 0 | HTTP room/message simulation; no persistent massive connection fan-out. |
| 07 | Cold start | 1 | Child-process time-to-health prototype; no complete CDS/AppCDS matrix. |
| 08 | ETL batch | 1 | Real local NIO pipeline; lacks reference digests and Tier 2 telemetry. |
| 09 | ONNX inference | 0 | Java fallback executes even when ONNX classes are detected; real ONNX session missing. |
| 10 | Microservices fleet | 0 | Single-process service simulation; not independently restartable JVM fleet. |
| 11 | Autoscaling burst | 0 | Local thread-pool simulation; not process/Kubernetes capacity scaling. |
| 12 | HFT gateway | 0 | HTTP order-book prototype; not gRPC, not HdrHistogram, incomplete matching semantics. |
| 13 | Large monolith | 0 | 500 objects of one class; does not create the intended class/method/loading surface. |

## Foundation delivered on the repair branch

- Repository-wide generated-artifact cleanup and ignore rules.
- Single authoritative `AGENTS.md`; misleading root status/agent duplicates removed.
- Dependency-free `benchctl` controller.
- OpenJDK runtime and collector capability discovery.
- Experiment validation and matrix planning.
- Truthful smoke result envelopes and invalid-comparison refusal.
- Versioned experiment, runtime and result schemas.
- Workload catalog with audited tiers.
- Controller unit tests and CI validation.

## Highest-priority remaining work

1. Make benchmark 01 Tier 2 with application-process JFR/GC/NMT/CPU/RSS telemetry.
2. Add Java 8 compatibility artifacts and execute them on JDK 8–25.
3. Replace legacy closed-loop harnesses with a shared open-loop HdrHistogram load engine.
4. Add correctness/invariant tests for every workload.
5. Promote workloads individually; never mark all workloads complete based on smoke checks.
