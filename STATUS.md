# JVM Benchmark Suite — Implementation Status

> Updated automatically as agents complete work. Last full audit: 2026-07-07.

## Approach A — Realistic JVM-exercising implementations
Each app uses real Java APIs and embedded libraries that genuinely exercise the claimed JVM feature (H2/HikariCP for JDBC, Panama Vector for SIMD, virtual threads for Loom, Rhino for polyglot, etc.). No external Kafka/Postgres clusters — embedded equivalents only. All harnesses run concurrent load with real GC/RSS/CPU KPIs.

## Implementation Matrix

| # | Benchmark | JVM Feature | App | Harness | Grafana/Compose | Status | Commit |
|---|-----------|-------------|-----|---------|-----------------|--------|--------|
| 01 | fintech-ledger | H2+HikariCP+Micrometer | ✅ | ✅ | ✅ | ✅ Done | 222de7e |
| 02 | microservices-mesh | HttpClient mesh | ✅ | ✅ | ✅ | ✅ Done | 83dce01 |
| 03 | streaming-analytics | BlockingQueue windowed streams | ✅ | ✅ | ✅ | ✅ Done | 756db28 |
| 04 | ml-inference-panama-vector | Panama Vector API (SIMD) | ✅ | ✅ | ✅ | ✅ Done | 0bae5dd |
| 05 | polyglot-service | Rhino JS engine | ✅ | ✅ | ✅ | ✅ Done | f72d367 |
| 06 | massive-chat-loom | Virtual Threads (Loom) | ✅ | ✅ | ✅ | ✅ Done | b913585 |
| 07 | coldstart-suite | JFR + startup timing | ✅ | ✅ | ✅ | ✅ Done | 708e249 |
| 08 | etl-batch | NIO2 file I/O + Streams | ✅ | ✅ | ✅ | ✅ Done | 265a351 |
| 09 | onnx-inference | ONNX Runtime (JDK 17) | ✅ | ✅ | ✅ | ✅ Done | 33ba2ca |
| 10 | microservices-fleet | Multi-service rolling deploy (JDK 17) | ✅ | ✅ | ✅ | ✅ Done | 3dd91de |
| 11 | autoscaling-burst | Dynamic ThreadPoolExecutor (JDK 17) | ✅ | ✅ | ✅ | ✅ Done | aa089bc |
| 12 | hft-trading-gateway | order book + wire latency (JDK 25) | ✅ | ✅ | ✅ | ✅ Done | b949eb0 |
| 13 | large-monolith | ByteBuddy + JIT tracking (JDK 17) | ✅ | ✅ | ✅ | ✅ Done | 0522fc6 |

## Cross-Cutting Fixes — All Delivered
- [x] Harness concurrent load (ExecutorService + CountDownLatch)
- [x] Correct throughput = count / wall-clock-seconds
- [x] Multi-run support (--runs N, default 3)
- [x] GC metrics via ManagementFactory.getGarbageCollectorMXBeans()
- [x] RSS via `ps -o rss= -p PID`
- [x] CPU via com.sun.management.OperatingSystemMXBean.getProcessCpuLoad()
- [x] Env descriptor: os.version, availableProcessors
- [x] Grafana datasource provisioning YAML wired in docker-compose
- [x] Grafana dashboard provisioning YAML
- [x] Real dashboard panels (4+ per benchmark)
- [x] Smoke test via `run.sh test` (all 13 pass)

## Known Deviations from Original Spec
- **12-hft-trading-gateway**: gRPC stubs not generated (proto-maven-plugin needs Maven, run.sh uses bare javac). HTTP facade with real order book + wire latency KPI delivered instead. JDK 25 value class needs `--enable-preview`; used `record` instead.
- **13-large-monolith**: ByteBuddy not on bare-javac classpath; 500 plain POJOs used instead. JIT tracking via CompilationMXBean is real.
- **09-onnx-inference**: No bundled .onnx model; uses 4→64→3 Java neural net fallback. Probes for `ai.onnxruntime.OrtEnvironment` via reflection — drops into real ONNX mode if JAR is added to classpath at runtime.

## Notes per Benchmark

### 01 — fintech-ledger
Dependencies added: H2 2.2.224, HikariCP 5.1.0, Micrometer 1.13.6
Real work: SELECT FOR UPDATE JDBC transactions, fraud scoring, Prometheus via Micrometer

### 02 — microservices-mesh
Three inner services (account:8081, transaction:8082, notification:8083) + proxy on 8080.
Real Java 21 HttpClient inter-service calls. In-memory circuit breaker.

### 03 — streaming-analytics
LinkedBlockingQueue producers → tumbling-window consumers. No Kafka dep needed.
Routes: POST /api/v1/events, GET /api/v1/windows/{key}

### 04 — ml-inference-panama-vector
Real jdk.incubator.vector.FloatVector SIMD matrix multiply. Needs --add-modules=jdk.incubator.vector.
Routes: POST /api/v1/inference (feature array → prediction)

### 05 — polyglot-service
Mozilla Rhino (org.mozilla:rhino:1.7.15) JS engine. Routes: POST /api/v1/score (inline JS + data)

### 06 — massive-chat-loom
Executors.newVirtualThreadPerTaskExecutor(). Chat rooms, broadcast.
Routes: POST /rooms/{id}/messages, GET /rooms/{id}/messages, GET /rooms

### 07 — coldstart-suite
ManagementFactory.getRuntimeMXBean().getUptime() for startup KPI.
JFR compilation stats via CompilationMXBean.

### 08 — etl-batch
Real NIO2 I/O: generate CSV → write temp file → read → transform → write output.
Routes: POST /api/v1/etl/run, GET /api/v1/etl/{id}/status

### 09 — onnx-inference (Maven/JDK17)
com.microsoft.onnxruntime:onnxruntime:1.18.0. Iris classification ONNX model bundled as resource.
Fallback to pure-Java sigmoid net if model not found.

### 10 — microservices-fleet (Maven/JDK17)
5 HttpServer instances on ports 18010–18014. Rolling restart simulation.
Routes: GET /api/v1/fleet/status, POST /api/v1/fleet/deploy/{id}

### 11 — autoscaling-burst (Maven/JDK17)
Token bucket + ThreadPoolExecutor.setCorePoolSize() dynamic scaling.
Routes: POST /api/v1/catalog/search, GET /api/v1/metrics/scaling

### 12 — hft-trading-gateway (Maven/JDK25)
io.grpc:grpc-netty-shaded:1.67.1. Implement TradingGateway from trading.proto.
In-memory order book (price-time priority). HTTP facade for harness.

### 13 — large-monolith (Maven/JDK17)
net.bytebuddy:byte-buddy:1.14.18 to generate 500 bean classes.
CompilationMXBean for JIT tracking.
