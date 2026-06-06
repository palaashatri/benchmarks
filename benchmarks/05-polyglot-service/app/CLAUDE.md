# CLAUDE.md — Polyglot Service vs Pure-Java Baseline · APPLICATION

This is the **shippable application** for benchmark 05. It is a standalone
project: it must build, test, and run on **OpenJDK 21** with no benchmark
harness anywhere on its classpath, and it can be lifted out of the suite and
shipped as its own product using only this file.

## The one rule for this directory

**Never add a dependency on, or an import from, `../harness/`.** If you need
something the harness has, either it belongs behind this app's external
contract, or it is a measurement concern that stays in the harness. Adding a
harness reference here breaks the ability to ship this app.

## What it is

A business service that delegates one piece of logic (pricing rules / transform) to an embedded script, compared against an equivalent pure-Java implementation — to quantify polyglot engine overhead. NOTE: meaningful polyglot numbers need GraalVM; under OpenJDK-now we build the baseline + seam only.

## Stack

Java 21; HTTP API; Java orchestration (DB + cache). Business logic behind a `ScriptEngineAdapter` interface with two impls: `PureJava` (baseline) and `Polyglot` (GraalVM Truffle JS/Python). **Under OpenJDK-now only `PureJava` is active**; the polyglot impl compiles against the adapter but is gated off (no `org.graalvm` dependency on the app classpath yet).

## Responsibilities

- Expose an HTTP API; orchestrate data from a DB + cache in Java.
- Delegate a unit of business logic (pricing rules / data transform / simple analytics) through `ScriptEngineAdapter`.
- Ship the pure-Java implementation as the shippable default; keep the polyglot implementation behind the adapter seam for the later GraalVM phase.

## External contract (the only surface the harness may use)

Published under `contract/`:
- `contract/openapi.yaml` — the HTTP surface (identical across baseline and polyglot).
- `contract/logic-spec.md` — the business-logic unit, defined language-neutrally so the Java and script versions are provably equivalent.
- `contract/adapter.md` — the `ScriptEngineAdapter` SPI the polyglot impl will satisfy.

Change `contract/` first when the surface changes; everything downstream
(harness clients, generated stubs) follows from it.

## Build & run (OpenJDK 21)

```bash
./gradlew build        # compile + unit tests
./gradlew test         # tests only
./gradlew run                 # PureJava baseline. Polyglot impl is gated: -Pengine=polyglot is a no-op until the GraalVM phase.
```

JDK is pinned via the Gradle toolchain (`JavaLanguageVersion.of(21)`); do not
depend on the host `JAVA_HOME`.

## External dependencies

DB + cache. No GraalVM dependency now — that is added in the deferred phase, in the harness runtime registry, never silently on the app classpath.

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
