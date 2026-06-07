# Implementation status

This repository currently contains **smoke-test scaffolds** for benchmarks 01–13, not the complete production-grade benchmark applications described in the scenario sections of the CLAUDE.md files. The checked-in Java apps are intentionally dependency-light local shims that compile with stock `javac`, expose health/metrics/bench smoke surfaces, and let the harness/result pipeline be tested end to end.

The full target implementations remain TODOs and must replace these shims benchmark-by-benchmark. Do not treat the current `MiniHttpServer` apps as implementations of Spring Boot, gRPC, Kafka, ONNX Runtime, Kubernetes, Disruptor, or database-backed benchmark behavior.

## Current scaffold capabilities

- Every `app/` and `harness/` has `run.sh build`, `run.sh test`, `run.sh run`, and `run.sh clean`.
- HTTP app scaffolds expose `/health`, `/metrics`, and deterministic `/bench/{profile}/{id}` responses.
- CLI app scaffolds emit deterministic JSON output for local smoke tests.
- Harness scaffolds make deterministic requests and write schema-shaped `results.json` smoke artifacts.

## Not yet implemented

- Benchmark-specific frameworks and infrastructure such as Spring Boot, gRPC servers/stubs, Kafka/Flink streams, ONNX Runtime inference, PostgreSQL/Redis persistence, Kubernetes/HPA behavior, LMAX Disruptor order books, and Gatling/k6 load simulations.
- Production-grade observability dashboards and real JVM/GC/JFR metric extraction.
- Contract-generated clients and full contract conformance beyond smoke surfaces.

## How to move a benchmark from scaffold to real implementation

1. Keep `app/` independent from `harness/`.
2. Update `app/contract/` first when the externally visible surface changes.
3. Replace the scaffold app internals with the benchmark-specific implementation from that benchmark's CLAUDE.md.
4. Replace the scaffold harness driver with the documented load tool and measurement protocol.
5. Keep `run.sh build` and `run.sh test` working as the local smoke entrypoints.
