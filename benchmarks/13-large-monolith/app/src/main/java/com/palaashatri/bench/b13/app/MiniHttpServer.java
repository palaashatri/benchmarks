package com.palaashatri.bench.b13.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Class-loading prototype. It remains Tier 0, but creates 500 distinct proxy classes. */
public final class MiniHttpServer {
    public interface Component { long apply(long input); }
    static final class IsolatedLoader extends ClassLoader {
        IsolatedLoader(ClassLoader parent) { super(parent); }
    }

    private final String benchmark;
    private final long constructedAt = System.nanoTime();
    private final List<Component> components = new ArrayList<>();
    private final List<ClassLoader> loaders = new ArrayList<>();
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong businessOperations = new AtomicLong();

    public MiniHttpServer(String benchmark, String ignoredTitle) {
        this.benchmark = benchmark;
        for (int index = 1; index <= 500; index++) {
            int id = index;
            ClassLoader loader = new IsolatedLoader(Component.class.getClassLoader());
            Component component = (Component) Proxy.newProxyInstance(loader, new Class<?>[]{Component.class},
                    (proxy, method, arguments) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return switch (method.getName()) {
                                case "toString" -> "GeneratedComponent-" + id;
                                case "hashCode" -> id;
                                case "equals" -> proxy == arguments[0];
                                default -> null;
                            };
                        }
                        long input = (Long) arguments[0];
                        return Long.rotateLeft(input ^ (id * 0x9E3779B97F4A7C15L), id & 63) + id;
                    });
            loaders.add(loader);
            components.add(component);
        }
    }

    public void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 256);
        server.createContext("/health", this::health);
        server.createContext("/runtime", this::runtime);
        server.createContext("/metrics", this::metrics);
        server.createContext("/api/v1/monolith/work", this::work);
        server.createContext("/api/v1/monolith/warmup/status", this::warmup);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.printf("{\"event\":\"started\",\"benchmark\":\"%s\",\"generated_classes\":%d,\"port\":%d,\"pid\":%d}%n",
                benchmark, components.size(), port, ProcessHandle.current().pid());
    }

    private void health(HttpExchange exchange) throws IOException {
        json(exchange, 200, "{\"status\":\"UP\",\"generated_component_classes\":" + components.size()
                + ",\"tier\":\"tier-0\"}");
    }
    private void runtime(HttpExchange exchange) throws IOException {
        json(exchange, 200, "{\"pid\":" + ProcessHandle.current().pid() + ",\"run_token\":\""
                + escape(System.getenv().getOrDefault("BENCH_RUN_TOKEN", "")) + "\",\"java_version\":\""
                + escape(System.getProperty("java.version")) + "\"}");
    }
    private void work(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        long value = requests.get();
        for (Component component : components) value = component.apply(value);
        businessOperations.incrementAndGet();
        json(exchange, 200, "{\"result\":" + value + ",\"components_visited\":" + components.size() + "}");
    }
    private void warmup(HttpExchange exchange) throws IOException {
        json(exchange, 200, "{\"uptime_ms\":" + ((System.nanoTime() - constructedAt) / 1_000_000L)
                + ",\"generated_component_classes\":" + components.size()
                + ",\"loaded_classes\":" + ManagementFactory.getClassLoadingMXBean().getLoadedClassCount()
                + ",\"jit_compilation_ms\":" + compilationMillis()
                + ",\"business_operations\":" + businessOperations.get() + "}");
    }
    private void metrics(HttpExchange exchange) throws IOException {
        String body = "# TYPE monolith_generated_component_classes gauge\nmonolith_generated_component_classes " + components.size() + "\n"
                + "# TYPE monolith_loaded_classes gauge\nmonolith_loaded_classes " + ManagementFactory.getClassLoadingMXBean().getLoadedClassCount() + "\n"
                + "# TYPE monolith_jit_compilation_seconds_total counter\nmonolith_jit_compilation_seconds_total " + format(compilationMillis() / 1_000.0) + "\n"
                + "# TYPE monolith_business_operations_total counter\nmonolith_business_operations_total " + businessOperations.get() + "\n"
                + "# TYPE benchmark_requests_total counter\nbenchmark_requests_total{benchmark=\"" + benchmark + "\"} " + requests.get() + "\n";
        bytes(exchange, 200, "text/plain; version=0.0.4", body);
    }
    private static long compilationMillis() {
        var bean = ManagementFactory.getCompilationMXBean();
        return bean == null || !bean.isCompilationTimeMonitoringSupported() ? -1 : bean.getTotalCompilationTime();
    }
    private static String format(double value) { return String.format(java.util.Locale.ROOT, "%.6f", value); }
    private static String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
    private static void json(HttpExchange exchange, int status, String body) throws IOException { bytes(exchange, status, "application/json", body); }
    private static void bytes(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", type); exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(payload); }
    }
}
