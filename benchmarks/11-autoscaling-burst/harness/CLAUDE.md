# CLAUDE.md — benchmark-3-autoscaling-api-harness


> **Current implementation status:** this directory currently contains a local smoke-test harness scaffold, not the full load-generation and observability harness described below. The scaffold exists so `run.sh build` / `run.sh test` can produce schema-shaped smoke results. The full harness described in this file remains the target and should replace the scaffold incrementally. See `../../../IMPLEMENTATION_STATUS.md`.

## Overview

Load harness that simulates flash-sale burst traffic patterns against the
catalog API. Designed to trigger HPA scale-up and measure new pod warm-up
time. Connects to the app via HTTP only.

## JDK Target: 17

## Tech Stack

- k6 (Go-based load generator), shell scripts, Prometheus, Grafana

## Project Structure

```
benchmark-3-autoscaling-api-harness/
├── k6/
│   ├── flash-sale.js
│   ├── steady-browse.js
│   └── scale-up-measure.js
├── scripts/
│   ├── run-flash-sale.sh
│   ├── watch-hpa.sh
│   ├── measure-pod-readiness.sh
│   └── run-comparison.sh
├── prometheus/
│   └── prometheus.yml
├── grafana/
│   └── dashboards/
│       └── autoscale-benchmark.json
└── results/
```

## Key Load Test: Flash Sale (`k6/flash-sale.js`)

Stages:
| Stage    | Duration | Target VUs |
|----------|----------|-----------|
| Normal   | 2 min    | 50        |
| Burst    | 30 s     | 2 000     |
| Sustained| 5 min    | 2 000     |
| Cool-down| 1 min    | 50        |

Thresholds: `http_req_duration p(99) < 1000 ms`, `errors rate < 5%`.

Each VU hits `GET /api/v1/products/{productId}?region=US&customerId=cust-{n}`
with a random product ID in [1, 50000] and a random customer in [1, 1000].
Checks: status 200, `finalPrice > 0`.

Base URL: `$BASE_URL` env var (default `http://localhost:8080`).

## Pod Readiness Measurement (`scripts/measure-pod-readiness.sh`)

Watches `kubectl get pods -w` for `ADDED` events. For each new pod, records
the creation timestamp and polls `kubectl exec ... curl -sf
http://localhost:8080/actuator/health` until it succeeds. Reports elapsed
milliseconds from pod creation to first healthy response.

## What NOT to Do

- Do NOT import or depend on the application source code.
- Do NOT use Gatling for this benchmark — k6 is used because it scales to
  2000 VUs more efficiently for the flash-sale burst shape.
- Do NOT pre-warm the app before running the flash-sale test.
