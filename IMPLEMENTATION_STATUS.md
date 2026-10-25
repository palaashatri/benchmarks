# Implementation status

Last audited: 2026-09-30; workload table updated by PR #5; repository re-checked 2026-10-25 (gRPC for 12 and the ONNX session for 09 added; see the workload table).

## Readiness

**Overall classification: measurement-ready for benchmark 01; smoke/prototype-ready for all other workloads.**

- Tier 2 workloads: **3** (`01-fintech-ledger`, `09-onnx-inference`, `12-hft-trading-gateway`); all three use the same measurement engine. Only `01` has been calibration-tested on this host; none has a measurement-valid result.
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
| 09 | ONNX inference | 2 | Seeded classifier built as an ONNX graph and run by a real ONNX Runtime 1.18.0 session in the Maven lane (`mvn test` checks it against the Java reference); the dependency-free smoke build reports the Java fallback. |
| 10 | Microservices fleet | 1 | Five independent replica JVMs; readiness-gated replacement, generation/PID identity and concurrent-deploy checks. |
| 11 | Autoscaling burst | 1 | Bounded gateway admission and queued work across one to four worker JVMs; automatic backlog scale-up, idle scale-down and failure accounting. |
| 12 | HFT gateway | 2 | Synthetic symbol-isolated FIFO/partial-fill engine shared by HTTP, persistent framed TCP and a real grpc-java listener; gRPC is verified by the Maven gate (`mvn test`), not by measurement runs. |
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
3. Add shared measurement integration to 09 and 12 before Tier 2 promotion. Real gRPC (12) and real ONNX Runtime sessions (09) are in place.
4. Keep publication claims out of documentation until a Tier 3 controlled-environment run exists.

## Known issues

- Workloads use three build paths: Gradle Kotlin DSL (01–08), Maven (09–13) and `run.sh` scripts that compile with raw `javac`. None has a wrapper; adding one is open work.
- The `run.sh` downloads (01, 05, `tools/loadgen`) are SHA-256 pinned. Gradle (01–08) and Maven (09–13) resolve through their own tooling and have no checksum verification metadata yet.
- `gradle test` for 01–08 has no test sources, so it passes without checking anything. Their correctness checks run through `run.sh test`; a Gradle-native test lane is open work.
- Maven Central through the development proxy intermittently returns 404 or 429 for artifacts that exist. Retries (`curl --retry`, Gradle `--refresh-dependencies`, `mvn -U`) absorb most of it; a full clean build can still need a second attempt.

## Product roadmap

What would make the suite useful to someone deciding between JDKs or collectors, in priority order:

1. **First published, measurement-valid result set** for `01-fintech-ledger` on a quiet machine (pinned CPU frequency, no other load), with the environment fingerprint, raw data and `benchctl compare` output checked in under a dated `published/` directory.
2. **Charts in the readable report:** `benchctl report` now renders tables (aggregate statistics with confidence intervals, repetitions, environment, and a comparison of measurement-valid results). Charts for throughput and latency per JDK × GC, and GC pause and RSS summaries, remain.
3. **One-command matrix runs:** `benchctl` fetching the JDK builds a plan needs (for example via the Foojay Disco API), so a user doesn't install eight JDKs by hand.
4. **A second Tier 2 workload.** `02-microservices-mesh` now runs real separate JVMs and is the closest candidate; port the benchmark 01 measurement engine to it.
5. **One build system with a wrapper** for every workload, and SHA-256 checks for every downloaded artifact.
6. **Measurement integration for 09 and 12**: a phased open-loop run with application-process telemetry, then promote them to Tier 2 (both now have real protocol paths verified in the Maven lane).
