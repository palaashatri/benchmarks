# Implementation status

Last audited: 2026-10-25.

## Readiness

**Overall classification: all fourteen workloads (00 through 13) are Tier 2 and run through the Tier 2 engine. Eleven use the request-mix shape (00 through 05, 09 through 13); 06 uses `framed-tcp`, 07 uses `startup` and 08 uses `jobs`. The lanes (`benchctl lane chat|startup|jobs`) run the same shapes with five repetitions under the same gates. No measurement-valid results exist yet; every number on this host is a calibration run on an uncontrolled sandbox.**

- Tier 2 workloads: **14**. Each declares its contract in `benchmarks/<id>/tier2.json`.
- Process-tree telemetry: `02`, `10` and `11` declare `telemetry_scope: process-tree`. Their telemetry covers the gateway and every child JVM it starts: CPU and resident memory per process, a pooled GC pause distribution across every JVM, a cross-check that each child the gateway reports is one the sampler observed and has its own GC log, and per-child native memory and a converted flight recording. Each child writes those as it exits, after the gateway's shutdown closes its stdin, so a child retired mid-run is captured too. The tree's committed native memory and allocation rate are sums that are null unless every JVM reported.
- Lanes: `06` (chat), `07` (startup) and `08` (jobs) are Tier 2 in the catalogue. Each lane runs five repetitions, samples the host before and after, applies the shared gates, and adds its own correctness and spread gates. Their envelopes are measurement-valid only once every gate passes on controlled hardware. The jobs lane shares its job driver with the engine.
- Platform: the controller and every workload are Java 17+ / Maven. The repository has no Python. The only scripts are the launchers (`benchctl`, `benchctl.cmd`) and the Maven wrappers (`mvnw`, `mvnw.cmd`), which contain no logic. Linux is verified locally. Windows and macOS are checked by CI only, and that run is the verification.
- Measurement-valid published result sets: **0**.
- Publication-ready hardware comparisons: **none**.

Calibration on this host (not a benchmark; `run_kind: calibration`, `measurement_valid: false`):

- Parity check, Python-era engine against the Java engine: the Java engine re-ran 01 with the Python-era settings (JDK 21, G1, 50 req/s, 5 s warm-up, 10 s measurement, 8 threads, seed 424242, three repetitions). Median p50 was 3.66 ms against 3.54 ms, median p99 7.29 ms against 7.87 ms, and throughput 50.0 req/s in both. Both runs are invalid for the same reasons, so this is a parity check, not a benchmark.
- Process-tree runs on the Java engine (three repetitions, same settings). `02`: three child JVMs in every repetition, each reported by the gateway, observed by the tree sampler and logged; peak process count 4; process-tree resident memory about 403 MiB median; one-core CPU about 62 %; pooled GC p99 about 16 ms. `10`: five children per repetition, all reported, observed and logged; peak process count 6; tree resident memory about 529 MiB; pooled GC p99 9.2 to 10.1 ms. `11`: autoscaling workers are observed as they start and stop; each reported worker is observed and logged in every repetition; peak process count 3 or 4.
- Lanes on this host (five repetitions each; invalid by the preflight and tier rules):
  - Startup (`07`, JAR, three samples per mode): every repetition passed and the application's own record matched the driver. Median time to health: default about 516 ms, no-cds about 582 ms, AppCDS about 396 ms.
  - Startup engine (`07`, `startup` shape, ten starts per mode, calibration run): the repetition passed, but the no-cds mode's coefficient of variation was 11.6 %, above the 10 % gate, so the gate rejects it.
  - Jobs (`08`, open loop at 1 job per second, four jobs, concurrency 2): every job completed and every digest matched. Median job latency about 268 ms with CV 0.5 %. The worst submission lag was 3.7 ms against a 1 s interval. Records per second varied with CV 12.4 %, so the spread gate fired.
  - Chat (`06`, 50 subscribers, 100 msg/s, 2 s warm-up, 5 s measurement): an earlier run sent 696 of 700 scheduled messages because the publisher schedule was truncated. After the schedule was split exactly, a fixed run sent all 700, conserved every frame with no error frames, and had a p99 delivery latency of about 11 ms. Spread across repetitions has not been measured since the fix.
  - Chat engine (`06`, `framed-tcp` shape, 40 subscribers in 4 rooms, 8 publishers, 100 msg/s, 6 s warm-up, 6 s measurement, calibration run): warm-up stable, every scheduled message in the window sent, no error frames, 1,200 deliveries/s, p50 about 2.4 ms, p99 about 4.9 ms. The engine needs the 6 s warm-up, because the 2 s warm-up is shorter than the 5 s stability window.
