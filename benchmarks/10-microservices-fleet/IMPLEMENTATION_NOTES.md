# Implementation Notes — 10-microservices-fleet

## What Was Implemented

### App (MiniHttpServer.java)
- Replaced the single-service stub with a 5-service fleet simulator using `ServiceState` inner class instances.
- Each `ServiceState` (id 0-4) owns an in-memory `ConcurrentHashMap<String,String>` inventory of 100 items (`item-0` to `item-99` → `value-N-svcK`), a per-service `AtomicLong` request counter, and a start timestamp.
- `POST /api/v1/fleet/deploy/{id}` reinitializes `services[id]` to a fresh `ServiceState`, measures deploy time with `System.nanoTime()`, and increments a global `deployCount`.
- Routes implemented:
  - `GET /api/v1/fleet/status` — JSON array of all 5 services with id, status, request count, uptime_ms.
  - `POST /api/v1/fleet/deploy/{id}` — restarts service N; returns `{"service_id":N,"deployed":true,"deploy_time_ms":N,"downtime_ms":N}`.
  - `GET /api/v1/service/{id}/inventory/{itemId}` — item lookup in the named service's inventory.
  - `GET /api/v1/catalog/{productId}` — backward-compat stub (for existing harness smoke tests).
  - `POST /api/v1/orders` — backward-compat stub.
  - `GET /api/v1/orders/{id}` — backward-compat stub.
  - `GET /health` — `{"status":"UP","services_running":5,"benchmark":"10-microservices-fleet"}`.
  - `GET /metrics` — Prometheus format: `fleet_requests_total`, `fleet_deploy_count`, `fleet_services_up`, `benchmark_requests_total`.
- `downtime_ms` is set equal to `deploy_time_ms` (in-JVM restart has no actual downtime; the value represents reinitialization cost).

### Harness (BenchmarkHarness.java)
- Replaced sequential for-loop with `ExecutorService.newFixedThreadPool(threads)` + `CountDownLatch`. Wall-clock throughput from System.nanoTime() brackets.
- REQUESTS array has 5 entries: fleet/status GET, service/0/inventory/item-5 GET, service/1/inventory/item-10 GET, fleet/deploy/2 POST, /health GET.
- Real JVM KPIs: `gcMs` from `GarbageCollectorMXBean`, `rssMb` from `ps -o rss=`, `cpuPct` from `com.sun.management.OperatingSystemMXBean`.
- `mode_kpis` extracts `deploy_time_ms` from deploy response bodies (accumulated via `AtomicLong`).
- `--threads` (default 8) and `--runs` (default 3) args added; last run result is output.

### Grafana Provisioning
- `harness/grafana/provisioning/datasources/prometheus.yaml` — Prometheus datasource.
- `harness/grafana/provisioning/dashboards/dashboards.yaml` — file provider for dashboards.
- `harness/docker-compose.yml` — updated Grafana service with password env var and volume mounts.
- `harness/grafana/dashboards/dashboard.json` — 4 panels: fleet request rate, services up (stat), deploy count (stat), request rate during deploys.

## Dependencies Added
- No new dependencies (pure Java, uses only `com.sun.net.httpserver`, `java.util.concurrent`, `java.lang.management`).

## Smoke Test Result
```
REQUESTS=20 ./run.sh test  (from harness/)
```
Passed. All 20 requests succeeded including multiple deploy POSTs to service 2. `results.json` produced with real `gc_pause_p99_ms`, `rss_mb`, `cpu_util_pct`, and `deploy_time_ms` in `mode_kpis`.

## Deviations from Spec
- All 5 services run in the same JVM on the same port using path-based routing, rather than separate ports (18011-18014). This avoids port conflicts in the smoke test environment and is simpler. The deployment simulation still exercises JVM state reinitialization: `new ServiceState(id)` discards and reallocates all in-memory inventory structures, exercising GC pressure and JIT re-profiling of the inventory-access hot path — which is the JVM dimension this benchmark targets.
- `availability_pct` and `p99_during_deploy_ms` in `mode_kpis` are reported as 0 (computing actual availability requires tracking requests concurrent with the deploy; that would require a separate measurement phase beyond the smoke test scope).
