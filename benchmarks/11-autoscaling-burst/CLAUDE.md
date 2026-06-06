# CLAUDE.md — Benchmark 11: Autoscaling REST API with Burst Traffic

> Suite conventions live in the repo-root `../../CLAUDE.md`. **Read that first.**
> This file covers only what is specific to this benchmark and how its two
> halves fit together.

## Scenario

A Spring Boot product catalog API with a complex polymorphic pricing engine,
deployed on Kubernetes with HPA. The harness simulates flash-sale burst
traffic (50 → 2000 rps in 30 s) to trigger HPA scale-up events. The
benchmark measures the cost of spinning up new JVM pods mid-burst: cold-start
time, per-pod warm-up latency, and the error rate during the transition.

## How the pieces fit (and why they are split)

```
11-autoscaling-burst/
  CLAUDE.md      <- you are here
  app/           <- the shippable catalog API (Maven, no harness deps)
  harness/       <- k6 load scripts, HPA watch scripts, Prometheus + Grafana
```

- `app/` is a standalone Spring Boot application. It builds and runs on
  **JDK 17** with no harness on the classpath.
- `harness/` drives load via k6 (a Go-based load generator — NOT Gatling)
  against the app's HTTP endpoints. It never imports app internal classes.

## JVM dimensions this benchmark exists to stress

- **Cold-start latency under live traffic** — new pods scaled by HPA receive
  real requests before JIT compilation is complete; pre-warm on startup is
  explicitly forbidden.
- **Polymorphic JIT** — 8 pricing rules evaluated per request; the JIT must
  profile and compile megamorphic call sites under burst load.
- **Heap sizing at scale** — multiple pod replicas (2–20) with independent
  JVMs; per-pod allocation rate and GC overhead are tracked.

## Current scope

OpenJDK HotSpot 17, Kubernetes HPA (CPU target 60%), scale-up policy:
4 pods per 30 s, stabilisation window 15 s. A Docker Compose path is
provided for laptop-scale runs.

## Run order for a full benchmark pass

1. From `app/`: build Docker image, push to registry, apply `k8s/` manifests.
2. From `harness/`: run `scripts/run-flash-sale.sh` — starts k6 with the
   flash-sale load profile.
3. In parallel: `scripts/watch-hpa.sh` monitors pod count; 
   `scripts/measure-pod-readiness.sh` times each new pod from creation to
   first successful health check.
4. Inspect `harness/results/` and the Grafana dashboard.

## Comparative questions the results should answer

- How long after HPA triggers a scale-up event does throughput recover?
- What is the p99 degradation during the 30 s burst ramp and the first
  2 minutes of the sustained burst?
- Does a low-pause GC reduce the warm-up latency on new pods vs G1?
- What is the per-pod warm-up time from container start to steady-state
  throughput?