- Python-era calibration (before the controller moved to Java; kept for comparison): the Tier 2 engine ran 01–05 and 09–13 on JDK 21 with three 10-second repetitions each at the offered 50 req/s, zero request errors and identity verified. Median p50 was 2.6–7.0 ms and median p99 5.7–49.7 ms across the ten non-00 Tier 2 workloads. Throughput is the offered rate, so these runs say nothing about capacity. Every envelope is invalid: the sandbox fails preflight (its CPU governor is not observable and it injects `JAVA_TOOL_OPTIONS`), and the runs have three repetitions, below the five a benchmark needs.

The old committed results were removed because they mixed smoke traffic with hardcoded runtime metadata and load-generator JVM telemetry. They must not be used for JVM conclusions.

## Workload matrix

| # | Workload | Tier | Audited state |
|---|---|---:|---|
| 00 | Runtime compatibility | 2 | Deterministic Java 8-bytecode sort/compress/hash workload; the controller builds one JAR and records/reuses its SHA-256 digest across runtimes. Maven tests run on JDK 8, 21 and 25. |
| 01 | Fintech ledger | 2 | Real H2/Hikari transactions, isolated process identity, balance-conservation checks, application-process JFR/GC/NMT/CPU/RSS telemetry, open-loop HdrHistogram load, warm-up and statistical gates. Reference Tier 2 workload. |
| 02 | Microservices mesh | 2 | Gateway plus three independent service JVMs; actual account/transaction/notification HTTP calls and child identity/readiness/cleanup checks. Process-tree telemetry covers the gateway and its three children, including each child's native memory and flight recording. |
| 03 | Streaming analytics | 2 | In-memory queue/window processor; no real broker or state backend. |
| 04 | Vector/FFM inference | 2 | JDK 21 preview Vector API and FFM paths with scalar/SIMD equivalence checks. Runs only on JDK 21 (catalog band 21–21). |
| 05 | Dynamic/polyglot service | 2 | Rhino execution prototype; not a cross-runtime OpenJDK feature lane. |
| 06 | Massive chat | 2 | Persistent virtual-thread TCP subscriptions and room fan-out; bounded outbound queues, real socket delivery counters and disconnect cleanup. Measured by the Tier 2 engine's `framed-tcp` shape, in which a driver JVM attaches to the application the engine started, and by the chat lane, which runs the driver five times with a fresh application each time. Delivery latency is taken by the driver. |
| 07 | Cold start | 2 | Child-JVM process-to-health under the default class-data archive, with CDS off, and with a dynamic AppCDS archive built from a JAR at startup. Measured by the Tier 2 engine's `startup` shape and by the startup lane. Child CPU, memory and JFR are not sampled (a child recording would change the start times), and the page cache is warm. |
| 08 | ETL batch | 2 | Real local NIO pipeline. Each job's canonical output is checked by SHA-256 against an independent in-memory reference, and a mismatch fails the job. Unfinished jobs report null, not zero. Measured by the Tier 2 engine's jobs load shape, and by the jobs lane, both of which submit jobs on an open-loop schedule and time each from its intended submission time. |
| 09 | ONNX inference | 2 | Seeded classifier built as an ONNX graph and run by a live ONNX Runtime 1.18.0 session (Maven lane). `mvnw test` checks it against the Java reference, with max deviation 2.4e-7 over 1,000 inputs. The Tier 2 engine measures the live session (`mode: onnx-runtime`). The dependency-free smoke build reports the Java fallback. |
| 10 | Microservices fleet | 2 | Five independent replica JVMs; readiness-gated replacement, generation/PID identity and concurrent-deploy checks. Process-tree telemetry covers the gateway and its five replicas. |
| 11 | Autoscaling burst | 2 | Bounded gateway admission and queued work across one to four worker JVMs; automatic backlog scale-up, idle scale-down and failure accounting. Process-tree telemetry follows workers as they start and stop. |
| 12 | HFT gateway | 2 | Synthetic symbol-isolated FIFO/partial-fill engine shared by HTTP, persistent framed TCP and a grpc-java listener. `mvnw test` makes eight real gRPC calls and reads the same orders back over HTTP. The Tier 2 engine measures the HTTP order path. |
| 13 | Large monolith | 2 | Generates 1,000 components (20 modules of 50) at build time with a Java generator. A reflective constructor-injection container wires them dependencies-first and every request traverses the whole graph. The result is checked against an independent model of the wiring. A synthetic container, not a production framework. |

