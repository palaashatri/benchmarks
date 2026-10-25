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

The shared generator (`tools/loadgen`, `OpenLoopLoad`) takes its request mix from each workload's contract file, so it carries no workload knowledge. Its output is a load envelope with no validity claim. Latency is recorded from the intended send time. Per-second series are bucketed as follows: scheduled and latency by intended send time, delivered throughput by response time. The series cover exactly the warm-up and measurement seconds. Responses that arrive after the last second are counted in that second.

## Warm-up

Warm-up is a primary result. Tier 2 workloads must record process start, readiness, first business operation, throughput over time, latency over time, compilation activity, code-cache occupancy, CPU, allocation and GC. Stable throughput detection must be documented and reproducible.

Implemented detector (`Tier2.java`, `Stats.java`): delivered throughput is stable when five consecutive one-second buckets have a coefficient of variation of at most 5 %. The stable window must lie inside the warm-up phase, so a window that starts only once measurement has begun cannot show that warm-up was sufficient. An empty bucket is never stable. Under an open-loop schedule, delivered throughput normally stays flat, so this gate mainly shows that the system keeps pace with the offered load. Latency settles more slowly, so latency stability (per-second p50, provisional 20 % coefficient of variation) is recorded but not gated until thresholds are calibrated on the target hardware.

## Telemetry ownership

Telemetry must describe the measured application process. Metrics from the load-generator JVM must never be reported as application JVM metrics. GC pause percentiles must come from event data such as JFR or GC logs; cumulative `GarbageCollectorMXBean` time is not a pause percentile. For a workload that starts child JVMs (`telemetry_scope: process-tree`), the application is the whole process tree: the gateway and every descendant. Each JVM in the tree is sampled, each writes its own GC log, and the tree totals are sums and pooled pause distributions across those JVMs. Every child also runs native memory tracking and a `bench` flight recording. As it exits, each child writes its own native memory summary and dumps that recording, after the gateway's shutdown closes its stdin. A child the gateway retires mid-run is captured the same way. The tree's committed native memory and allocation-sample rate are sums across every JVM, and they are null unless every JVM reported its value. A child the gateway reports but the sampler never observed is a validity failure. So is an observed child without a GC log, and an observed child whose native memory or recording is missing. Load-generator telemetry stays out of every total.

## Repetitions and statistics

Benchmark runs require at least five repetitions. Raw repetitions must be preserved. Aggregates must include median, mean, standard deviation, median absolute deviation, coefficient of variation, confidence intervals and explicit outlier reporting.

Implemented: a single repetition has no standard deviation, coefficient of variation or interval, and those fields are `null`. Outliers are screened per repetition for throughput, p99, GC pause and peak RSS, using the modified z-score (threshold 3.5, Iglewicz and Hoaglin) and Tukey fences (1.5 × IQR). Each outlier is reported with its repetition number and method. Outliers are never removed from the aggregate.

## Environment

Every Tier 2+ result must include runtime identity, executable identity, JVM flags, collector identity, OS/kernel, CPU topology, memory, cgroups/container limits, CPU governor, SMT/NUMA state where observable, workload artifact hashes and repository state.

Quiet-machine preflight (`benchctl preflight`, run before and after each measurement). It fails when any of these hold: the CPU governor is not `performance` on every CPU, or it cannot be observed; a JVM option variable (`JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, `_JAVA_OPTIONS`) is set, since it changes the measured JVM outside its recorded flags; the 1-minute load exceeds half the logical CPUs. Warnings cover THP mode, SMT state, cgroup CPU quotas and swap. An unknown value fails its check; it never passes. A failed preflight makes the run invalid, and the envelope records the failing checks.

## Lanes

The startup and chat lanes (06, 07) and the jobs lane (08) run five repetitions, sample the host before and after the lane, and apply the same gates as the Tier 2 engine: run kind, host preflight, repetition count and failures, and critical host state identical across repetitions (`Tier2.sharedReasons`). Each lane adds its own correctness gates and a spread gate: the coefficient of variation of its primary metric across repetitions must not exceed 10 % (`Tier2.spreadReason`, provisional). A failed repetition contributes no value to a statistic, and it is never zeroed.

The jobs lane is open loop. Jobs are due at fixed instants; a job that starts late still counts its full wait from the instant it was due. A schedule that falls behind in any repetition is a lane reason. Benchmark results are refused for workloads whose catalogue tier is below 2. The workloads behind all three lanes are Tier 2, so their envelopes can be measurement-valid once every gate passes on controlled hardware. The Tier 2 engine drives the same job driver for `08` through the `jobs` load shape, so the lane and the engine time jobs identically.

## Comparison validity

A comparison is one of: improvement, regression, inconclusive or invalid. Invalid or environmentally incompatible results must not be converted into performance claims.
