# AGENTS.md — OpenJDK benchmark suite

An OpenJDK HotSpot production-workload and regression suite for JDK 8–25: 13 production-shaped workloads plus a Java 8-bytecode compatibility workload, driven by a Java 17+ controller (`tools/benchctl`) that discovers JDKs, probes collectors/capabilities, plans valid combinations, runs experiments and refuses invalid comparisons. The controller and every workload run the same way on Linux, Windows and macOS.

Detailed per-workload state: [`IMPLEMENTATION_STATUS.md`](IMPLEMENTATION_STATUS.md). Measurement method and validity gates: [`METHODOLOGY.md`](METHODOLOGY.md). Each workload's `IMPLEMENTATION_NOTES.md` explains its design and limits.

## Current state (2026-10-25)

- `benchmarks/catalog.json` is the source of truth for each workload's tier and JDK range. Tier 2 (measurement-ready in code): `00`–`05`, `09`–`13`. Tier 1: `06`, `07`, `08`.
- `02`, `10` and `11` are Tier 2 with process-tree telemetry: the gateway and every child JVM it starts are sampled, logged and captured. Each child writes its own native memory summary and flight recording as it exits, and each reported child must be observed (see `IMPLEMENTATION_STATUS.md`).
- There are **no** measurement-valid published results. Do not add any until a run on controlled hardware passes every validity gate.

## Scope boundary

Public repository, **OpenJDK HotSpot only**. Do not add proprietary JVMs or collectors, vendor-private compiler services, confidential features, customer systems or private performance results, and no speculative SPIs for them. `./benchctl hygiene` enforces part of this.

## Commands

Controller (Java 17 or newer; the Maven wrapper is the only other tool):

```bash
./benchctl hygiene                               # repository hygiene and scope gate (Windows: benchctl.cmd hygiene)
./benchctl doctor                                # controller, repository and wrapper checks
./benchctl validate experiments/quick.yaml       # also every other manifest in experiments/
./benchctl discover-runtimes && ./benchctl list-runtimes && ./benchctl list-capabilities
./benchctl fetch-jdks --feature 17 --feature 21  # Adoptium builds, SHA-256 checked, into .benchctl/jdks
./benchctl preflight                             # quiet-machine checks; a Tier 2 run is invalid if these fail
./benchctl plan experiments/tier2-matrix.yaml    # the 11-workload Tier 2 matrix, platform-neutral
./benchctl run experiments/tier2-matrix.yaml     # real Tier 2 runs; needs a quiet machine to mean anything
./benchctl report results/<run-id>               # readable HTML report
./benchctl lane startup|jobs|chat ...            # the 07, 08 and 06 lanes, with five repetitions and the shared gates
./mvnw -B -f tools/benchctl/pom.xml test         # controller unit tests
```

Workloads and shared modules (each `app/` and `harness/` is an independent Maven project with its own wrapper, Maven 3.9.11, distribution SHA-256 pinned):

```bash
(cd benchmarks/<NN-name>/app && ./mvnw -B test)      # correctness and contract gates
(cd benchmarks/<NN-name>/harness && ./mvnw -B test)  # harness gates
./mvnw -B -f benchmarks/common/pom.xml test          # shared lifecycle and framed-TCP regressions
./mvnw -B -f tools/loadgen/pom.xml test              # open-loop load generator self-test
```

On Windows use `mvnw.cmd` and `benchctl.cmd` in place of `./mvnw` and `./benchctl`. The experiment files are JSON written with a `.yaml` extension (JSON is valid YAML), so the controller needs no YAML library.

## Layout

| Path | What it is |
|---|---|
| `benchctl`, `benchctl.cmd`, `mvnw`, `mvnw.cmd` | Launchers only. They build and run the Java controller or the Maven wrapper; they contain no logic |
| `tools/benchctl/` | The controller (Java 17): runtime discovery, capability probes, planning, Tier 2 engine, lanes, statistics, comparison, HTML report, hygiene gate, JDK fetch |
| `tools/loadgen/` | Shared open-loop, coordinated-omission-safe HdrHistogram load generator |
| `tools/testkit/`, `tools/lifecycle/`, `tools/smoke/` | Test-side helpers shared by the workload Maven builds |
| `benchmarks/NN-*/app` | Workload application (never imports harness code) |
| `benchmarks/NN-*/app/contract/` | The only interface between app and harness (OpenAPI/schemas) |
| `benchmarks/NN-*/harness` | Workload-specific harness |
| `benchmarks/NN-*/tier2.json` | Tier 2 contract: build, correctness gate, launch class, identity endpoint, request mix |
| `benchmarks/common/` | Shared app-side utilities: JSON, process identity/port publication, owned child-JVM lifecycle, bounded persistent TCP sessions |
| `schemas/` | Versioned experiment, runtime and result JSON schemas |
| `experiments/` | Reproducible experiment manifests (`tier2-matrix.yaml` is the Tier 2 matrix) |
| `results/` | Generated output — gitignored, never committed |

## Truth rules (non-negotiable)

