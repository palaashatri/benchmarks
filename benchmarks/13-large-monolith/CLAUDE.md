# CLAUDE.md — Benchmark 13: Large Monolith with Frequent Restarts

> Suite conventions live in the repo-root `../../CLAUDE.md`. **Read that first.**
> This file covers only what is specific to this benchmark and how its two
> halves fit together.

## Scenario

A deliberately large Spring Boot monolith (500+ beans, 100+ JPA entities,
12 business rules, 6 scheduled jobs) representing enterprise Java applications
such as those at Salesforce and Unity. The benchmark stresses classloading
and JIT compilation surface area by maximising the number of methods that
must be compiled from cold, then measures the application's behaviour under
frequent restarts — e.g., CI/CD pipelines that deploy multiple times per day.

## How the pieces fit (and why they are split)

```
13-large-monolith/
  CLAUDE.md      <- you are here
  app/           <- the shippable monolith (Maven, JDK 17, no harness deps)
  harness/       <- restart orchestration, load gen, warm-up curve capture
```

- `app/` is a standalone Spring Boot application. It builds and runs on
  **JDK 17** with no harness on the classpath.
- `harness/` drives load via HTTP and orchestrates restart cycles while
  measuring time-to-first-request, time-to-steady-throughput, and GC
  behaviour during each warm-up phase.

## JVM dimensions this benchmark exists to stress

- **Classloading surface** — 500+ beans, 100+ Hibernate-mapped entities,
  deep class hierarchies; startup time and initial JIT compilation queue
  depth are primary signals.
- **JIT compilation backlog under load** — requests arrive before the JIT
  has compiled the hot paths; compiled-method count vs time and CPU-util
  spike are tracked.
- **Polymorphic rule engine** — 12 sealed `BusinessRule` implementations
  evaluated per order; JIT must profile and inline across the full set.
- **GC at enterprise scale** — Hibernate ORM generates complex object graphs;
  allocation rate and GC pause behaviour during warm-up are compared across
  G1, ZGC, and Shenandoah.

## Current scope

OpenJDK HotSpot 17. CRaC and GraalVM native-image are deferred; they are
the motivation for the harness seam but are not activated yet.

## Run order for a full benchmark pass

1. From `app/`: `docker-compose up -d` (Postgres, Redis, Kafka), then
   `mvn spring-boot:run`.
2. From `harness/`: start load, record time-to-first-successful-response
   and time-to-reach-90%-steady-throughput.
3. Kill and restart the app (simulating a deploy); repeat N times to get a
   distribution of warm-up curves.
4. Inspect `harness/results/` and the Grafana dashboard.

## Comparative questions the results should answer

- How long does the monolith take to reach 90% of steady-state throughput
  after a cold start?
- How does the JIT compilation CPU spike affect p99 during warm-up?
- What is the per-restart cost in user-facing latency (area under the
  warm-up curve)?
- Does ZGC/Shenandoah reduce warm-up tail latency vs G1 on this workload?
