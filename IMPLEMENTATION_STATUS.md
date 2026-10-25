# Implementation status

Last audited: 2026-09-30. Code-completion pass re-checked 2026-10-25: the Tier 2 measurement engine is generic, the build is unified, and the controller covers JDK fetching, host preflight and report charts.

## Readiness

**Overall classification: eleven workloads run through the Tier 2 engine (00, 01, 02, 03, 04, 05, 09, 10, 11, 12, 13). Workloads 06, 07 and 08 use dedicated measurement lanes. No measurement-valid results exist yet; every number on this host is a calibration run on an uncontrolled sandbox.**

- Tier 2 workloads: **11** (`00`, `01`, `02`, `03`, `04`, `05`, `09`, `10`, `11`, `12`, `13`). All run through the same engine, `tools/run_tier2_experiment.py` with `tools/loadgen`, and each declares its contract in `benchmarks/<id>/tier2.json`. Gateway workloads (`02`, `10`, `11`) measure the gateway process only.
- Calibration on this host (not a benchmark; `run_kind: calibration`, `measurement_valid: false`). The Tier 2 engine ran 01–05 and 09–13 on JDK 21: three 10-second repetitions each at the offered 50 req/s, with zero request errors and identity verified. Workload 00 ran on JDK 8, 11, 17, 21 and 25. Across the ten non-00 Tier 2 workloads, median p50 is 2.6–7.0 ms and median p99 is 5.7–49.7 ms; the highest median p99 is 02 (49.7 ms) and 10 (28.1 ms). Throughput is the offered rate, so these runs say nothing about capacity. Startup lane (07, 10 starts per mode): default 487 ms, CDS off 559 ms, AppCDS 371 ms. Jobs lane (08): 10 jobs at concurrency 1 and 16 at concurrency 4, all digests matched. Chat (06): 500 msg/s target for 10 s with 200 subscribers, 6,496 of 6,500 messages sent, every frame conserved, delivery p99 8.5 ms. Every envelope is invalid: the host fails preflight (the CPU governor is not observable here, and this sandbox injects `JAVA_TOOL_OPTIONS`), and the engine runs have three repetitions, below the five a benchmark needs.
- Measurement-valid published result sets: **0**.
- JDK 8–25 controller support: runtime discovery, collector probing, implementation-band filtering, planning and verified JDK fetching (`benchctl fetch-jdks`).
- Build: one system for every workload. Applications and harnesses use Maven, each with a `./mvnw` wrapper pinned to Maven 3.9.11 and verified by SHA-256. Workload 00 keeps its `run.sh` lane.
- Publication-ready hardware comparisons: **none**.

The old committed results were removed because they mixed smoke traffic with hardcoded runtime metadata and load-generator JVM telemetry. They must not be used for JVM conclusions.

## Workload matrix

| # | Workload | Tier | Audited state |
|---|---|---:|---|
| 00 | Runtime compatibility | 2 | Deterministic Java 8-bytecode sort/compress/hash workload; `benchctl` builds one JAR and records/reuses its SHA-256 digest across runtimes. |
| 01 | Fintech ledger | 2 | Real H2/Hikari transactions, isolated process identity, balance-conservation checks, application-process JFR/GC/NMT/CPU/RSS telemetry, open-loop HdrHistogram load, warm-up and statistical gates. Reference Tier 2 workload. |
| 02 | Microservices mesh | 2 | Gateway plus three independent service JVMs; actual account/transaction/notification HTTP calls and child identity/readiness/cleanup checks. The Tier 2 engine measures the gateway process only; the three service JVMs have no per-process telemetry yet. |
| 03 | Streaming analytics | 2 | In-memory queue/window processor; no real broker/state backend. |
| 04 | Vector/FFM inference | 2 | JDK 21 preview Vector API and FFM paths with scalar/SIMD equivalence smoke checks. |
| 05 | Dynamic/polyglot service | 2 | Rhino execution prototype; not a cross-runtime OpenJDK feature lane. |
| 06 | Massive chat | 1 | Persistent virtual-thread TCP subscriptions and room fan-out; bounded outbound queues, real socket delivery counters and disconnect cleanup. Measured by its own driver (`ChatLoad`), not the Tier 2 engine; delivery latency is taken by the driver. |
| 07 | Cold start | 1 | Child-JVM process-to-health under the default class-data archive, with CDS off, and with a dynamic AppCDS archive built from a JAR at startup. Each mode is probed; the application records the times. Measured by the startup lane (`tools/app_lanes.py startup`), which reports median, MAD, bootstrap interval and outliers per mode. Still Tier 1: no host preflight and no page-cache control. |
| 08 | ETL batch | 1 | Real local NIO pipeline. Each job's canonical output is checked by SHA-256 against an independent in-memory reference, and a mismatch fails the job. Unfinished jobs report null, not zero. Measured by the jobs lane (`tools/app_lanes.py jobs`), which submits jobs at a fixed concurrency. Still Tier 1: no open-loop arrival model and no host preflight. |
| 09 | ONNX inference | 2 | Seeded classifier built as an ONNX graph and run by a live ONNX Runtime 1.18.0 session (Maven lane). `mvn test` checks it against the Java reference, with max deviation 2.4e-7 over 1,000 inputs. The Tier 2 engine measures the live session (`mode: onnx-runtime`). The dependency-free smoke build reports the Java fallback. |
| 10 | Microservices fleet | 2 | Five independent replica JVMs; readiness-gated replacement, generation/PID identity and concurrent-deploy checks. The Tier 2 engine measures the gateway process only. |
| 11 | Autoscaling burst | 2 | Bounded gateway admission and queued work across one to four worker JVMs; automatic backlog scale-up, idle scale-down and failure accounting. The Tier 2 engine measures the gateway process only. |
| 12 | HFT gateway | 2 | Synthetic symbol-isolated FIFO/partial-fill engine shared by HTTP, persistent framed TCP and a grpc-java listener. `mvn test` makes eight real gRPC calls and reads the same orders back over HTTP. The Tier 2 engine measures the HTTP order path. |
| 13 | Large monolith | 2 | Generates 1,000 components (20 modules of 50) at build time. A reflective constructor-injection container wires them dependencies-first and every request traverses the whole graph. The result is checked against an independent Python model of the wiring. A synthetic container, not a production framework. Tier 2 engine. |

