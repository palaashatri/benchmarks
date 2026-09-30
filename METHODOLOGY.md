# Benchmark methodology

## Execution classes

- **Smoke:** verifies build, startup, contract and basic correctness. Never measurement-valid.
- **Calibration:** exercises load and telemetry plumbing while the methodology is still being qualified. Never publication-valid.
- **Benchmark:** permitted only for Tier 2 or Tier 3 workloads with correctness, telemetry, warm-up and statistical gates satisfied.

## Implementation tiers

- **Tier 0:** contract/lifecycle simulator.
- **Tier 1:** production-shaped functional prototype.
- **Tier 2:** measurement-ready workload with application-process telemetry, open-loop load, deterministic data, correctness gates and repeatable phases.
- **Tier 3:** publication-ready execution on controlled hardware with documented variance and environmental controls.

## Runtime policy

The target is OpenJDK HotSpot. Runtime support is discovered from actual executable probes, not inferred from a version table. Unsupported collector and feature combinations are skipped with recorded reasons.

Two lanes are required:

1. **Compatibility lane:** the same Java 8 bytecode runs across compatible JDK 8–25 runtimes.
2. **Feature lane:** dedicated artifacts test features that require later JDKs.

## Load and latency

Measurement-ready latency tests must use an open-loop schedule and account for coordinated omission. HdrHistogram or an equivalent tested recorder is required. The legacy fixed-thread harnesses are not measurement-valid.

## Warm-up

Warm-up is a primary result. Tier 2 workloads must record process start, readiness, first business operation, throughput over time, latency over time, compilation activity, code-cache occupancy, CPU, allocation and GC. Stable throughput detection must be documented and reproducible.

## Telemetry ownership

Telemetry must describe the measured application process. Metrics from the load-generator JVM must never be reported as application JVM metrics. GC pause percentiles must come from event data such as JFR or GC logs; cumulative `GarbageCollectorMXBean` time is not a pause percentile.

## Repetitions and statistics

Benchmark runs require at least five repetitions. Raw repetitions must be preserved. Aggregates must include median, mean, standard deviation, median absolute deviation, coefficient of variation, confidence intervals and explicit outlier reporting.

## Environment

Every Tier 2+ result must include runtime identity, executable identity, JVM flags, collector identity, OS/kernel, CPU topology, memory, cgroups/container limits, CPU governor, SMT/NUMA state where observable, workload artifact hashes and repository state.

## Comparison validity

A comparison is one of: improvement, regression, inconclusive or invalid. Invalid or environmentally incompatible results must not be converted into performance claims.