1. Smoke output is never benchmark output.
2. Unknown data is `null` (or absent with a reason), never `0`.
3. Telemetry describes the measured application process, not the load generator.
4. Runtime, collector and JVM flags are read from the launched process, never hard-coded labels.
5. A result is measurement-valid only after correctness, telemetry, phase and statistical gates all pass. Every result carries `run_kind`, `implementation_tier`, `measurement_valid`, `invalid_reasons`, `warnings`.
6. A workload isn't done because it compiles or answers HTTP. It must exercise the behaviour it claims (real gRPC for HFT, real ONNX sessions, separate processes for mesh/fleet, persistent connections for chat, a broad class graph for the monolith). Until then its tier stays low and its notes say so.

## Engineering rules

- Shared measurement infrastructure is written once (controller/loadgen), not copied into each harness. Clone benchmark 01's Tier 2 engine to another workload only after that workload has equivalent correctness, process identity and telemetry.
- The controller is Java. Do not add Python, shell or other interpreters for controller logic. The launchers and `mvnw` wrappers are the only scripts, and they stay logic-free.
- Never apply a JVM flag or collector without probing that the runtime supports it. Plan JDK/GC combinations from discovered capabilities and each workload's `min_jdk`/`max_jdk`.
- Child JVMs (`benchmarks/common`): bind loopback only, use ephemeral ports, verify PID + run token + role on readiness, set heap explicitly, and always reap children and temp files on failure or shutdown. Shared code must compile on Java 17. Only workload 00's application code targets Java 8 bytecode.
- Pin every dependency and tool version. Never commit build outputs, downloaded jars, logs, PIDs, JFRs or results.
- A command called `test` must run tests. Every module's `mvnw test` is the authoritative check.
- Launchers (`benchctl`, `mvnw`) must keep their executable bit in git (`git update-index --chmod=+x`). CI also calls them through `sh`, so a checkout without the bit still runs.
- Don't add `CLAUDE.md`/`STATUS.md`-style files; the hygiene check rejects them. Status belongs in `IMPLEMENTATION_STATUS.md` and `catalog.json`.

## Git workflow and done

- Branch from `main`, open a PR; never push to `main`.
- Before pushing: controller unit tests, `./benchctl hygiene`, every `./benchctl validate` run, and `mvnw test` for every module you touched.
- If a change alters a workload's real capability, update its tier in `catalog.json`, its `IMPLEMENTATION_NOTES.md` and `IMPLEMENTATION_STATUS.md` in the same PR. Keep PR plans and checklists in the PR description, not in this file.

## Future SLOPOS/PARAMA targets and edge-efficiency requirements

**FUTURE TARGET ONLY / NOT VERIFIED.** benchmarks is an independently authored candidate for **optional first-party developer test tool, never an always-running system service**. Preserve Java 17+ benchmark controller, OpenJDK 8–25 workload suite with 13 production-shaped workloads and the current project's authoritative engineering contract, milestones, license and cross-platform requirements. The project is *not* a currently shipped SLOPOS/PARAMA app by virtue of being listed. Any optional first-party distribution requires a source/asset/provenance audit, real packaging, signed/verifiable builds, supported platform test matrix and no eager runtime/SDK load in the 512 MB core SLOPOS installation.

- **Linux/SLOPOS target:** SLOPOS optional Benchmarks developer catalog with launch/log/result file associations, progress, cancellation, clearly labeled target JDK/collector/capability combinations and stable provenance. No default installation, service or automatic benchmark on user login.
- **Pi Zero 2 W (4× Cortex-A53, 512 MB shared RAM) efficiency:** Minimize observer effect: low-rate/streamed metrics collection, bounded raw logs/results, fixed synthetic datasets, per-run memory/CPU budgets and explicit warm-up/cool-down. Add **edge-small** profiles that fit measured free RAM, isolate measurement processes and record thermal throttling/CPU frequency/microSD latency; never interpret Pi numbers as comparable to x86 host runs without normalization. Avoid background probes at idle.
- **Future native PARAMA/SURYA target:** PARAMA test target only after actual OpenJDK runtime supported on native PARAMA and needed process/tools/GC APIs; begin tiny Java benchmark and controller subset, without assuming existing Linux OpenJDK binaries, all GCs or JIT support. Do not mislabel failed/unimplemented JVM portability as workload regression.
- **Evidence and error handling:** On Pi Zero 2 W, run an explicitly low-memory test matrix with verified AArch64 Java runtime, separate JDK/runtime/controller RSS, log/sample overhead, process isolation, OOM/timeout and thermal clocks. Report unsupported GC/JDK or host settings as SKIPPED/NOT_RUN, not performance data.
- **Methodology:** Pin actual OS/ABI/runtime/GC/driver, reference input fixtures and revision; measure cold/warm time-to-usable, process-tree PSS/RSS, whole-system `MemAvailable`, steady idle CPU, local GUI response p95 if applicable, compute throughput and correctness independently, timeout/cancellation, memory-pressure failure and restart. Cross-build/virtualization is NOT proof of real Pi hardware or future PARAMA runtime. Decline or gate unsupported heavy operations before OOM; allow long computations but never permit them to starve critical interactive tasks. Keep external I/O delays distinct from app-induced UI stalls.
