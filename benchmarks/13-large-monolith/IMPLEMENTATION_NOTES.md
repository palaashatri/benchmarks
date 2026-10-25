# Implementation Notes — Benchmark 13: Large Monolith

## What it is

A synthetic monolith: **1,000 generated components** (20 modules of 50) wired by a reflective
constructor-injection container at startup, then traversed once per request. It exercises the
costs a large application pays at runtime: class loading of many classes, reflective object
graph construction, constructor-parameter annotations, and deep call chains through application
classes.

It is **not** a production framework. There is no Spring, no persistence, no transactions and no
restart distribution. The benchmark claim is limited to the container and traversal costs above.

## How it is built

- `app/tools/GenerateGraph.java` (single-file Java) writes the component sources and a `Manifest`.
  Each component is `Node_mXX_rYY` with `@Bean("mXX-rYY")`. A component depends on:
  - its primary: the previous row of its module (`rYY-1`), absent on row 0;
  - its secondary: the same row of the previous module (`mXX-1`), absent in module 0.
  Dependencies only point backwards, so the graph is acyclic.
- the Maven build runs the generator, compiles the hand-written sources with the generated ones
  (`javac --release 17`) and writes `build/run-sh/classpath.txt`.
- Maven runs the same generator in `generate-sources` and adds its output as a source root.

## Runtime

- `Container` instantiates every bean once, dependencies first, from the public constructor.
  Constructor parameters are annotated `@Dep("...")`. Duplicate beans, missing dependencies and
  cycles fail startup.
- Each component's `seed()` is derived from its tag and its dependencies' seeds, so the wiring
  determines the seeds.
- `apply(value)` passes the value down the primary chain. One traversal visits every component
  once: each module tail is applied in turn.
- `/api/v1/monolith/work` returns the traversal result and `components_visited`. Components visited
  is structural (the chain length), not sampled per call.

## Correctness

`./mvnw -B test` checks:
- every component is wired (`wired_components == components`);
- the Java reference result (computed at startup from the wired graph) equals `expected_result`,
  which the generator computes in Python from its own model of the wiring. A container that wires
  the graph differently fails here. This was checked by mutating the generator's seed derivation
  in a scratch copy: the test failed with the two results differing;
- ten traversals each return `expected_result` and visit every component;
- `/metrics` exposes the component gauge.

`mvn test` runs the same script (`exec` in the `test` phase). The harness smoke runs through
the workload harness Maven test, which uses the shared functional client.

## Routes

- `GET /health`, `GET /runtime` (identity: pid, run token, Java version), `GET /metrics`
- `GET /api/v1/monolith/graph` — shape, reference result, expected result
- `GET /api/v1/monolith/work` — one traversal
- `GET /api/v1/monolith/warmup/status` — startup, loaded classes and JIT time; `jit_compilation_ms`
  is `null` when the JVM does not report compilation time

## Measurement path

Tier 2 through the shared engine (`tier2.json`, `app/contract/load-mix.tsv`): the engine builds the
app with the Maven build, checks identity on `/runtime`, runs the correctness gate and drives the
load mix with open-loop HdrHistogram load. The mix is 95% traversals and 5% graph reads.

No measurement-valid result exists. Validity needs the controlled-hardware run described in
`IMPLEMENTATION_STATUS.md`.

## Limits

- The graph is generated, so its shape is fixed at build time (`--modules`, `--depth`). Changing
  it means regenerating, not reconfiguring at runtime.
- Each request traverses the whole graph, so per-request cost scales with component count by
  design. Throughput numbers only mean something relative to the same graph size.
- The container is a small reflective stand-in, not a framework; it does not model scopes, proxies,
  lifecycle callbacks or configuration binding.