## Measurement engine (Tier 2)

- **Contract:** `benchmarks/<id>/tier2.json` declares the build, correctness gate, launch class, identity endpoint, token prefix, classpath, request mix and `telemetry_scope` (`process`, the default, or `process-tree`). The JDK band and tier come from `catalog.json`.
- **Load:** `tools/loadgen` (`OpenLoopLoad`) is an open-loop, coordinated-omission-corrected generator. The request mix is a TSV contract file with weighted templates. Its output is a load envelope with no validity claim.
- **Load shapes:** `http-mix` (the default) drives the TSV request mix through `tools/loadgen`. `jobs` submits jobs on the open-loop schedule and times each to completion (`08`). `startup` starts each child JVM per mode and takes the application's own time to health (`07`). `framed-tcp` attaches a separate driver JVM to the application and measures persistent-connection delivery (`06`). The contract names the shape in `load_shape`.
- **Process-tree telemetry:** the sampler records the application process and every live descendant on each 100 ms tick, so the totals describe one instant. Children inherit their GC log option through `BENCH_CHILD_JVM_OPTIONS`, which the engine sets; each child writes `gc-<pid>.log`. The gateway's `/runtime` lists its live children, and the engine reads that list after load. Gates: every reported child is observed; every observed child has a GC log; at least one child is observed.
- **Gates:** correctness gate; identity (pid and run token) checked before load; zero request errors; every scheduled request completed; throughput stable inside warm-up (5 s window, CV ≤ 5 %; not applied to the `startup` shape); host preflight before and after; actual collector equals requested; repetition spread; at least five repetitions for a benchmark. The run-kind, preflight, repetition and host-state gates are shared with the lanes (`Tier2.sharedReasons`).
- **Outliers:** modified z-score and Tukey fences, reported with repetition numbers and never removed.
- **Collector identification:** JDK 9+ logs name the collector in their `Using` line. JDK 8 legacy GC logs do not, so the collector comes from the label each collector writes on every pause: `G1 Evacuation Pause`, `DefNew`, `PSYoungGen` or `ParNew`.
- **Provisional:** the latency stability threshold (20 % per-second p50 CV) and the lane spread threshold (10 % CV across repetitions) are recorded but not calibrated on target hardware.

## Lanes (06, 07, 08)

- `benchctl lane startup` runs the 07 application, with five repetitions by default. Each repetition starts every mode several times and checks the times against the application's own record. Use the packaged application JAR as the classpath: the AppCDS mode needs a JAR.
- `benchctl lane jobs` runs the 08 application. Jobs are submitted at a fixed rate (open loop), and each job's latency counts from its intended submission time. A schedule that falls behind is a lane reason, not a silent delay.
- `benchctl lane chat` runs the 06 driver (`--driver-classpath` is the harness classes) once per repetition. Each run starts a fresh application.
- All three record the host fingerprint and preflight before and after the lane, and apply `Tier2.sharedReasons`. Each adds its own correctness gates (cross-check, digests, frame conservation, all messages sent) and a spread gate on its primary metric (`Tier2.spreadReason`).
- Benchmark results are refused for workloads below Tier 2 (`Lanes.TIER_ONE_POLICY`). `06`, `07` and `08` are Tier 2, so their lane envelopes can be measurement-valid once every gate passes on controlled hardware.

## Controller (Java)

- `tools/benchctl` is the whole controller: JDK discovery and capability probes (`Runtimes`), host preflight (`HostEnvironment`), manifest validation and planning (`Experiments`), Tier 2 engine (`Tier2`), process-tree sampling (`Telemetry.TreeSampler`), execution (`Execution`), lanes (`Lanes`), statistics and bootstrap intervals (`Stats`), comparison (`Compare`), result envelope checks (`ResultEnvelopes`), HTML report (`Report`), repository hygiene (`Hygiene`) and verified JDK fetch (`JdkFetch`).
- Commands: `doctor`, `discover-runtimes`, `list-runtimes`, `inspect-runtime`, `list-capabilities`, `list-workloads`, `validate`, `plan`, `preflight`, `hygiene`, `fetch-jdks`, `run`, `resume`, `tier2`, `lane`, `compare`, `report`, `verify-results`.
- `fetch-jdks` installs OpenJDK HotSpot builds from the Eclipse Adoptium API for Linux, Windows and macOS. Each archive must match the SHA-256 that the API publishes for it. The earlier Ubuntu-archive path with GPG signatures was removed because it only worked on Linux. The live Adoptium endpoint has not been reachable from this sandbox, so its download path is covered by a local fixture server only.
- Unit tests: 81 in eleven classes (`./mvnw -B -f tools/benchctl/pom.xml test`). They cover JDK fetch, statistics, telemetry parsers, the process-tree gates, comparison, host preflight, the lane gates, the report, Tier 2 contracts and validity, and controller envelope checks. Four tests from the retired Python suite were not carried over: `test_jdk_archive.py` (its Ubuntu-archive signature path went with that source; `JdkFetchTest` covers the Adoptium path), two tests of the legacy result shim (the Java controller never produces those envelopes), and the JDK-band planner test. The band itself is exercised by `validate` and `plan` in CI, but it has no unit test yet.
- `benchctl report` writes one HTML file with tables, charts for throughput and latency over time, per-repetition bars, process-tree totals, and a comparison of measurement-valid results.

