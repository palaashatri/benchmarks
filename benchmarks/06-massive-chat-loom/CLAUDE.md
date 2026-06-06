# CLAUDE.md — Benchmark 06: Massive-Concurrency Chat / Collaboration Server (Loom)

> Suite conventions live in the repo-root `../../CLAUDE.md`. **Read that first.**
> This file covers only what is specific to this benchmark and how its two
> halves fit together.

## Scenario

A stateful WebSocket chat/collaboration backend implemented twice — Loom (one virtual thread per connection, blocking) and NIO event-loop (Netty) — to push per-connection memory, scheduler scalability, and GC on long-lived session graphs toward millions of connections.

## How the pieces fit (and why they are split)

```
06-massive-chat-loom/
  CLAUDE.md      <- you are here
  app/           <- the SHIPPABLE application (its own Gradle build, no harness deps)
  harness/       <- load gen, metrics capture, KPI extraction, runtime registry
```

- `app/` is a standalone project. It builds, tests, and runs on **OpenJDK 21**
  with no harness on the classpath, and can be copied out of this repo and
  shipped using only `app/CLAUDE.md`.
- `harness/` drives `app/` exclusively through its **external contract** (see
  `app/contract/`). It never imports an app internal class.
- When the contract changes, edit `app/contract/` first; the harness regenerates
  from it. A harness change must never force an app change.

## JVM dimensions this benchmark exists to stress

- **Millions of connections** — per-connection memory, scheduler scalability, GC with many long-lived session objects.
- **Contention on shared state** — presence maps, room membership, broadcast queues: coarse locks vs lock-free structures.
- **GC under long-lived object graphs** — stable young-gen but old-gen management + fragmentation become the story.

## Current scope (OpenJDK only)

Loom vs NIO is the live axis (Loom GA in 21, no preview flags). GraalVM comparison deferred to the harness registry.

## Run order for a full benchmark pass

1. From `harness/`: bring up dependencies — `docker-compose up -d`.
2. From `app/`: build and start the application (see `app/CLAUDE.md`).
3. From `harness/`: run the load profiles and collect results (see `harness/CLAUDE.md`).
4. Inspect `harness/results/*.json` (normalised schema in the suite root) and
   the Grafana dashboard.

## Comparative questions the results should answer

- Does Loom allow orders-of-magnitude more connections at manageable memory/CPU vs NIO?
- At what point do GC/scheduler overheads bottleneck virtual threads?
