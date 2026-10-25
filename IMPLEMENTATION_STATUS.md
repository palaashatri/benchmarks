# Implementation status

Last audited: 2026-10-25.

## Readiness

**Overall classification: eleven workloads run through the Tier 2 engine (00, 01, 02, 03, 04, 05, 09, 10, 11, 12, 13). Workloads 06, 07 and 08 use dedicated measurement lanes. No measurement-valid results exist yet; every number on this host is a calibration run on an uncontrolled sandbox.**

- Tier 2 workloads: **11**, all run through the Tier 2 engine in the controller (`tools/benchctl`, `Tier2.java`) with `tools/loadgen`. Each declares its contract in `benchmarks/<id>/tier2.json`. Gateway workloads (`02`, `10`, `11`) measure the gateway process only; per-process telemetry for their service JVMs is open work (remaining item 6).
- Platform: the controller and every workload are Java 17+ / Maven. The repository has no Python. The only scripts are the launchers (`benchctl`, `benchctl.cmd`) and the Maven wrappers (`mvnw`, `mvnw.cmd`), which contain no logic. Linux is verified locally. Windows and macOS are checked by CI only, and that run has not happened yet.
- Measurement-valid published result sets: **0**.
- Publication-ready hardware comparisons: **none**.
- Calibration (not a benchmark; `run_kind: calibration`, `measurement_valid: false`). These numbers come from the Python-era engine that the Java port replaced. They are kept for comparison and will be re-run on the Java engine (see the audit section of the pull request): the Tier 2 engine ran 01–05 and 09–13 on JDK 21 with three 10-second repetitions each at the offered 50 req/s, zero request errors, and identity verified. Median p50 was 2.6–7.0 ms and median p99 5.7–49.7 ms across the ten non-00 Tier 2 workloads; the highest median p99 was 02 (49.7 ms) and 10 (28.1 ms). Throughput is the offered rate, so these runs say nothing about capacity. Startup lane (07, 10 starts per mode): default 487 ms, CDS off 559 ms, AppCDS 371 ms. Jobs lane (08): 10 jobs at concurrency 1 and 16 at concurrency 4, all digests matched. Chat (06): 500 msg/s target for 10 s with 200 subscribers, 6,496 of 6,500 messages sent, every frame conserved, delivery p99 8.5 ms. Every envelope is invalid: the sandbox fails preflight (its CPU governor is not observable and it injects `JAVA_TOOL_OPTIONS`), and the engine runs have three repetitions, below the five a benchmark needs.

The old committed results were removed because they mixed smoke traffic with hardcoded runtime metadata and load-generator JVM telemetry. They must not be used for JVM conclusions.

## Workload matrix

| # | Workload | Tier | Audited state |
|---|---|---:|---|
| 00 | Runtime compatibility | 2 | Deterministic Java 8-bytecode sort/compress/hash workload; the controller builds one JAR and records/reuses its SHA-256 digest across runtimes. Maven tests run on JDK 8, 21 and 25. |
| 01 | Fintech ledger | 2 | Real H2/Hikari transactions, isolated process identity, balance-conservation checks, application-process JFR/GC/NMT/CPU/RSS telemetry, open-loop HdrHistogram load, warm-up and statistical gates. Reference Tier 2 workload. |
| 02 | Microservices mesh | 2 | Gateway plus three independent service JVMs; actual account/transaction/notification HTTP calls and child identity/readiness/cleanup checks. The Tier 2 engine measures the gateway process only; the service JVMs have no per-process telemetry yet. |
| 03 | Streaming analytics | 2 | In-memory queue/window processor; no real broker or state backend. |
| 04 | Vector/FFM inference | 2 | JDK 21 preview Vector API and FFM paths with scalar/SIMD equivalence checks. Runs only on JDK 21 (catalog band 21–21). |
| 05 | Dynamic/polyglot service | 2 | Rhino execution prototype; not a cross-runtime OpenJDK feature lane. |
| 06 | Massive chat | 1 | Persistent virtual-thread TCP subscriptions and room fan-out; bounded outbound queues, real socket delivery counters and disconnect cleanup. Measured by its own driver (`ChatLoad`), not the Tier 2 engine; delivery latency is taken by the driver. |
| 07 | Cold start | 1 | Child-JVM process-to-health under the default class-data archive, with CDS off, and with a dynamic AppCDS archive built from a JAR at startup. Each mode is probed; the application records the times. Measured by the startup lane (`benchctl lane startup`), which reports median, MAD, bootstrap interval and outliers per mode. Still Tier 1: no host preflight and no page-cache control. |
| 08 | ETL batch | 1 | Real local NIO pipeline. Each job's canonical output is checked by SHA-256 against an independent in-memory reference, and a mismatch fails the job. Unfinished jobs report null, not zero. Measured by the jobs lane (`benchctl lane jobs`), which submits jobs at a fixed concurrency. Still Tier 1: no open-loop arrival model and no host preflight. |
| 09 | ONNX inference | 2 | Seeded classifier built as an ONNX graph and run by a live ONNX Runtime 1.18.0 session (Maven lane). `mvnw test` checks it against the Java reference, with max deviation 2.4e-7 over 1,000 inputs. The Tier 2 engine measures the live session (`mode: onnx-runtime`). The dependency-free smoke build reports the Java fallback. |
| 10 | Microservices fleet | 2 | Five independent replica JVMs; readiness-gated replacement, generation/PID identity and concurrent-deploy checks. The Tier 2 engine measures the gateway process only. |
| 11 | Autoscaling burst | 2 | Bounded gateway admission and queued work across one to four worker JVMs; automatic backlog scale-up, idle scale-down and failure accounting. The Tier 2 engine measures the gateway process only. |
| 12 | HFT gateway | 2 | Synthetic symbol-isolated FIFO/partial-fill engine shared by HTTP, persistent framed TCP and a grpc-java listener. `mvnw test` makes eight real gRPC calls and reads the same orders back over HTTP. The Tier 2 engine measures the HTTP order path. |
| 13 | Large monolith | 2 | Generates 1,000 components (20 modules of 50) at build time with a Java generator. A reflective constructor-injection container wires them dependencies-first and every request traverses the whole graph. The result is checked against an independent model of the wiring. A synthetic container, not a production framework. |

