# CLAUDE.md — benchmark-2-microservices-fleet-harness

## Overview

Test harness for the microservices fleet. Includes Gatling load tests,
Kubernetes rolling deployment scripts, and metric collection. Connects to
the fleet via the Gateway service HTTP endpoint only.

## JDK Target: 17

## Project Structure

```
benchmark-2-microservices-fleet-harness/
├── pom.xml
├── docker-compose.yml              # full stack: app services + monitoring
├── k8s/
│   ├── rolling-deploy.sh
│   ├── scale-fleet.sh
│   └── monitoring/
│       ├── prometheus-config.yml
│       └── grafana-dashboards/
├── src/main/scala/com/opthub/bench/harness/
│   ├── OrderFlowSimulation.scala
│   ├── CatalogBrowseSimulation.scala
│   ├── RollingDeploySimulation.scala
│   └── FleetWarmupSimulation.scala
├── scripts/
│   ├── run-rolling-deploy-test.sh
│   ├── run-steady-state.sh
│   └── collect-fleet-metrics.sh
├── test-data/
│   ├── products.csv
│   ├── customers.csv
│   └── order-templates.json
└── results/
```

## Key Simulation: Rolling Deploy Test (`RollingDeploySimulation`)

Feeds `products.csv` + `customers.csv` randomly. Each virtual user:
1. `GET /api/v1/catalog/${productId}` (browse).
2. Pause 100–500 ms.
3. `POST /api/v1/orders` with a single item.

Load profile: ramp 10→200 rps over 60 s, then hold 200 rps for 600 s.
The rolling restart runs externally during the sustained phase.

## Rolling Deploy Script (`k8s/rolling-deploy.sh`)

Iterates over all 5 services (`gateway`, `order-service`,
`inventory-service`, `pricing-service`, `audit-service`). For each:
1. `kubectl -n bench-fleet rollout restart deployment $SERVICE`
2. Wait for rollout to complete (timeout 120 s).
3. Sleep 30 s to let the new pod reach steady-state JIT optimisation.

## What to Measure

- Fleet-wide p99 latency during rolling deploy vs steady state.
- Per-pod time-to-clear-optimisation-backlog (TTCOB).
- Total CPU across fleet during deploy (compilation overhead).
- Error rate during pod transitions.

## What NOT to Do

- Do NOT import or depend on any service's source code — harness connects
  via HTTP to the gateway only.
- Do NOT run Gatling from inside any service container.
- Do NOT measure in the same JVM as any service.