## Measurement engine (Tier 2)

- **Contract:** `benchmarks/<id>/tier2.json` declares the build, correctness gate, launch class, identity endpoint, token prefix, classpath and request mix. The JDK band and tier come from `catalog.json`.
- **Load:** `tools/loadgen` (`OpenLoopLoad`) is an open-loop, coordinated-omission-corrected generator. The request mix is a TSV contract file with weighted templates. Its output is a load envelope with no validity claim.
- **Gates:** correctness gate; identity (pid and run token) checked before load; zero request errors; every scheduled request completed; throughput stable inside warm-up (5 s window, CV ≤ 5 %); host preflight before and after; actual collector equals requested; repetition spread; at least five repetitions for a benchmark.
- **Outliers:** modified z-score and Tukey fences, reported with repetition numbers and never removed.
- **Collector identification:** JDK 9+ logs name the collector in their `Using` line. JDK 8 legacy GC logs do not, so the collector comes from the label each collector writes on every pause: `G1 Evacuation Pause`, `DefNew`, `PSYoungGen` or `ParNew`. Each label was checked against real JDK 8 logs.
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
- Controller unit tests (90), including the Tier 2 contract, preflight, JDK fetch and report tests; JDK 8/11/17/21/25 discovery CI.
- Readable report (`benchctl report`): tables, charts for throughput and latency over time, per-repetition bars, and a comparison of measurement-valid results.
- Compatibility smoke CI on JDK 8 and JDK 25.
- Correctness smoke checks for every numbered workload; Maven lifecycle CI for every application and harness.
- Statistical comparison using median throughput and bootstrap confidence-interval overlap.

## Remaining work

Items 1, 2 and 4 need controlled hardware or a live environment. Items 3, 5 and 6 are code work, listed with what each needs.

1. **Run the benchmark matrix on a controlled host.** Run `benchctl preflight` until it passes, then `./benchctl run experiments/tier2-workloads.yaml`. Expect the first runs to be invalid until the host passes every gate. Check the results with `benchctl report`.
2. **Calibrate the provisional thresholds on that host.** These are the latency stability threshold and the warm-up window length, both set without hardware data.
3. **Foojay as a source** stays opt-in (`fetch-jdks --source foojay`). Its checksums are not signed, and its live endpoint has not been reachable from this sandbox.
4. **Tier 3 publication** of controlled-environment results, once they pass every gate. Until then, no publication claims.
5. **Controller lanes for startup and jobs.** `tools/app_lanes.py` measures 07 (child-JVM starts) and 08 (batch jobs). Neither fits the request-based Tier 2 engine. Both need host preflight and repetition gates, and 08 needs an open-loop submission model, before their numbers can be valid.
6. **Multi-process telemetry for 02, 10 and 11.** They run on the Tier 2 engine, which measures the gateway process only. Each needs a per-process telemetry definition for its service JVMs before its numbers describe the whole system.

## Known issues

- Workload 00 has no Maven build; its `run.sh test` lane runs in CI.
- Maven Central through the development proxy intermittently returns 404 or 429 for artifacts that exist. The wrapper and `run.sh` downloads retry, and a full clean build can still need a second attempt.
- Gradle is gone from the repository. The previous `gradle test` for 02 and 06 ran their `run.sh test` gates through a task hook; that behaviour is kept under Maven.
- The Maven distribution pinned in each `mvnw` is verified by its SHA-256; Central's SHA-512 was used to confirm the digest.

## Product roadmap

1. **First published, measurement-valid result set** for `01-fintech-ledger` on a quiet machine (pinned CPU frequency, no other load), with the environment fingerprint, raw data, the preflight record and `benchctl compare` output, under a dated `published/` directory.
2. **Measurement results for 09 and 12** on the same host, once their calibration thresholds are set.
3. **Charts per JDK × GC** once the matrix has data: the report already draws the charts; the matrix view needs the data.
4. **Per-process telemetry for 02, 10 and 11**, so their Tier 2 numbers describe the whole system, then the shared measurement gates for the lanes of 06, 07 and 08.
