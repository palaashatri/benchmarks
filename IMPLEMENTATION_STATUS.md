# Implementation status

Last audited: 2026-09-30. Code-completion pass re-checked 2026-10-25: the Tier 2 measurement engine is generic, the build is unified, and the controller covers JDK fetching, host preflight and report charts.

## Readiness

**Overall classification: the code for the Tier 2 measurement path is complete for workloads 01, 09, 12 and 13. Workloads 02–08, 10 and 11 are Tier 1 prototypes and are not yet at their documented design. No measurement-valid results exist yet. Benchmark execution waits for controlled hardware.**

- Tier 2 workloads: **4** (`01-fintech-ledger`, `09-onnx-inference`, `12-hft-trading-gateway`, `13-large-monolith`). All four run through the same engine, `tools/run_tier2_experiment.py` with `tools/loadgen`, and each declares its contract in `benchmarks/<id>/tier2.json`.
- Calibration on this host (not a benchmark): 01, 09 and 12 complete two repetitions with zero request errors, identity verified and warm-up stable. 13 completed a five-second calibration repetition at 50 req/s with zero errors. Every envelope is `measurement_valid: false`, because the host fails preflight (the CPU governor is not observable here, and this sandbox injects `JAVA_TOOL_OPTIONS`) and because the calibration run kind is not a benchmark.
- Measurement-valid published result sets: **0**.
- JDK 8–25 controller support: runtime discovery, collector probing, implementation-band filtering, planning and verified JDK fetching (`benchctl fetch-jdks`).
- Build: one system for every workload. Applications and harnesses use Maven, each with a `./mvnw` wrapper pinned to Maven 3.9.11 and verified by SHA-256. Workload 00 keeps its `run.sh` lane.
- Publication-ready hardware comparisons: **none**.

The old committed results were removed because they mixed smoke traffic with hardcoded runtime metadata and load-generator JVM telemetry. They must not be used for JVM conclusions.

## Workload matrix

| # | Workload | Tier | Audited state |
|---|---|---:|---|
| 00 | Runtime compatibility | 1 | Deterministic Java 8-bytecode sort/compress/hash workload; `benchctl` builds one JAR and records/reuses its SHA-256 digest across runtimes. |
| 01 | Fintech ledger | 2 | Real H2/Hikari transactions, isolated process identity, balance-conservation checks, application-process JFR/GC/NMT/CPU/RSS telemetry, open-loop HdrHistogram load, warm-up and statistical gates. Reference Tier 2 workload. |
| 02 | Microservices mesh | 1 | Gateway plus three independent service JVMs; actual account/transaction/notification HTTP calls and child identity/readiness/cleanup checks. Not yet on the Tier 2 engine: its telemetry spans several JVMs. |
| 03 | Streaming analytics | 1 | In-memory queue/window processor; no real broker/state backend. |
| 04 | Vector/FFM inference | 1 | JDK 21 preview Vector API and FFM paths with scalar/SIMD equivalence smoke checks. |
| 05 | Dynamic/polyglot service | 1 | Rhino execution prototype; not a cross-runtime OpenJDK feature lane. |
| 06 | Massive chat | 1 | Persistent virtual-thread TCP subscriptions and room fan-out; bounded outbound queues, real socket delivery counters and disconnect cleanup. |
| 07 | Cold start | 1 | Child-JVM process-to-health under the default class-data archive, with CDS off, and with a dynamic AppCDS archive built from a JAR at startup. Each mode is probed; the application records the times. Not yet a controller startup benchmark (no per-sample restart, repetitions or preflight). |
| 08 | ETL batch | 1 | Real local NIO pipeline. Each job's canonical output is checked by SHA-256 against an independent in-memory reference, and a mismatch fails the job. Unfinished jobs report null, not zero. Not on the Tier 2 engine: its load is job submission, not request traffic. |
| 09 | ONNX inference | 2 | Seeded classifier built as an ONNX graph and run by a live ONNX Runtime 1.18.0 session (Maven lane). `mvn test` checks it against the Java reference, with max deviation 2.4e-7 over 1,000 inputs. The Tier 2 engine measures the live session (`mode: onnx-runtime`). The dependency-free smoke build reports the Java fallback. |
| 10 | Microservices fleet | 1 | Five independent replica JVMs; readiness-gated replacement, generation/PID identity and concurrent-deploy checks. |
| 11 | Autoscaling burst | 1 | Bounded gateway admission and queued work across one to four worker JVMs; automatic backlog scale-up, idle scale-down and failure accounting. |
| 12 | HFT gateway | 2 | Synthetic symbol-isolated FIFO/partial-fill engine shared by HTTP, persistent framed TCP and a grpc-java listener. `mvn test` makes eight real gRPC calls and reads the same orders back over HTTP. The Tier 2 engine measures the HTTP order path. |
| 13 | Large monolith | 2 | Generates 1,000 components (20 modules of 50) at build time. A reflective constructor-injection container wires them dependencies-first and every request traverses the whole graph. The result is checked against an independent Python model of the wiring. A synthetic container, not a production framework. Tier 2 engine. |

