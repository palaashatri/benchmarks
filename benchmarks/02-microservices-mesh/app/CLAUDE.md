# CLAUDE.md — Event-Driven Microservices Mesh · APPLICATION

This is the **shippable application** for benchmark 02. It is a standalone
project: it must build, test, and run on **OpenJDK 21** with no benchmark
harness anywhere on its classpath, and it can be lifted out of the suite and
shipped as its own product using only this file.

## The one rule for this directory

**Never add a dependency on, or an import from, `../harness/`.** If you need
something the harness has, either it belongs behind this app's external
contract, or it is a measurement concern that stays in the harness. Adding a
harness reference here breaks the ability to ship this app.

## What it is

A 3–5 service mesh processing events asynchronously, implemented twice over identical logic — once reactive (non-blocking), once on Loom (virtual threads + blocking style) — to compare concurrency, scheduling, and GC in message storms.

## Stack

Java 21; two interchangeable stacks: reactive (Spring WebFlux or Vert.x) and Loom (virtual threads + blocking I/O). Kafka (default) / NATS / RabbitMQ transport. Resilience4j for retries/circuit-breakers. Micrometer.

## Responsibilities

- Ingress service: validate + enrich incoming events.
- Aggregation service: join/correlate events.
- Notification service: fan out to external endpoints.
- Optional audit/compliance side-service.
- Provide BOTH a reactive and a Loom implementation of the same pipeline behind one build flag, so logic is identical and only the concurrency model differs.

## External contract (the only surface the harness may use)

Published under `contract/`:
- `contract/topics.md` — Kafka topic names, partitioning, key layout for ingress→sink flow.
- `contract/event-*.avsc` — Avro schemas for each event type.
- `contract/openapi.yaml` — the notification fan-out endpoint shape (harness can stub downstreams).

Change `contract/` first when the surface changes; everything downstream
(harness clients, generated stubs) follows from it.

## Build & run (OpenJDK 21)

```bash
./gradlew build        # compile + unit tests
./gradlew test         # tests only
./gradlew run -Pstack=loom      # or -Pstack=reactive ; each service is a separate Gradle module under app/
```

JDK is pinned via the Gradle toolchain (`JavaLanguageVersion.of(21)`); do not
depend on the host `JAVA_HOME`.

## External dependencies

Kafka (or NATS/RabbitMQ). Downstream notification targets can be the harness stub or real endpoints.

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
