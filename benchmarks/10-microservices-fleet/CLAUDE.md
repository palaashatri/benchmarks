# CLAUDE.md — Benchmark 10: Microservices Fleet with Rolling Deployments

> Suite conventions live in the repo-root `../../CLAUDE.md`. **Read that first.**
> This file covers only what is specific to this benchmark and how its two
> halves fit together.

## Scenario

A 5-service microservices fleet simulating an e-commerce backend (gateway,
order, inventory, pricing, audit). Each service is a standalone Spring Boot
application. The primary stress is JVM warm-up during rolling deployments:
a pod is killed and replaced while load runs continuously, so the benchmark
measures the cost of JIT recompilation and heap warmup per pod restart.

## How the pieces fit (and why they are split)

```
10-microservices-fleet/
  CLAUDE.md      <- you are here
  app/           <- the 5-service fleet (Maven multi-module, no harness deps)
  harness/       <- Gatling load tests, rolling-deploy scripts, monitoring
```

- `app/` is a standalone Maven multi-module project. Each service builds to
  its own Docker image and exposes its own port. The harness touches the fleet
  only through the **gateway service's HTTP endpoint**.
- `harness/` orchestrates load + rolling restarts + metric collection. It
  never imports any service's internal classes.

## JVM dimensions this benchmark exists to stress

- **Rolling-deploy warm-up** — per-pod JIT compilation backlog and GC
  pressure in the 0–120 s window after a pod restart under live traffic.
- **Polymorphic JIT profiling** — the pricing engine dispatches through 8
  sealed-interface rule implementations per request; megamorphic call-site
  optimisation is the key JIT dimension.
- **Fleet-wide GC coordination** — 5 JVMs running simultaneously; CPU
  budget for GC + JIT compilation competes with request processing.

## Current scope

OpenJDK HotSpot 17. Kubernetes rolling deploy is modelled with
`kubectl rollout restart`; the harness includes a Docker Compose path for
laptop-scale runs.

## Run order for a full benchmark pass

1. From `app/`: `docker-compose up -d` to start infrastructure (Postgres,
   Redis, Kafka), then build and start each service.
2. From `harness/`: `docker-compose up -d` to start Prometheus + Grafana.
3. Run `harness/scripts/run-rolling-deploy-test.sh` — it starts Gatling
   load and, in parallel, calls `k8s/rolling-deploy.sh` to restart pods
   one at a time.
4. Inspect `harness/results/` and the Grafana dashboard.

## Comparative questions the results should answer

- What is the fleet-wide p99 latency spike during a rolling restart vs
  steady state?
- How long does each new pod take to clear its JIT compilation backlog
  (TTCOB — time-to-clear-optimisation-backlog)?
- What is the total CPU overhead of JIT compilation across all 5 pods
  during a deploy?
- What is the error rate during pod transitions, and how does it vary
  by GC configuration?
