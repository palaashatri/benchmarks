# CLAUDE.md — ML Inference & Feature Pipeline (Panama & Vector) · APPLICATION

This is the **shippable application** for benchmark 04. It is a standalone
project: it must build, test, and run on **OpenJDK 21** with no benchmark
harness anywhere on its classpath, and it can be lifted out of the suite and
shipped as its own product using only this file.

## The one rule for this directory

**Never add a dependency on, or an import from, `../harness/`.** If you need
something the harness has, either it belongs behind this app's external
contract, or it is a measurement concern that stays in the harness. Adding a
harness reference here breaks the ability to ship this app.

## What it is

An online inference service whose model-execution path can be pure-Java, JNI, or Panama FFM, with hot numeric kernels in scalar vs Vector API form — to measure FFI overhead and SIMD gains in a real service with allocation, parsing, and GC in the mix.

## Stack

Java 21; REST + gRPC (single + batched inference). Feature computation in Java (joins, normalisation, encodings, vector math on float arrays). Three model paths behind one `ModelRuntime` interface: pure-Java, JNI, FFM (`java.lang.foreign`). Kernels in scalar and `jdk.incubator.vector` form.

## Responsibilities

- Serve single and batched inference over REST/gRPC.
- Compute features in Java; provide scalar AND Vector-API implementations of hot kernels (dot product, cosine similarity) selected by config.
- Execute the model via one of three runtimes behind `ModelRuntime`: pure-Java (e.g. ONNX Runtime Java / XGBoost4J), JNI, or FFM.

## External contract (the only surface the harness may use)

Published under `contract/`:
- `contract/openapi.yaml`, `contract/inference.proto` — single + batch inference surfaces.
- `contract/model.md` — the model file format + feature schema (same model across all three runtimes).
- `contract/runtimes.md` — how `ModelRuntime` (java|jni|ffm) and kernel mode (scalar|vector) are selected.

Change `contract/` first when the surface changes; everything downstream
(harness clients, generated stubs) follows from it.

## Build & run (OpenJDK 21)

```bash
./gradlew build        # compile + unit tests
./gradlew test         # tests only
./gradlew run -Pmodel=ffm -Pkernel=vector   # JDK 21: FFM is preview -> build adds --enable-preview; Vector API is incubator -> --add-modules jdk.incubator.vector
```

JDK is pinned via the Gradle toolchain (`JavaLanguageVersion.of(21)`); do not
depend on the host `JAVA_HOME`.

## External dependencies

A native model library for the JNI/FFM paths (the pure-Java path needs none). **Version note:** FFM (`java.lang.foreign`) is *preview* in JDK 21 (JEP 442) and *final* in JDK 22 (JEP 454); Vector API stays *incubator*. Keep both behind the adapter so a final-API JDK swaps in without touching callers. Standalone shipping with preview features must document `--enable-preview` for consumers.

## Observability (built in, harness-independent)

- Prometheus metrics at `/metrics` (Micrometer): business + JVM + infra metrics.
- Structured JSON logging, one event per line.
- JFR-ready: the app does nothing that blocks `-XX:StartFlightRecording`. It
  **does not hard-code a garbage collector** — GC selection is supplied
  externally (by the harness, or by whoever runs the shipped app).

## Standalone shipping checklist

- Builds and tests green with only OpenJDK 21 present.
- No `../harness` references anywhere (grep before you commit).
- `contract/` is complete and matches the running surface.
- README/run instructions reproducible from this file alone.

## Code style

Java 21 records / sealed types / pattern matching. No Lombok. Pin dependency
versions in the per-build version catalog.
