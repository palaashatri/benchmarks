# CLAUDE.md — Massive-Concurrency Chat / Collaboration Server (Loom) · APPLICATION

This is the **shippable application** for benchmark 06. It is a standalone
project: it must build, test, and run on **OpenJDK 21** with no benchmark
harness anywhere on its classpath, and it can be lifted out of the suite and
shipped as its own product using only this file.

## The one rule for this directory

**Never add a dependency on, or an import from, `../harness/`.** If you need
something the harness has, either it belongs behind this app's external
contract, or it is a measurement concern that stays in the harness. Adding a
harness reference here breaks the ability to ship this app.

## What it is

A stateful WebSocket chat/collaboration backend implemented twice — Loom (one virtual thread per connection, blocking) and NIO event-loop (Netty) — to push per-connection memory, scheduler scalability, and GC on long-lived session graphs toward millions of connections.

## Stack

Java 21; WebSocket (or HTTP/2 server push). Two implementations behind one contract: Loom (vthread/connection, blocking I/O) and NIO (Netty + shared pools). Rooms/channels, presence, broadcast, optional recent-history persistence. Micrometer.

## Responsibilities

- Authenticate user sessions; track rooms/channels and presence.
- Broadcast messages to rooms; optionally persist recent history.
- Provide BOTH a Loom and an NIO implementation of the same protocol, switchable by build flag.

## External contract (the only surface the harness may use)

Published under `contract/`:
- `contract/protocol.md` — WebSocket message protocol (connect, join, leave, broadcast, presence).
- `contract/openapi.yaml` — the auth + session bootstrap REST surface.

Change `contract/` first when the surface changes; everything downstream
(harness clients, generated stubs) follows from it.

## Build & run (OpenJDK 21)

```bash
./gradlew build        # compile + unit tests
./gradlew test         # tests only
./gradlew run -Pserver=loom     # or -Pserver=nio
```

JDK is pinned via the Gradle toolchain (`JavaLanguageVersion.of(21)`); do not
depend on the host `JAVA_HOME`.

## External dependencies

Optional store for recent-history persistence; none required for the core in-memory path.

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
