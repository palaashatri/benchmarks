package com.palaashatri.bench.b07.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** OpenJDK process-start prototype. CDS/AppCDS matrix remains Tier-2 work. */
public final class MiniHttpServer {
    private final String benchmark;
    private final long constructedAt = System.nanoTime();
    private final long jvmStartMs = ManagementFactory.getRuntimeMXBean().getStartTime();
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong childStarts = new AtomicLong();
    private final AtomicLong childStartDurationNs = new AtomicLong();

    public MiniHttpServer(String benchmark) { this.benchmark = benchmark; }

    public void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 256);
        server.createContext("/health", this::health);
        server.createContext("/runtime", this::runtime);
        server.createContext("/metrics", this::metrics);
        server.createContext("/api/v1/coldstart/measure", this::measure);
        server.createContext("/api/v1/jfr/stats", this::stats);
        server.createContext("/api/v1/warmup", this::stats);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.printf("{\"event\":\"started\",\"benchmark\":\"%s\",\"port\":%d,\"pid\":%d}%n",
                benchmark, port, ProcessHandle.current().pid());
    }

    private void health(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        json(exchange, 200, "{\"status\":\"UP\",\"process_uptime_ms\":"
                + ManagementFactory.getRuntimeMXBean().getUptime() + "}");
    }

    private void runtime(HttpExchange exchange) throws IOException {
        json(exchange, 200, "{\"pid\":" + ProcessHandle.current().pid()
                + ",\"run_token\":\"" + escape(System.getenv().getOrDefault("BENCH_RUN_TOKEN", "")) + "\""
                + ",\"java_executable\":\"" + escape(javaExecutable().toString()) + "\""
                + ",\"java_version\":\"" + escape(System.getProperty("java.version")) + "\"}");
    }

    private void stats(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        var compilation = ManagementFactory.getCompilationMXBean();
        long compilationMs = compilation == null || !compilation.isCompilationTimeMonitoringSupported()
                ? -1 : compilation.getTotalCompilationTime();
        json(exchange, 200, "{\"jvm_start_epoch_ms\":" + jvmStartMs
                + ",\"jvm_uptime_ms\":" + ManagementFactory.getRuntimeMXBean().getUptime()
                + ",\"server_uptime_ms\":" + ((System.nanoTime() - constructedAt) / 1_000_000L)
                + ",\"jit_compilation_ms\":" + compilationMs
                + ",\"loaded_classes\":" + ManagementFactory.getClassLoadingMXBean().getLoadedClassCount()
                + ",\"cds_mode\":\"not-controlled-by-app\"}");
    }

    private void measure(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            json(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        int childPort = freePort();
        String token = "child-" + Long.toUnsignedString(System.nanoTime());
        ProcessBuilder builder = new ProcessBuilder(javaExecutable().toString(), "-cp",
                System.getProperty("java.class.path"), BenchmarkApp.class.getName(), Integer.toString(childPort));
        builder.environment().put("BENCH_RUN_TOKEN", token);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        long started = System.nanoTime();
        Process child = builder.start();
        String error = null;
        long readyNs = -1;
        try {
            waitForChild(childPort, child, token, Duration.ofSeconds(15));
            readyNs = System.nanoTime() - started;
            childStarts.incrementAndGet();
            childStartDurationNs.addAndGet(readyNs);
        } catch (Exception exception) {
            error = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        } finally {
            child.destroy();
            try {
                if (!child.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) child.destroyForcibly();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                child.destroyForcibly();
            }
        }
        if (error != null) {
            json(exchange, 500, "{\"error\":\"child_start_failed\",\"message\":\"" + escape(error) + "\"}");
        } else {
            json(exchange, 200, "{\"time_to_health_ms\":" + (readyNs / 1_000_000.0)
                    + ",\"child_java\":\"" + escape(javaExecutable().toString()) + "\""
                    + ",\"measurement_kind\":\"process_to_health\"}");
        }
    }

    private void metrics(HttpExchange exchange) throws IOException {
        String body = "# TYPE coldstart_child_starts_total counter\ncoldstart_child_starts_total " + childStarts.get() + "\n"
                + "# TYPE coldstart_child_start_duration_seconds_sum counter\ncoldstart_child_start_duration_seconds_sum "
                + format(childStartDurationNs.get() / 1_000_000_000.0) + "\n"
                + "# TYPE benchmark_requests_total counter\nbenchmark_requests_total{benchmark=\"" + benchmark + "\"} " + requests.get() + "\n";
        bytes(exchange, 200, "text/plain; version=0.0.4", body);
    }

    private static void waitForChild(int port, Process child, String token, Duration timeout) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!child.isAlive()) throw new IllegalStateException("child exited with code " + child.exitValue());
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/runtime"))
                        .timeout(Duration.ofMillis(400)).GET().build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && response.body().contains("\"run_token\":\"" + token + "\"")) return;
            } catch (Exception ignored) { }
            Thread.sleep(20);
        }
        throw new IllegalStateException("child readiness timeout");
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }
    private static Path javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
    }
    private static String format(double value) { return String.format(java.util.Locale.ROOT, "%.6f", value); }
    private static String escape(String value) { return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\""); }
    private static void json(HttpExchange exchange, int status, String body) throws IOException { bytes(exchange, status, "application/json", body); }
    private static void bytes(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(payload); }
    }
}
