# ONNX Inference Service contract

The surface is defined in `openapi.yaml`. The service binds `127.0.0.1` only.

Operations:
- `POST /api/v1/inference/classify`: classify a 4-feature vector or a text (hashed to 4 features).
- `GET /api/v1/inference/health` and `GET /health`: status, `mode`, `onnx_session_active`.
- `GET /runtime`: pid, run token, Java version and VM name.
- `GET /metrics`: Prometheus text metrics, labelled by the engine that ran.

`mode` is `onnx-runtime` only when an ONNX Runtime session is live (Maven ONNX lane); the
dependency-free build reports `java-fallback` and `onnx_session_active` is `false`.
