# 06-massive-chat-loom implementation

The HTTP control plane and persistent TCP listener share room state. Each TCP connection has a virtual-thread reader/writer, a queue of at most 256 outbound frames and a 65,536-byte input-frame limit. The server admits at most 4,096 sessions. Room history retains the last 100 messages. Disconnect removes memberships; full outbound queues disconnect slow readers. Oversized frames receive a best-effort error and a one-second drain deadline prevents a stalled writer from retaining a session.

`queued_deliveries` counts successfully enqueued frames. `socket_deliveries` counts message frames flushed to the socket; it is not a client acknowledgement. HTTP subscriber registration does not create a persistent connection or delivery. TCP is newline-framed UTF-8 JSON, not WebSocket or a production chat service.

All listeners bind `127.0.0.1`. `PORT=0` selects an independently bound ephemeral HTTP port; `/runtime` reports PID, role, run token, Java version, executable, actual collector names and JVM arguments. `BENCH_PORT_FILE` is published after startup. Run `cd app && ./mvnw -B test` for external process/socket correctness checks and `cd harness && ./mvnw -B test` for smoke-result identity checks.

The HTTP harness is a functional smoke client, not the measurement path. It accepts `--base-url`, `--requests`, `--threads`, `--runs` and `--out`, executes every requested repetition, and writes a `smoke` envelope with `measurement_valid=false` and `kpis=null`. It does not report GC, RSS, CPU, percentile or throughput measurements from the load generator.

## Measurement lane

Chat delivery is measured by `harness/src/main/java/com/palaashatri/bench/b06/harness/ChatLoad.java`. Run standalone (`benchctl lane chat`), the driver starts the application itself. Under the Tier 2 engine's `framed-tcp` shape, the engine starts and samples the application, and the driver attaches to it with `--attach-base-url`, `--run-token` and `--app-pid` rather than starting one. Either way the driver opens persistent subscriber and publisher connections over the newline-framed TCP protocol, publishes on an open-loop schedule, and measures delivery latency from each message's intended send time. Every message frame received is checked against the server's queued-delivery count (conservation). Application CPU and RSS are sampled from `/proc` for the application process only.

Run it after building the application and the harness:

```sh
(cd app && ./mvnw -B -q package)
(cd harness && ./mvnw -B -q package && java -cp target/classes com.palaashatri.bench.b06.harness.ChatLoad --out <file> --subscribers 200 --rooms 8 --publishers 8 --rate 500 --warmup-seconds 3 --measure-seconds 10)
```

Latency is measured by the driver, not the application, and the envelope says so.

## Validity

06 is Tier 2. Its contract (`benchmarks/06-massive-chat-loom/tier2.json`) runs the correctness test, builds the application and the driver, and measures under the shared gates: identity, zero errors (error frames and conservation failures), every scheduled message sent, warm-up stability, five repetitions and host preflight. An envelope is measurement-valid only when every gate passes on controlled hardware.
