# Implementation status

This repository now contains **dependency-light local benchmark implementations** for benchmarks 01–13. They are not generic `/bench` echo servers: each app exposes the benchmark-specific contract surface from its `app/contract/` and `CLAUDE.md` notes, keeps in-memory domain state where useful, and performs deterministic local work so the harnesses can validate behavior without external infrastructure.

The implementations intentionally stay within Java SE (`javac`, `HttpServer`, `HttpClient`, collections, and file IO) so every `run.sh build` and `run.sh test` works in a fresh local checkout. They are therefore full local implementations of the repository's no-dependency smoke contracts, but they are not yet production-framework implementations of Spring Boot, real gRPC/protobuf servers, Kafka/Flink streams, ONNX Runtime, PostgreSQL/Redis, Kubernetes/HPA, or LMAX Disruptor internals.

## Current implementation capabilities

- Every `app/` and `harness/` has `run.sh build`, `run.sh test`, `run.sh run`, and `run.sh clean`.
- Apps keep common `/health`, `/metrics`, `/actuator/health`, and `/actuator/prometheus` endpoints for smoke and observability checks.
- Benchmark-specific app behavior is implemented locally:
  - 01 ledger transfers, balances, and transaction history.
  - 02 event ingress, flow state, and notification stubs.
  - 03 streaming event ingestion, window aggregates, and lag reporting.
  - 04 vector feature extraction, local inference, and model metadata.
  - 05 pure-Java rule evaluation, mode discovery, and script validation seam.
  - 06 virtual connection simulation, room messages, and room event reads.
  - 07 coldstart function listing and function invocation.
  - 08 ETL schema, local job run, and job status.
  - 09 local text classification with inference health.
  - 10 catalog lookup, order creation, and order status.
  - 11 product pricing with deterministic rule branches.
  - 12 trading submit/cancel/status through a JSON gRPC-shaped local shim.
  - 13 customer graph lookup, order rule evaluation, and daily report aggregation.
- Harnesses use benchmark-specific request plans and still emit schema-shaped `results.json` artifacts for deterministic local verification.
- Contract OpenAPI files describe the implemented local HTTP surfaces rather than a generic `/bench/{profile}/{id}` placeholder.

## Still deferred for production parity

- Replacing the dependency-light local implementations with framework-specific variants where the scenarios require them: Spring Boot services, real gRPC/protobuf generated stubs, Kafka/Flink pipelines, ONNX Runtime integration, PostgreSQL/Redis persistence, Kubernetes/HPA tests, LMAX Disruptor order books, and Gatling/k6 load simulations.
- Production-grade dashboards and real JVM/GC/JFR metric extraction.
- Contract-generated clients beyond the checked-in local Java harnesses.

## How to move a benchmark from local implementation to production parity

1. Keep `app/` independent from `harness/`.
2. Update `app/contract/` first when the externally visible surface changes.
3. Replace only the internals needed for the production framework while preserving the local `run.sh` smoke entrypoints.
4. Replace the local harness request plan with the documented load tool and measurement protocol.
5. Keep `run.sh build` and `run.sh test` working as the minimum local verification gate.
