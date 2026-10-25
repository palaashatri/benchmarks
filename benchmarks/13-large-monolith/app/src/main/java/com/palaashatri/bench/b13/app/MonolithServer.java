package com.palaashatri.bench.b13.app;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HTTP surface over the wired component graph. A work request passes a value down every module
 * chain, so each request reaches every component once. The result is deterministic, and the
 * reference result computed at startup lets clients check it.
 */
public final class MonolithServer {
    /** Starting value of every traversal. */
    static final long INPUT = 0x5DEECE66DL;

    private final String benchmark;
    private final Container container;
    private final int modules;
    private final int depth;
    private final List<Component> tails = new ArrayList<>();
    private final long startupMillis;
    private final long expectedResult;
    private final long referenceResult;
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong businessOperations = new AtomicLong();
    private final long constructedAtNanos = System.nanoTime();

    /** Interface for route handlers that only accept GET. */
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    /**
     * @param expectedResult result of the generator's independent model of the same graph; a
     *                       wiring that differs from the declared graph produces a different value
     */
    public MonolithServer(String benchmark, Container container, int modules, int depth, long expectedResult,
                          long startedNanos) {
        this.benchmark = benchmark;
        this.container = container;
        this.modules = modules;
        this.depth = depth;
        this.expectedResult = expectedResult;
        for (int module = 0; module < modules; module++) {
            tails.add(container.component(beanId(module, depth - 1)));
        }
        this.startupMillis = (System.nanoTime() - startedNanos) / 1_000_000L;
        this.referenceResult = traverse();
    }

    /** Bean names are the contract with the generator: {@code m<module>-r<row>}. */
    static String beanId(int module, int row) {
        return String.format(Locale.ROOT, "m%02d-r%02d", module, row);
    }

    public void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 256);
        server.createContext("/health", get(this::health));
        server.createContext("/runtime", get(this::runtime));
        server.createContext("/metrics", get(this::metrics));
        server.createContext("/api/v1/monolith/graph", get(this::graph));
        server.createContext("/api/v1/monolith/work", get(this::work));
        server.createContext("/api/v1/monolith/warmup/status", get(this::warmup));
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        int bound = server.getAddress().getPort();
        RuntimeInfo.publishPort(bound);
        System.out.printf("{\"event\":\"started\",\"benchmark\":\"%s\",\"components\":%d,\"port\":%d,\"pid\":%d}%n",
                benchmark, components(), bound, ProcessHandle.current().pid());
    }

    private int components() {
        return modules * depth;
    }

    private long traverse() {
        long value = INPUT;
        for (Component tail : tails) {
            value = tail.apply(value);
        }
        return value;
    }

    private void health(HttpExchange exchange) throws IOException {
        json(exchange, 200, "{\"status\":\"UP\",\"components\":" + components()
                + ",\"wired_components\":" + container.instanceCount() + "}");
    }

    private void runtime(HttpExchange exchange) throws IOException {
        json(exchange, 200, RuntimeInfo.json());
    }

    private void graph(HttpExchange exchange) throws IOException {
        json(exchange, 200, "{\"benchmark\":\"" + escape(benchmark) + "\",\"modules\":" + modules
                + ",\"depth\":" + depth + ",\"components\":" + components()
                + ",\"wired_components\":" + container.instanceCount()
                + ",\"reference_result\":" + referenceResult
                + ",\"expected_result\":" + expectedResult + "}");
    }

    private void work(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        long result = traverse();
        businessOperations.incrementAndGet();
        json(exchange, 200, "{\"result\":" + result + ",\"components_visited\":" + components() + "}");
    }

    private void warmup(HttpExchange exchange) throws IOException {
        json(exchange, 200, "{\"uptime_ms\":" + ((System.nanoTime() - constructedAtNanos) / 1_000_000L)
                + ",\"startup_ms\":" + startupMillis
                + ",\"components\":" + components()
                + ",\"loaded_classes\":" + ManagementFactory.getClassLoadingMXBean().getLoadedClassCount()
                + ",\"jit_compilation_ms\":" + jitMillisJson()
                + ",\"business_operations\":" + businessOperations.get() + "}");
    }

    private void metrics(HttpExchange exchange) throws IOException {
        StringBuilder body = new StringBuilder()
                .append("# TYPE monolith_components gauge\nmonolith_components ").append(components()).append('\n')
                .append("# TYPE monolith_loaded_classes gauge\nmonolith_loaded_classes ")
                .append(ManagementFactory.getClassLoadingMXBean().getLoadedClassCount()).append('\n')
                .append("# TYPE monolith_business_operations_total counter\nmonolith_business_operations_total ")
                .append(businessOperations.get()).append('\n')
                .append("# TYPE benchmark_requests_total counter\nbenchmark_requests_total{benchmark=\"")
                .append(escape(benchmark)).append("\"} ").append(requests.get()).append('\n');
        CompilationMXBean compilation = ManagementFactory.getCompilationMXBean();
        if (compilation != null && compilation.isCompilationTimeMonitoringSupported()) {
            body.append("# TYPE monolith_jit_compilation_seconds_total counter\nmonolith_jit_compilation_seconds_total ")
                    .append(String.format(Locale.ROOT, "%.6f", compilation.getTotalCompilationTime() / 1_000.0))
                    .append('\n');
        }
        Json.bytes(exchange, 200, "text/plain; version=0.0.4", body.toString());
    }

    private static String jitMillisJson() {
        CompilationMXBean compilation = ManagementFactory.getCompilationMXBean();
        if (compilation == null || !compilation.isCompilationTimeMonitoringSupported()) {
            return "null";
        }
        return String.valueOf(compilation.getTotalCompilationTime());
    }

    private static HttpHandler get(Handler handler) {
        return Routes.guard(exchange -> {
            if (Routes.method(exchange, "GET")) {
                handler.handle(exchange);
            }
        });
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        Json.bytes(exchange, status, "application/json", body);
    }
}
