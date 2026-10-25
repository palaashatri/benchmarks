# Implementation Notes — 09-onnx-inference

## Classifier

A seeded 4 → 64 → 3 network (ReLU, softmax). Its weights come from `ClassifierWeights.seeded()`
(`Random(42)`), so every engine computes the same function.

- **Maven ONNX lane** (`app/src/onnx/java`): `OnnxModelBuilder` writes the network as an ONNX
  graph (MatMul → Relu → MatMul → Softmax, opset 13, IR 8) using the protobuf wire format and the
  onnx.proto field numbers. No model file is checked in and no ONNX library is needed to build it.
  `OnnxInferenceEngine` opens an `OrtSession` over those bytes with ONNX Runtime 1.18.0 and reports
  `mode = onnx-runtime`.
- **Dependency-free lane** (`app/src/main/java`): `JavaFallbackEngine` is the pure-Java reference and
  reports `mode = java-fallback`. the Maven test compiles only this lane.

An engine is selected with `ServiceLoader`. The ONNX provider is registered only in the Maven build,
so the smoke build cannot report ONNX. A provider whose session fails to load is a startup error, not
a silent fallback.

## Routes

- `POST /api/v1/inference/classify`: accepts `{"features":[f1,f2,f3,f4]}` or `{"text":"..."}`. Every
  response carries `mode` and `onnx_session_active` for the engine that actually ran.
- `GET /api/v1/inference/health`, `GET /health`: `status`, `mode`, `onnx_session_active`.
- `GET /runtime`: pid, run token, Java version, VM name and `mode`.
- `GET /metrics`: `inference_requests_total{mode}`, `inference_duration_seconds_sum{mode}`,
  `onnx_session_active` gauge (1 only for a live session), `benchmark_requests_total`.

## Verification

- `cd app && ./mvnw -B test` (smoke, javac): the fallback is served; `mode` is `java-fallback` and
  `onnx_session_active` is `false`.
- `mvn -B -f app/pom.xml test`: runs the smoke suite, then `OnnxEquivalenceCheck`. The check requires
  the ORT session to match the Java reference within 1e-5 on 1,000 seeded random inputs with no
  class disagreements, and requires the HTTP service to report `onnx-runtime` with a live session.

## Harness

`harness/BenchmarkHarness.java` runs concurrent requests against the service and records
`model_load_ms` from the first health response, plus GC, RSS and CPU from the harness process.
Those measurements are not application-process telemetry, and the envelope stays
`measurement_valid=false`.

## Limits

- The network is a seeded random network, not a trained iris model. The benchmark measures inference
  work; it makes no accuracy claim.
- No phased open-loop load, warm-up gating or application-process telemetry is wired in, so this
  workload stays out of Tier 2.