## Foundation delivered

- Repository-wide generated-artifact cleanup and ignore rules.
- Single authoritative `AGENTS.md`.
- Public-scope hygiene gate (`benchctl hygiene`) rejects proprietary and out-of-scope terminology and forbidden tracked files.
- Java controller with launchers for Unix shells and Windows `cmd`.
- OpenJDK runtime and collector capability discovery across JDK 8–25.
- `benchctl preflight`: quiet-machine checks, also applied to every Tier 2 run and every lane.
- Workload JDK compatibility bands and invalid-combination skipping.
- Experiment validation, matrix planning, smoke orchestration and Tier 2 execution.
- Truthful smoke result envelopes and invalid-comparison refusal.
- Versioned experiment, runtime and result schemas.
- Workload catalog with audited tiers.
- Maven lifecycle for every application and harness, with the Maven wrapper pinned to Maven 3.9.11 and verified by SHA-256. The wrapper checks the distribution with `shasum` first, which works on Linux and macOS.
- Platform-neutral Tier 2 matrix (`experiments/tier2-matrix.yaml`): 11 workloads, one runtime band, one collector set. It runs on any host with a discovered JDK 21.
- CI on Ubuntu, Windows and macOS (see `.github/workflows`): controller tests, manifest validation and the Tier 2 plan on JDK 17 and 21; shared libraries; load generator; workload 00 on JDK 8, 21 and 25; every other workload on JDK 21, and 09, 10, 11 and 13 on JDK 17. Each harness packages its application first.

## Remaining work

Items 1, 2 and 4 need controlled hardware or a live environment. Items 3 and 5 are open and listed with what each needs.

1. **Run the benchmark matrix on a controlled host.** Run `./benchctl preflight` until it passes, then `./benchctl run experiments/tier2-matrix.yaml`. Expect the first runs to be invalid until the host passes every gate. Check the results with `./benchctl report`.
2. **Calibrate the provisional thresholds on that host.** These are the latency stability threshold, the lane spread threshold and the warm-up window length, all set without hardware data.
3. **Live Adoptium verification.** `fetch-jdks` is written against the Adoptium API and its checksums. It needs one live run on each of Linux, Windows and macOS before it is trusted there. Checksums come from the API, which is not a signature; signature verification would be a further step.
4. **Tier 3 publication** of controlled-environment results, once they pass every gate. Until then, no publication claims. `06`, `07` and `08` are Tier 2 through the engine's `framed-tcp`, `startup` and `jobs` shapes. Each still needs a controlled-hardware run that passes every gate.
5. **Windows and macOS are verified by CI only.** The Java controller and the Maven builds are written for all three, but this sandbox only runs Linux. The first CI run on the pull request is the verification; any failure there is open work.

## Known issues

- Maven Central through the development proxy intermittently returns 404 or 429 for artifacts that exist. The wrappers retry their downloads, and a full clean build can still need a second attempt.
- The `mvnw` wrappers fall back to `curl` for the Maven distribution download (upstream wrapper behaviour). The distribution is verified by its pinned SHA-256 either way.
- Gradle is gone from the repository. The earlier Gradle builds for 02 and 06 are now Maven builds.
- The Tier 2 engine rebuilds the controller on each `benchctl` launch. Editing controller sources during a long run changes the next launch; run measurements from a fixed tree.

## Product roadmap

1. **First published, measurement-valid result set** for `01-fintech-ledger` on a quiet machine (pinned CPU frequency, no other load), with the environment fingerprint, raw data, the preflight record and `benchctl compare` output, under a dated `published/` directory.
2. **Measurement results for 09 and 12** on the same host, once their calibration thresholds are set.
3. **Charts per JDK × GC** once the matrix has data: the report already draws the charts; the matrix view needs the data.