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

## Independent benchmark tooling and low-memory efficiency

**Benchmarks is an independent OpenJDK workload and QA harness, not a SLOPOS/PARAMA desktop application candidate.** It must **not** be bundled, added to a first-party Software catalog (even as an optional developer app), made into a shell/background service or assigned an OS-specific GUI/launcher integration milestone. Its purpose is reproducible JVM performance and correctness measurement across supported systems, without dependency on a particular desktop environment. Neither Linux SLOPOS nor future native PARAMA is a product target solely because it appears in the broader ecosystem.

- **Preserve the existing contract:** The controller/workloads retain their declared OpenJDK, OS, collector and architecture matrices, correctness checks, reproducible comparison logic and no-observer-bias rules. A hypothetical future PARAMA Java runtime or Linux ABI is an *external platform possibility*, not a PARAMA port commitment for Benchmarks.
- **Raspberry Pi Zero 2 W efficiency as a useful measurement profile:** when a compatible AArch64 Java runtime is actually available, allow opt-in **edge-small** fixture profiles with bounded heaps, streamed/sampled metrics, capped log/output, explicit warm-up and cooldown, thermal/frequency/microSD reporting, and observer-overhead measurements. Never run workloads automatically in background; don't mistake Pi data for like-for-like comparisons with dissimilar x86 hardware or unsupported GC/JDK combinations.
- **Evidence:** Record exact JDK/GC/flags, commit and dataset hash, OS/ABI/hardware, process tree PSS/peak RSS, whole-system `MemAvailable`, heap and collector memory, observed harness CPU/I/O overhead, temperature/throttling, timing distributions, timeout/OOM and unsupported settings. A Pi-specific PASS requires an actual Pi Zero 2 W run; otherwise mark `NOT_RUN` or `BLOCKED`.
- **Scope:** Optimization here means a trustworthy low-overhead benchmark tool, *not* a newly packaged SLOPOS/PARAMA application. Do not weaken workload semantics or currently required test gates.

