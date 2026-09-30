# 06-massive-chat-loom implementation

The HTTP control plane and persistent TCP listener share room state. Each TCP connection has a virtual-thread reader/writer, a queue of at most 256 outbound frames and a 65,536-byte input-frame limit. The server admits at most 4,096 sessions. Room history retains the last 100 messages. Disconnect removes memberships; full outbound queues disconnect slow readers. Oversized frames receive a best-effort error and a one-second drain deadline prevents a stalled writer from retaining a session.

`queued_deliveries` counts successfully enqueued frames. `socket_deliveries` counts message frames flushed to the socket; it is not a client acknowledgement. HTTP subscriber registration does not create a persistent connection or delivery. TCP is newline-framed UTF-8 JSON, not WebSocket or a production chat service.

All listeners bind `127.0.0.1`. `PORT=0` selects an independently bound ephemeral HTTP port; `/runtime` reports PID, role, run token, Java version, executable, actual collector names and JVM arguments. `BENCH_PORT_FILE` is published after startup. Run `cd app && ./run.sh test` for external process/socket correctness checks and `cd harness && ./run.sh test` for smoke-result identity checks.

These are Tier 1 functional prototypes. The shared harness accepts `--base-url`, `--requests`, `--threads`, `--runs` and `--out`, executes every requested repetition, and writes a `smoke` envelope with `measurement_valid=false` and `kpis=null`. It does not report GC, RSS, CPU, percentile or throughput measurements from the load generator.
