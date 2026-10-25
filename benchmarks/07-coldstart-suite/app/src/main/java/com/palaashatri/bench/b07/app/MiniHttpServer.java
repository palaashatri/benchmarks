package com.palaashatri.bench.b07.app;

import com.palaashatri.bench.common.Json;
import com.palaashatri.bench.common.Routes;
import com.palaashatri.bench.common.RuntimeInfo;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.management.CompilationMXBean;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HTTP surface of the cold-start suite. Startup times are measured on child JVMs that this process
 * starts and reaps; this process's own figures are reported separately and never mixed in.
 */
public final class MiniHttpServer {
    private final String benchmark;
    private final ColdStart coldStart = new ColdStart();
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong childStarts = new AtomicLong();
    private final long jvmStartEpochMs = ManagementFactory.getRuntimeMXBean().getStartTime();

    /** Route handler that may throw I/O failures. */
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    public MiniHttpServer(String benchmark) { this.benchmark = benchmark; }

    public void start(int port) throws IOException {
        // Only the top-level application prepares archives. Children carry a role and never do.
        if (System.getenv("BENCH_SERVICE_ROLE") == null) {
            coldStart.prepare();
        }
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 256);
        server.createContext("/health", route("GET", this::health));
        server.createContext("/runtime", route("GET", this::runtime));
        server.createContext("/metrics", route("GET", this::metrics));
        server.createContext("/api/v1/coldstart/modes", route("GET", this::modes));
        server.createContext("/api/v1/coldstart/samples", route("GET", this::samples));
        server.createContext("/api/v1/coldstart/measure", route("POST", this::measure));
        server.createContext("/api/v1/jfr/stats", route("GET", this::stats));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        int bound = server.getAddress().getPort();
        RuntimeInfo.publishPort(bound);
        System.out.printf("{\"event\":\"started\",\"benchmark\":\"%s\",\"port\":%d,\"pid\":%d}%n",
                benchmark, bound, ProcessHandle.current().pid());
    }

    private void health(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        json(exchange, 200, "{\"status\":\"UP\",\"process_uptime_ms\":"
                + ManagementFactory.getRuntimeMXBean().getUptime() + "}");
    }

    private void runtime(HttpExchange exchange) throws IOException {
        json(exchange, 200, RuntimeInfo.json());
    }

    private void modes(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        json(exchange, 200, coldStart.modesJson());
    }

    private void samples(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        json(exchange, 200, coldStart.samplesJson());
    }

    private void measure(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        byte[] bytes = exchange.getRequestBody().readNBytes(Json.MAX_BYTES + 1);
        if (bytes.length > Json.MAX_BYTES) throw new IllegalArgumentException("request_too_large");
        String text = new String(bytes, StandardCharsets.UTF_8);
        ColdStart.Mode mode = text.isBlank()
                ? ColdStart.Mode.DEFAULT
                : ColdStart.Mode.parse(String.valueOf(Json.parseObject(text).getOrDefault("mode", "default")));

        ColdStart.Measurement result = coldStart.measure(mode);
        if (!result.ok()) {
            json(exchange, 500, "{\"mode\":\"" + mode.id + "\",\"error\":\"child_start_failed\",\"message\":\""
                    + ColdStart.escape(result.message()) + "\"}");
            return;
        }
        childStarts.incrementAndGet();
        json(exchange, 200, "{\"mode\":\"" + mode.id + "\",\"time_to_health_ms\":"
                + String.format(Locale.ROOT, "%.3f", result.timeToHealthMs())
                + ",\"child_pid\":" + result.childPid()
                + ",\"child_runtime\":" + result.childRuntime()
                + ",\"measurement_kind\":\"process_to_health\"}");
    }

    private void stats(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        CompilationMXBean compilation = ManagementFactory.getCompilationMXBean();
        String jit = compilation == null || !compilation.isCompilationTimeMonitoringSupported()
                ? "null" : Long.toString(compilation.getTotalCompilationTime());
        json(exchange, 200, "{\"jvm_start_epoch_ms\":" + jvmStartEpochMs
                + ",\"jvm_uptime_ms\":" + ManagementFactory.getRuntimeMXBean().getUptime()
                + ",\"loaded_classes\":" + ManagementFactory.getClassLoadingMXBean().getLoadedClassCount()
                + ",\"jit_compilation_ms\":" + jit + "}");
    }

    private void metrics(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        String body = "# TYPE coldstart_child_starts_total counter\ncoldstart_child_starts_total " + childStarts.get() + "\n"
                + "# TYPE benchmark_requests_total counter\nbenchmark_requests_total{benchmark=\""
                + ColdStart.escape(benchmark) + "\"} " + requests.get() + "\n";
        Json.bytes(exchange, 200, "text/plain; version=0.0.4", body);
    }

    private static HttpHandler route(String method, Handler handler) {
        return Routes.guard(exchange -> {
            if (Routes.method(exchange, method)) {
                handler.handle(exchange);
            }
        });
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        Json.bytes(exchange, status, "application/json", body);
    }
}
