package com.palaashatri.bench.b10.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Single-JVM fleet-state simulator.
 *
 * Five logical service states are hosted by one process. A replacement creates
 * a new immutable service generation; it does not restart an external JVM.
 */
public final class MiniHttpServer {
    static final class ServiceState {
        final int id;
        final long generation;
        final long startedAtMs;
        final AtomicLong requests = new AtomicLong();
        final ConcurrentHashMap<String, String> inventory = new ConcurrentHashMap<>();

        ServiceState(int id, long generation) {
            this.id = id;
            this.generation = generation;
            this.startedAtMs = System.currentTimeMillis();
            for (int item = 0; item < 100; item++) {
                inventory.put(
                        "item-" + item,
                        "value-" + item + "-service-" + id + "-generation-" + generation);
            }
        }
    }

    private final String benchmark;
    private final AtomicReferenceArray<ServiceState> services = new AtomicReferenceArray<>(5);
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong replacements = new AtomicLong();
    private final AtomicLong orderIds = new AtomicLong(1);
    private final ConcurrentHashMap<String, String> orders = new ConcurrentHashMap<>();

    public MiniHttpServer(String benchmark, String ignoredTitle) {
        this.benchmark = benchmark;
        for (int index = 0; index < services.length(); index++) {
            services.set(index, new ServiceState(index, 1));
        }
        orders.put("order-1", "{\"orderId\":\"order-1\",\"status\":\"SEEDED\"}");
    }

