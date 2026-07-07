# Implementation Notes — Benchmark 03: Streaming Analytics Pipeline

## What Was Implemented

### App (`app/`)

Replaced the generic `MiniHttpServer` stub with a real streaming server that exercises JVM concurrency primitives:

- **Event bus**: `LinkedBlockingQueue<StreamEvent>` with capacity 100,000.
- **Consumer threads**: 2 virtual threads (`Executors.newVirtualThreadPerTaskExecutor()`) drain the queue continuously, maintaining tumbling-window state.
- **Windowing**: 10-second tumbling windows keyed by `event.key + ":" + windowStart`. State held in `ConcurrentHashMap<String, WindowState>`.
- **Records**: `StreamEvent(String key, double value, long timestamp)` and `WindowState(String key, long windowStart, long count, double sum, double min, double max)`.

Routes implemented:
- `POST /api/v1/events` — accepts `{"key":"...","value":N}`, enqueues event, returns `{"accepted":true/false,"queue_depth":N}`.
- `GET /api/v1/windows/{key}` — returns latest window state for a key.
- `GET /api/v1/windows` — returns all windows (up to 100).
- `GET /api/v1/lag` — returns `{queue_depth, events_published, events_consumed, consumer_lag}`.
- `GET /health` — returns `{status, queue_depth, windows}`.
- `GET /metrics` — Prometheus text with `streaming_events_published_total`, `streaming_events_consumed_total`, `streaming_consumer_lag`, `streaming_windows_active`, `jvm_available_processors`, `benchmark_requests_total`.

Server executor uses `Executors.newVirtualThreadPerTaskExecutor()` for connection handling.

### Harness (`harness/`)

Upgraded from sequential for-loop to concurrent `ExecutorService`-based load:

- `ExecutorService pool = Executors.newFixedThreadPool(threads)` (default 8).
- `CountDownLatch` coordinates completion; wall-clock time used for throughput calculation.
- 3 runs by default (`--runs 3`); final run results written to `results.json`.
- Added `--threads N` and `--runs N` args.
- Collects: GC total collection time (`ManagementFactory.getGarbageCollectorMXBeans()`), RSS (`ps -o rss=`), CPU (`OperatingSystemMXBean.getProcessCpuLoad()`).
- `mode_kpis` reports `events_per_second`, `window_latency_ms`, `consumer_lag`.

### Grafana (`harness/grafana/`)

- Created `provisioning/datasources/prometheus.yaml` (Prometheus data source auto-wired).
- Created `provisioning/dashboards/dashboards.yaml` (file provider pointing at `/var/lib/grafana/dashboards`).
- Updated `dashboard.json` with 4 panels: Event Throughput, Consumer Lag, Active Windows, Request Rate.
- Updated `docker-compose.yml` to mount provisioning and dashboard volumes; sets `GF_SECURITY_ADMIN_PASSWORD=admin`.

## Dependencies Added

None. The app uses only JDK standard library (`com.sun.net.httpserver`, `java.util.concurrent`).

## Smoke Test Result

```
REQUESTS=40 ./run.sh test
```

Output (final run of 3):
```json
{"benchmark":"03-streaming-analytics","runtime":"openjdk-hotspot-21","gc":"G1",
 "kpis":{"throughput":4666.038,"p50_ms":1,"p99_ms":4,...},
 "mode_kpis":{"events_per_second":4666.038,"window_latency_ms":0,"consumer_lag":0}}
```

All 40 requests succeeded across 3 runs.

## Deviations from Spec

- Virtual threads used for HttpServer executor (per spec) and consumer threads (spec said "virtual threads on startup").
- The `run.sh` SMOKE_GETS/SMOKE_POSTS paths were updated to match the new API routes (`/api/v1/events`, `/api/v1/windows/sensor-1`, `/api/v1/lag`).
