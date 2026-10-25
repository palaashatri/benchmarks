# AGENTS.md — OpenJDK benchmark suite

An OpenJDK HotSpot production-workload and regression suite for JDK 8–25: 13 production-shaped workloads plus a Java 8-bytecode compatibility workload, driven by a dependency-free Python controller (`benchctl`) that discovers JDKs, probes collectors/capabilities, plans valid combinations, runs experiments and refuses invalid comparisons.

Detailed per-workload state: [`IMPLEMENTATION_STATUS.md`](IMPLEMENTATION_STATUS.md). Measurement method and validity gates: [`METHODOLOGY.md`](METHODOLOGY.md). Each workload's `IMPLEMENTATION_NOTES.md` explains its design and limits.

## Current state (2026-10-20)

- `01-fintech-ledger` is the only **Tier 2** (measurement-ready) workload. Everything else is Tier 0/1: correctness and contract smoke tests, not benchmark numbers.
- There are **no** measurement-valid published results. Do not add any until a run on controlled hardware passes every validity gate.
- `benchmarks/catalog.json` is the source of truth for each workload's tier and JDK range.

## Scope boundary

Public repository, **OpenJDK HotSpot only**. Do not add proprietary JVMs or collectors, vendor-private compiler services, confidential features, customer systems or private performance results, and no speculative SPIs for them. `tools/check_repository.py` enforces part of this.

## Commands

Controller (Python 3.12, standard library only):

```bash
python3 -m unittest discover -s tools/tests -v
python3 tools/check_repository.py                # repository hygiene and scope gate
./benchctl validate experiments/quick.yaml       # also standard.yaml, gc-focused.yaml, ledger-benchmark.yaml, tier2-workloads.yaml
./benchctl doctor && ./benchctl discover-runtimes && ./benchctl list-runtimes
./benchctl fetch-jdks --feature 21               # verified OpenJDK HotSpot builds into .benchctl/jdks
./benchctl preflight                             # quiet-machine checks; a Tier 2 run is invalid if these fail
./benchctl plan experiments/tier2-workloads.yaml
./benchctl run experiments/tier2-workloads.yaml  # real Tier 2 runs; needs a quiet machine to mean anything
```

Workloads (each `app/` and `harness/` is an independent project):

```bash
(cd benchmarks/<NN-name>/app && ./run.sh test)      # correctness/contract smoke
(cd benchmarks/<NN-name>/harness && ./run.sh test)
bash tools/workload_tests/run_common.sh              # shared lifecycle + framed-TCP regressions (benchmarks/common)
(cd benchmarks/<NN-name>/app && ./mvnw -B test)     # every workload; Maven 3.9.11 wrapper, distribution SHA-256 pinned
(cd benchmarks/<NN-name>/harness && ./mvnw -B test)  # every harness
```

`run.sh` scripts compile with plain `javac` and download pinned jars from Maven Central with `curl`. They are smoke entry points; authoritative orchestration goes through `benchctl`. The experiment files are JSON written with a `.yaml` extension (JSON is valid YAML), so the controller needs no YAML library.

## Layout

| Path | What it is |
|---|---|
| `benchctl`, `tools/benchctl*.py` | Controller: runtime discovery, capability probes, planning, execution, validation, comparison, reporting |
| `tools/loadgen/` | Shared open-loop, coordinated-omission-safe HdrHistogram load generator |
| `tools/telemetry.py`, `tools/compare_results.py` | Application-process telemetry; bootstrap-CI comparison |
| `benchmarks/NN-*/app` | Workload application (never imports harness code) |
| `benchmarks/NN-*/app/contract/` | The only interface between app and harness (OpenAPI/schemas) |
| `benchmarks/NN-*/harness` | Workload-specific harness |
| `benchmarks/common/` | Shared app-side utilities: JSON, process identity/port publication, owned child-JVM lifecycle, bounded persistent TCP sessions |
| `schemas/` | Versioned experiment, runtime and result JSON schemas |
| `experiments/` | Reproducible experiment manifests |
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
- Never apply a JVM flag or collector without probing that the runtime supports it. Plan JDK/GC combinations from discovered capabilities and each workload's `min_jdk`/`max_jdk`.
- Child JVMs (`benchmarks/common`): bind loopback only, use ephemeral ports, verify PID + run token + role on readiness, set heap explicitly, and always reap children and temp files on failure or shutdown. Shared code must compile on Java 17.
- Pin every dependency and tool version. Never commit build outputs, downloaded jars, logs, PIDs, JFRs or results.
- A command called `test` must run tests. Every workload's `mvn test` runs its `run.sh test` gates, so the Maven lifecycle is the authoritative check.
- Executable scripts (`benchctl`, `run.sh`, `tools/**/*.sh`) must keep their executable bit in git (`git update-index --chmod=+x`); CI calls them directly.
- Don't add `CLAUDE.md`/`STATUS.md`-style files; the hygiene check rejects them. Status belongs in `IMPLEMENTATION_STATUS.md` and `catalog.json`.

## Git workflow and done

- Branch from `main`, open a PR; never push to `main`.
- Before pushing: controller unit tests, `check_repository.py`, all four `benchctl validate` runs, and `run.sh test` for every workload you touched.
- If a change alters a workload's real capability, update its tier in `catalog.json`, its `IMPLEMENTATION_NOTES.md` and `IMPLEMENTATION_STATUS.md` in the same PR. Keep PR plans and checklists in the PR description, not in this file.