    public void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 256);
        server.createContext("/health", this::health);
        server.createContext("/runtime", this::runtime);
        server.createContext("/metrics", this::metrics);
        server.createContext("/api/v1/fleet/status", this::fleetStatus);
        server.createContext("/api/v1/fleet/deploy/", this::replaceService);
        server.createContext("/api/v1/service/", this::inventory);
        server.createContext("/api/v1/catalog/", this::catalog);
        server.createContext("/api/v1/orders", this::orders);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.printf(
                "{\"event\":\"started\",\"benchmark\":\"%s\","
                        + "\"process_model\":\"single-jvm-simulation\","
                        + "\"port\":%d,\"pid\":%d}%n",
                benchmark,
                port,
                ProcessHandle.current().pid());
    }

    private void health(HttpExchange exchange) throws IOException {
        json(exchange, 200,
                "{\"status\":\"UP\",\"logical_services\":5,"
                        + "\"external_processes\":0,"
                        + "\"process_model\":\"single-jvm-simulation\"}");
    }

    private void runtime(HttpExchange exchange) throws IOException {
        json(exchange, 200,
                "{\"pid\":" + ProcessHandle.current().pid()
                        + ",\"run_token\":\""
                        + escape(System.getenv().getOrDefault("BENCH_RUN_TOKEN", ""))
                        + "\",\"java_version\":\""
                        + escape(System.getProperty("java.version")) + "\"}");
    }

    private void fleetStatus(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        StringBuilder body = new StringBuilder(
                "{\"process_model\":\"single-jvm-simulation\","
                        + "\"external_processes\":0,\"services\":[");
        for (int index = 0; index < services.length(); index++) {
            ServiceState service = services.get(index);
            if (index > 0) body.append(',');
            body.append("{\"id\":").append(service.id)
                    .append(",\"generation\":").append(service.generation)
                    .append(",\"status\":\"UP\"")
                    .append(",\"requests\":").append(service.requests.get())
                    .append(",\"uptime_ms\":")
                    .append(System.currentTimeMillis() - service.startedAtMs)
                    .append('}');
        }
        body.append("]}");
        json(exchange, 200, body.toString());
    }

    private void replaceService(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            json(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        exchange.getRequestBody().readAllBytes();
        String suffix = exchange.getRequestURI().getPath()
                .substring("/api/v1/fleet/deploy/".length());
        int id;
        try {
            id = Integer.parseInt(suffix);
        } catch (NumberFormatException invalid) {
            json(exchange, 400, "{\"error\":\"invalid_service_id\"}");
            return;
        }
        if (id < 0 || id >= services.length()) {
            json(exchange, 404, "{\"error\":\"service_not_found\"}");
            return;
        }
        long started = System.nanoTime();
        while (true) {
            ServiceState previous = services.get(id);
            ServiceState replacement = new ServiceState(id, previous.generation + 1);
            if (services.compareAndSet(id, previous, replacement)) {
                replacements.incrementAndGet();
                json(exchange, 200,
                        "{\"service_id\":" + id
                                + ",\"previous_generation\":" + previous.generation
                                + ",\"new_generation\":" + replacement.generation
                                + ",\"replacement_build_ms\":"
                                + format((System.nanoTime() - started) / 1_000_000.0)
                                + ",\"external_process_restarted\":false,"
                                + "\"simulated_downtime_ms\":0}");
                return;
            }
        }
    }

    private void inventory(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        String rest = exchange.getRequestURI().getPath()
                .substring("/api/v1/service/".length());
        String[] pieces = rest.split("/", 3);
        if (pieces.length != 3 || !"inventory".equals(pieces[1])) {
            json(exchange, 400, "{\"error\":\"bad_path\"}");
            return;
        }
        int id;
        try {
            id = Integer.parseInt(pieces[0]);
        } catch (NumberFormatException invalid) {
            json(exchange, 400, "{\"error\":\"invalid_service_id\"}");
            return;
        }
        if (id < 0 || id >= services.length()) {
            json(exchange, 404, "{\"error\":\"service_not_found\"}");
            return;
        }
        ServiceState service = services.get(id);
        service.requests.incrementAndGet();
        String value = service.inventory.get(pieces[2]);
        if (value == null) {
            json(exchange, 404, "{\"error\":\"item_not_found\"}");
            return;
        }
        json(exchange, 200,
                "{\"service_id\":" + id
                        + ",\"generation\":" + service.generation
                        + ",\"item_id\":\"" + escape(pieces[2])
                        + "\",\"value\":\"" + escape(value) + "\"}");
    }

    private void catalog(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        String product = exchange.getRequestURI().getPath()
                .substring("/api/v1/catalog/".length());
        long stock = 10 + Math.floorMod(product.hashCode(), 500);
        long price = 999 + Math.floorMod(product.hashCode(), 20_000);
        json(exchange, 200,
                "{\"productId\":\"" + escape(product)
                        + "\",\"available\":" + stock
                        + ",\"priceCents\":" + price + "}");
    }

    private void orders(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        if ("POST".equalsIgnoreCase(method) && "/api/v1/orders".equals(path)) {
            exchange.getRequestBody().readAllBytes();
            String id = "order-" + orderIds.getAndIncrement();
            String document = "{\"orderId\":\"" + id
                    + "\",\"status\":\"ACCEPTED\"}";
            orders.put(id, document);
            json(exchange, 200, document);
            return;
        }
        if ("GET".equalsIgnoreCase(method) && path.startsWith("/api/v1/orders/")) {
            String id = path.substring("/api/v1/orders/".length());
            json(exchange, 200, orders.getOrDefault(
                    id,
                    "{\"orderId\":\"" + escape(id) + "\",\"status\":\"UNKNOWN\"}"));
            return;
        }
        json(exchange, 404, "{\"error\":\"not_found\"}");
    }

    private void metrics(HttpExchange exchange) throws IOException {
        String body = "# TYPE fleet_logical_services gauge\n"
                + "fleet_logical_services 5\n"
                + "# TYPE fleet_external_processes gauge\n"
                + "fleet_external_processes 0\n"
                + "# TYPE fleet_state_replacements_total counter\n"
                + "fleet_state_replacements_total " + replacements.get() + "\n"
                + "# TYPE benchmark_requests_total counter\n"
                + "benchmark_requests_total{benchmark=\"" + benchmark + "\"} "
                + requests.get() + "\n";
        bytes(exchange, 200, "text/plain; version=0.0.4", body);
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.6f", value);
    }

    private static String escape(String value) {
        return value == null
                ? ""
                : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void json(HttpExchange exchange, int status, String body)
            throws IOException {
        bytes(exchange, status, "application/json", body);
    }

    private static void bytes(
            HttpExchange exchange, int status, String type, String body)
            throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }
}
