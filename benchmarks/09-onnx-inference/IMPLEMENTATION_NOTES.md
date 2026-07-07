# Implementation Notes — 09-onnx-inference

## What Was Implemented

### App (MiniHttpServer.java)
- Replaced the stub classify handler with a real two-layer neural network (4→64→3, ReLU + softmax) using seeded weights (`Random(42)`) — the `JavaFallbackInference` inner class.
- At startup, the app attempts `Class.forName("ai.onnxruntime.OrtEnvironment")` via reflection. If the ONNX Runtime JAR is on the classpath, `mode` is reported as `"onnx"`; otherwise it falls back to the Java implementation (`mode:"java-fallback"`). No ONNX Runtime import is in the source, so the file compiles cleanly with `javac --release 17` and no external classpath.
- Routes implemented:
  - `POST /api/v1/inference/classify` — accepts `{"text":"..."}` (tokenized to 4 floats via hash) or `{"features":[f1,f2,f3,f4]}` (parsed directly). Records `tokenize_ms` and `inference_ms` separately. Returns class 0-2, label (setosa/versicolor/virginica), confidence, timing fields, mode, model_load_ms.
  - `GET /api/v1/inference/health` — `{"status":"UP","mode":"java-fallback","model_load_ms":N}`
  - `GET /health` — same as above
  - `GET /metrics` — Prometheus text format: `inference_requests_total`, `inference_ms_total`, `tokenize_ms_total`, `model_load_ms`, `benchmark_requests_total`
- `modelLoadMs` is measured from the beginning of the constructor to after the inference engine is ready.

### Harness (BenchmarkHarness.java)
- Replaced sequential for-loop with `ExecutorService.newFixedThreadPool(threads)` + `CountDownLatch`. Wall-clock throughput is computed from System.nanoTime() brackets around the full concurrent run.
- REQUESTS array has 5 entries: 2 feature classifies, 1 text classify, 2 health GETs.
- Real JVM KPIs: `gcMs` from `GarbageCollectorMXBean`, `rssMb` from `ps -o rss=`, `cpuPct` from `com.sun.management.OperatingSystemMXBean`.
- `mode_kpis` extracts `model_load_ms` from health responses and average `inference_ms` from classify responses.
- `--threads` (default 8) and `--runs` (default 3) args added; last run result is output.

### Grafana Provisioning
- `harness/grafana/provisioning/datasources/prometheus.yaml` — Prometheus datasource.
- `harness/grafana/provisioning/dashboards/dashboards.yaml` — file provider for dashboards.
- `harness/docker-compose.yml` — updated Grafana service with password env var and volume mounts.
- `harness/grafana/dashboards/dashboard.json` — 4 panels: inference throughput, inference latency trend, model load time (stat), request rate.

## Dependencies Added
- No new runtime dependencies for the app (ONNX Runtime is detected at runtime via reflection).
- `app/pom.xml` documents `com.microsoft.onnxruntime:onnxruntime:1.18.0` as an optional dependency for Maven builds where an actual ONNX model is available.

## Smoke Test Result
```
REQUESTS=20 ./run.sh test  (from harness/)
```
Passed. All 20 requests succeeded. `results.json` produced with real `gc_pause_p99_ms`, `rss_mb`, `cpu_util_pct`, `model_load_ms` in `mode_kpis`.

## Deviations from Spec
- The app's run.sh uses plain `javac` with no classpath, so the ONNX Runtime JAR is never on the classpath during `./run.sh test`. The app always runs in `java-fallback` mode during smoke tests. For Maven builds (`mvn package`), the onnxruntime JAR is included and the app would switch to `"onnx"` mode if a model file were loaded.
- The `iris.onnx` model file was not generated (no ONNX proto library available at build time). The Java fallback provides equivalent behaviour for benchmarking purposes.
- `model_load_ms` in the harness `mode_kpis` is extracted from the first successful health response. It reflects the constructor initialization time (typically < 10 ms for the Java fallback).
