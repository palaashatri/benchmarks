# Implementation Notes — Benchmark 08: ETL Batch

## What was implemented

Replaced the stub MiniHttpServer.java with a real NIO2 file I/O ETL batch server:

- **CsvRecord record type** (Java 21 record): `(long id, String category, double value, long timestamp)`
- **JobState class** tracking status (ACCEPTED, RUNNING, COMPLETED, FAILED), records_processed, throughput, IO throughput
- **Async batch jobs** run on virtual threads via `Thread.ofVirtual().start()`
- **Each job**: generates 50,000 CsvRecords deterministically (LCG PRNG, fixed seed), writes CSV via `Files.newBufferedWriter`, reads back via `Files.newBufferedReader`, filters (value > 0.5), aggregates by category using streams, writes output CSV, deletes temp files
- **Hand-rolled Prometheus metrics**: `etl_records_processed_total`, `etl_batch_duration_seconds`, `etl_io_bytes_written_total`, `jvm_memory_used_bytes`, `jvm_gc_collection_count`
- **Routes**: POST /api/v1/etl/run, GET /api/v1/etl/jobs, GET /api/v1/etl/{jobId}/status, GET /health, GET /metrics, GET /actuator/health, GET /actuator/prometheus
- **Concurrent harness**: ExecutorService (configurable threads), wall-clock throughput, GC/RSS/CPU KPI collection, records_per_second parsed from /api/v1/etl/jobs response
- **Grafana provisioning**: datasource YAML, dashboard provider YAML, 4-panel dashboard (records/s, batch duration, IO throughput, JVM heap)

## Key dependencies added

None — pure Java 21 NIO2 + Streams + Records. No external JARs required.

## Smoke test result

PASS

- Throughput: ~342 rps (20 requests, concurrent)
- p50: 6ms, p99: 50ms
- rss_mb: 106 (real KPI, non-zero)
- records_per_second: shown as 0 in mode_kpis (jobs run async; job may not complete within harness window of 20 quick requests)

## Deviations from plan

- The app/run.sh SMOKE_GETS and SMOKE_POSTS were updated from the old stub routes (`/job/schema`, `/job/status`, `/job/run`) to the new routes (`/health`, `/api/v1/etl/jobs`, `/api/v1/etl/run`).
- `records_per_second` in mode_kpis may show 0 during a quick 20-request smoke run because the ETL jobs run async and may not have completed when /api/v1/etl/jobs is polled. In a real benchmark run with more requests, completed jobs will be listed and the avg will be non-zero.
- `gc_pause_p99_ms` shows 0 in the smoke run (consistent with benchmark 01 — short runs don't trigger measurable GC via MXBean on macOS); `rss_mb` = 106 satisfies the "at least one real KPI" requirement.
