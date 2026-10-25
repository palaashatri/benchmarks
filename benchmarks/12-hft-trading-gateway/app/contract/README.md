# 12-hft-trading-gateway external contract

See `openapi.yaml` for the actual HTTP paths.

HTTP and persistent newline-framed TCP SubmitOrder/CancelOrder/GetOrderStatus use the same synthetic in-memory order book. Matching is symbol-isolated, price-prioritized and FIFO at equal prices, supports partial fills, and exposes remaining quantity and cancellation state. Each TCP connection has bounded frames/output queues and disconnect cleanup.

The gRPC service in `trading.proto` (`TradingGateway`: `SubmitOrder`, `CancelOrder`, `GetOrderStatus`) is served over real HTTP/2 by grpc-java on `127.0.0.1` only. It is built and checked by the Maven gRPC lane (`mvn -B -f app/pom.xml test`), which generates the stubs, starts a gateway and makes real RPCs. The dependency-free smoke build has no gRPC provider, so `/health` reports `grpc_active=false` there; the gRPC lane reports `grpc_active=true` with the bound `grpc_port`. gRPC calls use the same order book as HTTP and framed TCP and are counted in `gateway_grpc_requests_total`. Unknown proto fields are `optional` and stay unset. `/grpc/*` paths remain ordinary HTTP aliases. There is no external venue or market-data connectivity, and no publication-grade latency is measured.

Discover `tcp_port` with GET `/health`. Send one UTF-8 JSON object followed by `\n` per frame on a persistent socket:

```json
{"op":"SubmitOrder","symbol":"ALPHA","side":"BUY","quantity":100,"price_nanos":150}
{"op":"GetOrderStatus","order_id":"ord-1"}
{"op":"CancelOrder","order_id":"ord-1"}
```

Submit replies contain `accepted` and `order_id`; status replies include `status` and `remaining_quantity`. Symbols are normalized to uppercase and limited to letters, digits, underscore, dot and hyphen (1–32 characters). Quantities/prices are positive integers. Frames are limited to 65,536 bytes. Invalid input/operations receive errors; oversized input closes after a bounded error drain. HTTP submit uses the same fields without `op`, and both transports see the same book. `/grpc/*` paths are ordinary HTTP aliases.

All listeners bind `127.0.0.1`. `PORT=0` selects an independently bound ephemeral HTTP port; `/runtime` reports PID, role, run token, Java version, executable, actual collector names and JVM arguments. `BENCH_PORT_FILE` is published after startup. Run `cd app && ./run.sh test` for external process/socket correctness checks and `cd harness && ./run.sh test` for smoke-result identity checks.

These are Tier 1 functional prototypes. The shared harness accepts `--base-url`, `--requests`, `--threads`, `--runs` and `--out`, executes every requested repetition, and writes a `smoke` envelope with `measurement_valid=false` and `kpis=null`. It does not report GC, RSS, CPU, percentile or throughput measurements from the load generator.
