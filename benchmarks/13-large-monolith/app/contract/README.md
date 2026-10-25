# Large Monolith contract

The app's only external surface. Every route is GET. Responses are JSON, except `/metrics`, which is Prometheus text.

| Route | Purpose |
|---|---|
| `GET /health` | Liveness; reports declared and wired component counts. |
| `GET /runtime` | Process identity (pid, run token, Java version). The Tier 2 engine checks it before load. |
| `GET /metrics` | Prometheus exposition. |
| `GET /api/v1/monolith/graph` | Graph shape, the Java reference result and `expected_result`. |
| `GET /api/v1/monolith/work` | One traversal of all components. The result must equal `expected_result`. |
| `GET /api/v1/monolith/warmup/status` | Startup, class-loading and JIT figures. |

The component graph is generated at build time by `app/tools/GenerateGraph.java`. The generator also computes `expected_result` from its own model of the wiring. A container that wires the graph differently produces a different traversal result, and the smoke test fails.
