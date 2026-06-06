# CLAUDE.md — Serverless / CRaC / Native-Image Cold-Start Suite · APPLICATION

This is the **shippable application** for benchmark 07. It is a standalone
project: it must build, test, and run on **OpenJDK 21** with no benchmark
harness anywhere on its classpath, and it can be lifted out of the suite and
shipped as its own product using only this file.

## The one rule for this directory

**Never add a dependency on, or an import from, `../harness/`.** If you need
something the harness has, either it belongs behind this app's external
contract, or it is a measurement concern that stays in the harness. Adding a
harness reference here breaks the ability to ship this app.

## What it is

A small set of serverless-style functions runnable in multiple launch modes, to measure cold-start, warm-invocation latency, and footprint. NOTE: CRaC + native-image are the point of this benchmark but are OUT OF SCOPE now; OpenJDK-now delivers the functions + HotSpot cold/warm harness behind a LaunchMode seam.

## Stack

Java 21; a few small functions (CRUD on a tiny dataset, image resize/thumbnail, JSON transform, lightweight inference), each a self-contained deployable. A `LaunchMode` seam abstracts how a function is started: `hotspot` (active now), `crac` and `native` (defined, deferred).

## Responsibilities

- Implement each function as a minimal, independently deployable unit.
- Expose a uniform invocation entrypoint so the harness can launch new-process cold starts and reuse warm processes identically across functions.
- Keep all launch-mode specifics behind `LaunchMode`; the function logic is mode-agnostic.

## External contract (the only surface the harness may use)

Published under `contract/`:
- `contract/functions.md` — each function's input/output and invocation entrypoint.
- `contract/launchmode.md` — the `LaunchMode` SPI (hotspot now; crac/native are documented stubs).

Change `contract/` first when the surface changes; everything downstream
(harness clients, generated stubs) follows from it.

## Build & run (OpenJDK 21)

```bash
./gradlew build        # compile + unit tests
./gradlew test         # tests only
./gradlew run -Pfn=json-transform -Pmode=hotspot   # mode=crac / mode=native are gated until the CRaC/native phase
```

JDK is pinned via the Gradle toolchain (`JavaLanguageVersion.of(21)`); do not
depend on the host `JAVA_HOME`.

## External dependencies

None for the HotSpot path. The CRaC path will require a CRaC-enabled JDK + CRIU (Linux, CAP_CHECKPOINT_RESTORE/CAP_SYS_PTRACE); the native path will require the GraalVM native toolchain. **Neither is added now** — the app stays buildable with stock OpenJDK 21.

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