## Measurement engine (Tier 2)

- **Contract:** `benchmarks/<id>/tier2.json` declares the build, correctness gate, launch class, identity endpoint, token prefix, classpath and request mix. The JDK band and tier come from `catalog.json`.
- **Load:** `tools/loadgen` (`OpenLoopLoad`) is an open-loop, coordinated-omission-corrected generator. The request mix is a TSV contract file with weighted templates. Its output is a load envelope with no validity claim.
- **Gates:** correctness gate; identity (pid and run token) checked before load; zero request errors; every scheduled request completed; throughput stable inside warm-up (5 s window, CV ≤ 5 %); host preflight before and after; actual collector equals requested; repetition spread; at least five repetitions for a benchmark.
- **Outliers:** modified z-score and Tukey fences, reported with repetition numbers and never removed.
- **Provisional:** the latency stability threshold (20 % per-second p50 CV) is recorded but not gated, until it is calibrated on the target hardware.

## Foundation delivered

- Repository-wide generated-artifact cleanup and ignore rules.
- Single authoritative `AGENTS.md`.
- Public-scope hygiene gate rejects proprietary/runtime-out-of-scope terminology.
- Dependency-free `benchctl` controller.
- OpenJDK runtime and collector capability discovery; `benchctl fetch-jdks` installs OpenJDK 8, 11, 17, 21 and 25 from the Ubuntu archive. Each release is verified against the Ubuntu archive signing key, and each package against its index digest. Every install is validated by running `java` and `javac`, and by resolving the JVM's native libraries.
- `benchctl preflight`: quiet-machine checks, also applied to every Tier 2 run.
- Workload JDK compatibility bands and invalid-combination skipping.
- Experiment validation, matrix planning, smoke orchestration and Tier 2 execution for every Tier 2 workload.
- Truthful smoke result envelopes and invalid-comparison refusal.
- Versioned experiment, runtime and result schemas.
- Workload catalog with audited tiers.
- Controller unit tests (71), including the Tier 2 contract, preflight, JDK fetch and report tests; JDK 8/11/17/21/25 discovery CI.
- Readable report (`benchctl report`): tables, charts for throughput and latency over time, per-repetition bars, and a comparison of measurement-valid results.
- Compatibility smoke CI on JDK 8 and JDK 25.
- Correctness smoke checks for every numbered workload; Maven lifecycle CI for every application and harness.
- Statistical comparison using median throughput and bootstrap confidence-interval overlap.

## Remaining work

Everything below needs controlled hardware or a live environment. No code work is left in this pass.

1. **Run the benchmark matrix on a controlled host.** Run `benchctl preflight` until it passes, then `./benchctl run experiments/tier2-workloads.yaml`. Expect the first runs to be invalid until the host passes every gate. Check the results with `benchctl report`.
2. **Calibrate the provisional thresholds on that host.** These are the latency stability threshold and the warm-up window length, both set without hardware data.
3. **Foojay as a source** stays opt-in (`fetch-jdks --source foojay`). Its checksums are not signed, and its live endpoint has not been reachable from this sandbox.
4. **Tier 2 for the remaining workloads** (for example 02, whose telemetry spans several JVMs). Not planned in this pass.
5. **Tier 3 publication** of controlled-environment results, once they pass every gate. Until then, no publication claims.

## Known issues

- Workload 00 has no Maven build; its `run.sh test` lane runs in CI.
- Maven Central through the development proxy intermittently returns 404 or 429 for artifacts that exist. The wrapper and `run.sh` downloads retry, and a full clean build can still need a second attempt.
- Gradle is gone from the repository. The previous `gradle test` for 02 and 06 ran their `run.sh test` gates through a task hook; that behaviour is kept under Maven.
- The Maven distribution pinned in each `mvnw` is verified by its SHA-256; Central's SHA-512 was used to confirm the digest.

## Product roadmap

1. **First published, measurement-valid result set** for `01-fintech-ledger` on a quiet machine (pinned CPU frequency, no other load), with the environment fingerprint, raw data, the preflight record and `benchctl compare` output, under a dated `published/` directory.
2. **Measurement results for 09 and 12** on the same host, once their calibration thresholds are set.
3. **Charts per JDK × GC** once the matrix has data: the report already draws the charts; the matrix view needs the data.
4. **Tier 2 for the remaining workloads**, starting with 02, once their telemetry is defined per process.