## Measurement engine (Tier 2)

- **Contract:** `benchmarks/<id>/tier2.json` declares the build, correctness gate, launch class, identity endpoint, token prefix, classpath and request mix. The JDK band and tier come from `catalog.json`.
- **Load:** `tools/loadgen` (`OpenLoopLoad`) is an open-loop, coordinated-omission-corrected generator. The request mix is a TSV contract file with weighted templates. Its output is a load envelope with no validity claim.
- **Gates:** correctness gate; identity (pid and run token) checked before load; zero request errors; every scheduled request completed; throughput stable inside warm-up (5 s window, CV ≤ 5 %); host preflight before and after; actual collector equals requested; repetition spread; at least five repetitions for a benchmark.
- **Outliers:** modified z-score and Tukey fences, reported with repetition numbers and never removed.
- **Collector identification:** JDK 9+ logs name the collector in their `Using` line. JDK 8 legacy GC logs do not, so the collector comes from the label each collector writes on every pause: `G1 Evacuation Pause`, `DefNew`, `PSYoungGen` or `ParNew`.
- **Provisional:** the latency stability threshold (20 % per-second p50 CV) is recorded but not gated, until it is calibrated on the target hardware.

## Controller (Java)

- `tools/benchctl` is the whole controller: JDK discovery and capability probes (`Runtimes`), host preflight (`HostEnvironment`), manifest validation and planning (`Experiments`), Tier 2 engine (`Tier2`), execution (`Execution`), startup and jobs lanes (`Lanes`), statistics and bootstrap intervals (`Stats`), comparison (`Compare`), result envelope checks (`ResultEnvelopes`), HTML report (`Report`), repository hygiene (`Hygiene`) and verified JDK fetch (`JdkFetch`).
- Commands: `doctor`, `discover-runtimes`, `list-runtimes`, `inspect-runtime`, `list-capabilities`, `list-workloads`, `validate`, `plan`, `preflight`, `hygiene`, `fetch-jdks`, `run`, `resume`, `tier2`, `lane`, `compare`, `report`, `verify-results`.
- `fetch-jdks` installs OpenJDK HotSpot builds from the Eclipse Adoptium API for Linux, Windows and macOS. Each archive must match the SHA-256 that the API publishes for it. The earlier Ubuntu-archive path with GPG signatures was removed because it only worked on Linux. The live Adoptium endpoint has not been reachable from this sandbox, so its download path is covered by a local fixture server only.
- Unit tests: 72 in ten classes (`./mvnw -B -f tools/benchctl/pom.xml test`), covering JDK fetch, statistics, telemetry parsers, comparison, host preflight, lanes, the report, Tier 2 contracts and validity gates, and controller envelope checks. Not carried over from the retired Python suite: `test_jdk_archive.py` (its Ubuntu-archive signature path was removed with that source; `JdkFetchTest` covers the Adoptium path), two tests of the legacy result shim (the Java controller never produces those envelopes), and the JDK-band planner test. The band itself is exercised by `validate` and `plan` in CI, but it has no unit test yet.
- `benchctl report` writes one HTML file with tables, charts for throughput and latency over time, per-repetition bars, and a comparison of measurement-valid results.

