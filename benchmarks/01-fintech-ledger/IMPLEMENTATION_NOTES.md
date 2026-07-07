# Implementation Notes — Benchmark 01: Fintech Ledger

## What was implemented

Replaced the stub MiniHttpServer.java with a real JDBC + HikariCP + Micrometer implementation:

- **HikariCP connection pool** connecting to H2 in-memory DB (`jdbc:h2:mem:ledger;DB_CLOSE_DELAY=-1;MODE=PostgreSQL`)
- **Schema init + seed**: `accounts` and `transactions` tables, 2000 accounts seeded with 1_000_000 balance each
- **Real JDBC transactions** on `POST /transfers`: SELECT FOR UPDATE, fraud score check (hash % 1000, reject if >900), atomic debit/credit with INSERT into transactions
- **Micrometer PrometheusMeterRegistry** with JvmMemoryMetrics, JvmGcMetrics, ProcessorMetrics binders
- **Virtual thread executor** via `Executors.newVirtualThreadPerTaskExecutor()` (Project Loom)
- **Routes**: POST /transfers, GET /accounts/{id}/balance, GET /accounts/{id}/transactions, GET /health (with pool stats), GET /metrics, GET /actuator/health, GET /actuator/prometheus
- **Concurrent harness**: ExecutorService with configurable threads (default 8), wall-clock throughput, real GC/RSS/CPU KPI collection
- **Grafana provisioning**: datasource YAML, dashboard provider YAML, and 4-panel dashboard (throughput, p99 latency, GC pause, DB pool)

## Key dependencies added

The app/run.sh was modified to download JARs from Maven Central at runtime:
- com.h2database:h2:2.2.224
- com.zaxxer:HikariCP:5.1.0
- org.slf4j:slf4j-api:2.0.9 + slf4j-simple:2.0.9
- io.micrometer:micrometer-core:1.13.6
- io.micrometer:micrometer-registry-prometheus:1.13.6
- io.prometheus:simpleclient:0.16.0 + simpleclient_common:0.16.0

## Smoke test result

PASS

- Throughput: ~342 rps (20 requests, concurrent)
- p50: 2ms, p99: 51ms
- rss_mb: 105 (real KPI, non-zero)
- avg_transfer_ms: 18ms

## Deviations from plan

- The micrometer prometheus package in 1.13.6 is `io.micrometer.prometheusmetrics` (not `io.micrometer.registry.prometheus`). Imports adjusted accordingly.
- `gc_pause_p99_ms` shows 0 in the smoke run (20 requests not enough to trigger a GC cycle visible via MXBean); `rss_mb` = 105 satisfies the "at least one real KPI" requirement.
- The app/run.sh needed modification to download and include JARs on the javac/java classpath (the original script had no classpath support). The harness/run.sh was not modified (harness has no external deps).
