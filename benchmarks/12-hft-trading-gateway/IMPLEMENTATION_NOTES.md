# 12-hft-trading-gateway implementation

HTTP and persistent newline-framed TCP SubmitOrder/CancelOrder/GetOrderStatus use the same synthetic in-memory order book. Matching is symbol-isolated, price-prioritized and FIFO at equal prices, supports partial fills, and exposes remaining quantity and cancellation state. Each TCP connection has bounded frames/output queues and disconnect cleanup.

`/grpc/*` paths are retained ordinary HTTP aliases. `grpc_active=false` is explicit; there is no grpc-java/HTTP2 protocol, external venue or market-data connectivity. Order submissions count real engine operations, but no publication-grade latency is measured.

All listeners bind `127.0.0.1`. `PORT=0` selects an independently bound ephemeral HTTP port; `/runtime` reports PID, role, run token, Java version, executable, actual collector names and JVM arguments. `BENCH_PORT_FILE` is published after startup. Run `cd app && ./run.sh test` for external process/socket correctness checks and `cd harness && ./run.sh test` for smoke-result identity checks.

These are Tier 1 functional prototypes. The shared harness accepts `--base-url`, `--requests`, `--threads`, `--runs` and `--out`, executes every requested repetition, and writes a `smoke` envelope with `measurement_valid=false` and `kpis=null`. It does not report GC, RSS, CPU, percentile or throughput measurements from the load generator.
