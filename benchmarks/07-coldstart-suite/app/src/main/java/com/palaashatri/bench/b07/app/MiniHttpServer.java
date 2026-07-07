package com.palaashatri.bench.b07.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public final class MiniHttpServer {

    // When the JVM started (epoch ms)
    private final long jvmStartMs = ManagementFactory.getRuntimeMXBean().getStartTime();

    // When this server was initialized (nanoTime)
    private final long startedAtNano = System.nanoTime();

    // NanoTime of the very first request (-1 until first request arrives)
    private final AtomicLong firstRequestNano = new AtomicLong(-1L);

    // Total request counter
    private final AtomicLong requests = new AtomicLong();

    // Per-second throughput circular buffer (60 slots)
    private final AtomicLong[] perSecondCounts = new AtomicLong[60];
    private final long[] secondTimestamps = new long[60];

    private final String benchmark;

    // Fixed child port for coldstart measurement
    private static final int CHILD_PORT = 18070;

    public MiniHttpServer(String benchmark) {
        this.benchmark = benchmark;
        for (int i = 0; i < 60; i++) {
            perSecondCounts[i] = new AtomicLong(0);
            secondTimestamps[i] = 0L;
        }
    }

    public void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 256);
        server.createContext("/health", this::handleHealth);
        server.createContext("/actuator/health", this::handleHealth);
        server.createContext("/metrics", this::handleMetrics);
        server.createContext("/actuator/prometheus", this::handleMetrics);
        server.createContext("/api/v1/coldstart/measure", this::handleColdstartMeasure);
        server.createContext("/api/v1/jfr/stats", this::handleJfrStats);
        server.createContext("/api/v1/warmup", this::handleWarmup);
        server.createContext("/", this::handleCatchAll);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        log("started", "\"port\":" + port);
    }

    // -------------------------------------------------------------------------
    // Request recording
    // -------------------------------------------------------------------------

    private void recordFirstRequest() {
        firstRequestNano.compareAndSet(-1L, System.nanoTime());
    }

    private void recordRequest() {
        requests.incrementAndGet();
        long currentSecond = System.currentTimeMillis() / 1000L;
        int idx = (int) (currentSecond % 60);
        // If the slot belongs to a different second, reset it
        if (secondTimestamps[idx] != currentSecond) {
            synchronized (perSecondCounts[idx]) {
                if (secondTimestamps[idx] != currentSecond) {
                    perSecondCounts[idx].set(0L);
                    secondTimestamps[idx] = currentSecond;
                }
            }
        }
        perSecondCounts[idx].incrementAndGet();
    }

    // -------------------------------------------------------------------------
    // Handlers
    // -------------------------------------------------------------------------

    private void handleHealth(HttpExchange ex) throws IOException {
        recordFirstRequest();
        recordRequest();
        long uptimeMs = (System.nanoTime() - startedAtNano) / 1_000_000L;
        long firstNano = firstRequestNano.get();
        long startupMs = firstNano == -1L ? -1L : (firstNano - startedAtNano) / 1_000_000L;
        long compiledMs = compilationMs();
        long jvmUptimeMs = System.currentTimeMillis() - jvmStartMs;
        String body = "{\"status\":\"UP\",\"uptime_ms\":" + uptimeMs
                + ",\"startup_ms\":" + startupMs
                + ",\"compiled_ms\":" + compiledMs
                + ",\"jvm_start_ms\":" + jvmUptimeMs + "}";
        json(ex, 200, body);
    }

    private void handleMetrics(HttpExchange ex) throws IOException {
        recordFirstRequest();
        recordRequest();
        long startupMs = startupMsValue();
        long compiledMs = compilationMs();
        long loadedClasses = ManagementFactory.getClassLoadingMXBean().getLoadedClassCount();
        int processors = Runtime.getRuntime().availableProcessors();
        long totalRequests = requests.get();
        String body = "# TYPE jvm_startup_ms gauge\n"
                + "jvm_startup_ms " + startupMs + "\n"
                + "# TYPE jvm_compilation_ms_total counter\n"
                + "jvm_compilation_ms_total " + compiledMs + "\n"
                + "# TYPE benchmark_requests_total counter\n"
                + "benchmark_requests_total{benchmark=\"" + benchmark + "\"} " + totalRequests + "\n"
                + "# TYPE jvm_loaded_classes gauge\n"
                + "jvm_loaded_classes " + loadedClasses + "\n"
                + "# TYPE jvm_available_processors gauge\n"
                + "jvm_available_processors " + processors + "\n";
        bytes(ex, 200, "text/plain; version=0.0.4", body);
    }

    private void handleJfrStats(HttpExchange ex) throws IOException {
        recordFirstRequest();
        recordRequest();
        long compiledMs = compilationMs();
        long loadedClasses = ManagementFactory.getClassLoadingMXBean().getLoadedClassCount();
        long uptimeMs = (System.nanoTime() - startedAtNano) / 1_000_000L;
        long jvmUptimeMs = System.currentTimeMillis() - jvmStartMs;
        String body = "{\"total_compilation_ms\":" + compiledMs
                + ",\"loaded_classes\":" + loadedClasses
                + ",\"uptime_ms\":" + uptimeMs
                + ",\"jvm_start_ms\":" + jvmUptimeMs + "}";
        json(ex, 200, body);
    }

    private void handleColdstartMeasure(HttpExchange ex) throws IOException {
        recordFirstRequest();
        recordRequest();
        // Drain the request body (good practice)
        try (InputStream in = ex.getRequestBody()) { in.readAllBytes(); }

        Process childProcess = null;
        long ttfr = -1L;
        String errorMsg = null;
        try {
            long t0 = System.nanoTime();
            String cp = System.getProperty("java.class.path");
            childProcess = new ProcessBuilder(
                    "java", "-cp", cp,
                    "com.palaashatri.bench.b07.app.BenchmarkApp",
                    String.valueOf(CHILD_PORT))
                    .redirectErrorStream(true)
                    .start();

            // Poll child /health until 200, max 10s (100 attempts x 100ms)
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(500))
                    .build();
            boolean responded = false;
            for (int attempt = 0; attempt < 100; attempt++) {
                try {
                    Thread.sleep(100);
                    HttpRequest req = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + CHILD_PORT + "/health"))
                            .timeout(Duration.ofMillis(400))
                            .GET().build();
                    HttpResponse<Void> res = client.send(req, HttpResponse.BodyHandlers.discarding());
                    if (res.statusCode() == 200) {
                        ttfr = (System.nanoTime() - t0) / 1_000_000L;
                        responded = true;
                        break;
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception ignored) {
                    // child not ready yet
                }
            }
            if (!responded) {
                errorMsg = "timeout";
            }
        } catch (Exception e) {
            errorMsg = escape(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            if (childProcess != null) {
                childProcess.destroyForcibly();
            }
        }

        String body;
        if (errorMsg != null) {
            body = "{\"time_to_first_response_ms\":-1,\"error\":\"" + errorMsg + "\"}";
        } else {
            body = "{\"time_to_first_response_ms\":" + ttfr + "}";
        }
        json(ex, 200, body);
    }

    private void handleWarmup(HttpExchange ex) throws IOException {
        recordFirstRequest();
        recordRequest();
        long nowSecond = System.currentTimeMillis() / 1000L;
        StringBuilder sb = new StringBuilder("{\"samples\":[");
        boolean first = true;
        for (int offset = 59; offset >= 0; offset--) {
            long second = nowSecond - offset;
            int idx = (int) (second % 60);
            if (secondTimestamps[idx] == second) {
                long rps = perSecondCounts[idx].get();
                if (rps > 0) {
                    if (!first) sb.append(',');
                    sb.append("{\"second\":").append(second).append(",\"rps\":").append(rps).append('}');
                    first = false;
                }
            }
        }
        sb.append("]}");
        json(ex, 200, sb.toString());
    }

    private void handleCatchAll(HttpExchange ex) throws IOException {
        recordFirstRequest();
        recordRequest();
        // Drain body
        try (InputStream in = ex.getRequestBody()) { in.readAllBytes(); }
        String path = ex.getRequestURI().getPath();
        json(ex, 404, "{\"error\":\"no route for " + escape(path) + "\",\"benchmark\":\"" + benchmark + "\"}");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private long startupMsValue() {
        long firstNano = firstRequestNano.get();
        return firstNano == -1L ? -1L : (firstNano - startedAtNano) / 1_000_000L;
    }

    private long compilationMs() {
        var compMX = ManagementFactory.getCompilationMXBean();
        if (compMX == null) return 0L;
        return compMX.getTotalCompilationTime();
    }

    private static void json(HttpExchange ex, int status, String body) throws IOException {
        bytes(ex, status, "application/json", body);
    }

    private static void bytes(HttpExchange ex, int status, String contentType, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, data.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(data);
        }
    }

    private static String escape(String raw) {
        if (raw == null) return "";
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private void log(String event, String fields) {
        System.out.println("{\"event\":\"" + event + "\",\"benchmark\":\"" + benchmark + "\"," + fields + "}");
    }
}