## Foundation delivered

- Repository-wide generated-artifact cleanup and ignore rules.
- Single authoritative `AGENTS.md`.
- Public-scope hygiene gate (`benchctl hygiene`) rejects proprietary and out-of-scope terminology and forbidden tracked files.
- Java controller with launchers for Unix shells and Windows `cmd`.
- OpenJDK runtime and collector capability discovery across JDK 8–25.
- `benchctl preflight`: quiet-machine checks, also applied to every Tier 2 run.
- Workload JDK compatibility bands and invalid-combination skipping.
- Experiment validation, matrix planning, smoke orchestration and Tier 2 execution.
- Truthful smoke result envelopes and invalid-comparison refusal.
- Versioned experiment, runtime and result schemas.
- Workload catalog with audited tiers.
- Maven lifecycle for every application and harness, with the Maven wrapper pinned to Maven 3.9.11 and verified by SHA-256.
- Platform-neutral Tier 2 matrix (`experiments/tier2-matrix.yaml`): 11 workloads, one runtime band, one collector set. It runs on any host with a discovered JDK 21.
- CI on Ubuntu, Windows and macOS (see `.github/workflows`): controller tests and manifest validation on JDK 17 and 21; shared libraries; load generator; workload 00 on JDK 8, 21 and 25; every other workload on JDK 21, and 09, 10, 11 and 13 on JDK 17.

## Remaining work

Items 1, 2 and 4 need controlled hardware or a live environment. Items 3, 5, 6 and 7 are code work, listed with what each needs.

1. **Run the benchmark matrix on a controlled host.** Run `./benchctl preflight` until it passes, then `./benchctl run experiments/tier2-matrix.yaml`. Expect the first runs to be invalid until the host passes every gate. Check the results with `./benchctl report`.
2. **Calibrate the provisional thresholds on that host.** These are the latency stability threshold and the warm-up window length, both set without hardware data.
3. **Live Adoptium verification.** `fetch-jdks` is written against the Adoptium API and its checksums. It needs one live run on each of Linux, Windows and macOS before it is trusted there. Checksums come from the API, which is not a signature; signature verification would be a further step.
4. **Tier 3 publication** of controlled-environment results, once they pass every gate. Until then, no publication claims.
5. **Startup and jobs lanes need the same gates as Tier 2.** `benchctl lane` measures 07 (child-JVM starts) and 08 (batch jobs). Neither fits the request-based Tier 2 engine yet. Both need host preflight, before-and-after checks and repetition gates, and 08 needs an open-loop submission model, before their numbers can be valid.
6. **Multi-process telemetry for 02, 10 and 11.** They run on the Tier 2 engine, which measures the gateway process only. Each needs a per-process telemetry definition for its service JVMs (child JVM options through the environment, per-child GC logs and sampling, roll-up per process) before its numbers describe the whole system.
7. **Windows and macOS are verified by CI only.** The Java controller and the Maven builds are written for all three, but this sandbox only runs Linux. The first CI run on the pull request is the verification; any failure there is open work.

## Known issues

- Maven Central through the development proxy intermittently returns 404 or 429 for artifacts that exist. The wrappers retry their downloads, and a full clean build can still need a second attempt.
- The `mvnw` wrappers fall back to `curl` for the Maven distribution download (upstream wrapper behaviour). The distribution is verified by its pinned SHA-256 either way.
- Gradle is gone from the repository. The earlier Gradle builds for 02 and 06 are now Maven builds.

## Product roadmap

1. **First published, measurement-valid result set** for `01-fintech-ledger` on a quiet machine (pinned CPU frequency, no other load), with the environment fingerprint, raw data, the preflight record and `benchctl compare` output, under a dated `published/` directory.
2. **Measurement results for 09 and 12** on the same host, once their calibration thresholds are set.
3. **Charts per JDK × GC** once the matrix has data: the report already draws the charts; the matrix view needs the data.
4. **Per-process telemetry for 02, 10 and 11**, then the shared measurement gates for the lanes of 06, 07 and 08.
