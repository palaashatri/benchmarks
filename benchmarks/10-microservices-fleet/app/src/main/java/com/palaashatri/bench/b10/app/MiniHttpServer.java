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

public final class MiniHttpServer {
    private final String benchmark;
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong ids = new AtomicLong(1);
    private final AtomicLong deployCount = new AtomicLong();
    private final ServiceState[] services = new ServiceState[5];
    // per-service last restart times (epoch ms)
    private final long[] lastRestartMs = new long[5];

    static final class ServiceState {
        final int id;
        final AtomicLong requests = new AtomicLong();
        final ConcurrentHashMap<String, String> inventory = new ConcurrentHashMap<>();
        final long startTime = System.currentTimeMillis();

        ServiceState(int id) {
            this.id = id;
            for (int i = 0; i < 100; i++) {
                inventory.put("item-" + i, "value-" + i + "-svc" + id);
            }
        }
    }

    public MiniHttpServer(String benchmark, String title) {
        this.benchmark = benchmark;
        long now = System.currentTimeMillis();
        for (int i = 0; i < 5; i++) {
            services[i] = new ServiceState(i);
            lastRestartMs[i] = now;
        }
    }

    public void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 256);
        server.createContext("/api/v1/fleet/status", this::fleetStatus);
        server.createContext("/api/v1/fleet/deploy/", this::fleetDeploy);
        server.createContext("/api/v1/service/", this::serviceInventory);
        server.createContext("/api/v1/catalog/", this::catalog);
        server.createContext("/api/v1/orders", this::orders);
        server.createContext("/health", this::health);
        server.createContext("/metrics", this::metrics);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("{\"event\":\"started\",\"benchmark\":\"" + benchmark + "\",\"port\":" + port + "}");
    }

    // GET /api/v1/fleet/status
    private void fleetStatus(HttpExchange ex) throws IOException {
        requests.incrementAndGet();
        StringBuilder sb = new StringBuilder("{\"services\":[");
        for (int i = 0; i < 5; i++) {
            ServiceState s = services[i];
            long uptimeMs = System.currentTimeMillis() - s.startTime;
            if (i > 0) sb.append(',');
            sb.append("{\"id\":").append(i)
              .append(",\"status\":\"UP\"")
              .append(",\"requests\":").append(s.requests.get())
              .append(",\"uptime_ms\":").append(uptimeMs)
              .append('}');
        }
        sb.append("]}");
        json(ex, 200, sb.toString());
    }

    // POST /api/v1/fleet/deploy/{serviceId}
    private void fleetDeploy(HttpExchange ex) throws IOException {
        requests.incrementAndGet();
        String path = ex.getRequestURI().getPath();
        // consume body (required to avoid broken pipe on some JDK versions)
        ex.getRequestBody().readAllBytes();
        String suffix = path.substring("/api/v1/fleet/deploy/".length());
        int serviceId;
        try {
            serviceId = Integer.parseInt(suffix.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            json(ex, 400, "{\"error\":\"invalid service id\"}");
            return;
        }
        if (serviceId < 0 || serviceId >= 5) {
            json(ex, 404, "{\"error\":\"service id out of range\"}");
            return;
        }
        long t0 = System.nanoTime();
        services[serviceId] = new ServiceState(serviceId);
        long deployTimeMs = Math.max(1L, (System.nanoTime() - t0) / 1_000_000L);
        lastRestartMs[serviceId] = System.currentTimeMillis();
        deployCount.incrementAndGet();
        json(ex, 200, "{\"service_id\":" + serviceId
                + ",\"deployed\":true"
                + ",\"deploy_time_ms\":" + deployTimeMs
                + ",\"downtime_ms\":" + deployTimeMs + "}");
    }

    // GET /api/v1/service/{id}/inventory/{itemId}
    private void serviceInventory(HttpExchange ex) throws IOException {
        requests.incrementAndGet();
        String path = ex.getRequestURI().getPath();
        // path format: /api/v1/service/{id}/inventory/{itemId}
        String rest = path.substring("/api/v1/service/".length());
        // rest = "{id}/inventory/{itemId}"
        int slashAfterSvcId = rest.indexOf('/');
        if (slashAfterSvcId < 0) {
            json(ex, 400, "{\"error\":\"bad path\"}");
            return;
        }
        String svcIdStr = rest.substring(0, slashAfterSvcId);
        String remaining = rest.substring(slashAfterSvcId + 1); // "inventory/{itemId}"
        int inventorySlash = remaining.indexOf('/');
        if (inventorySlash < 0 || !remaining.startsWith("inventory/")) {
            json(ex, 400, "{\"error\":\"bad path\"}");
            return;
        }
        String itemId = remaining.substring("inventory/".length());
        int serviceId;
        try {
            serviceId = Integer.parseInt(svcIdStr);
        } catch (NumberFormatException e) {
            json(ex, 400, "{\"error\":\"invalid service id\"}");
            return;
        }
        if (serviceId < 0 || serviceId >= 5) {
            json(ex, 404, "{\"error\":\"service not found\"}");
            return;
        }
        ServiceState s = services[serviceId];
        s.requests.incrementAndGet();
        String value = s.inventory.get(itemId);
        if (value == null) {
            json(ex, 404, "{\"error\":\"item not found\",\"service_id\":" + serviceId
                    + ",\"item_id\":\"" + escape(itemId) + "\"}");
            return;
        }
        json(ex, 200, "{\"service_id\":" + serviceId
                + ",\"item_id\":\"" + escape(itemId) + "\""
                + ",\"value\":\"" + escape(value) + "\""
                + ",\"requests\":" + s.requests.get() + "}");
    }

    // GET /api/v1/catalog/{productId}  (backward compat)
    private void catalog(HttpExchange ex) throws IOException {
        requests.incrementAndGet();
        String path = ex.getRequestURI().getPath();
        String product = path.substring("/api/v1/catalog/".length());
        long stock = 10 + Math.floorMod(product.hashCode(), 500);
        long price = 999 + Math.floorMod(product.hashCode(), 20_000);
        json(ex, 200, "{\"productId\":\"" + escape(product)
                + "\",\"available\":" + stock
                + ",\"priceCents\":" + price
                + ",\"pricingRules\":8}");
    }

    // POST /api/v1/orders  and  GET /api/v1/orders/{id}  (backward compat)
    // Both /api/v1/orders and /api/v1/orders/ are routed here by the context "/api/v1/orders"
    private final ConcurrentHashMap<String, String> orderDocs = new ConcurrentHashMap<>();
    {
        orderDocs.put("order-1", "{\"orderId\":\"order-1\",\"status\":\"SEEDED\"}");
    }

    private void orders(HttpExchange ex) throws IOException {
        requests.incrementAndGet();
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        if ("POST".equals(method) && path.equals("/api/v1/orders")) {
            ex.getRequestBody().readAllBytes(); // consume body
            String id = "order-" + ids.getAndIncrement();
            String doc = "{\"orderId\":\"" + id + "\",\"status\":\"ACCEPTED\",\"auditPublished\":true}";
            orderDocs.put(id, doc);
            json(ex, 200, doc);
        } else if ("GET".equals(method) && path.startsWith("/api/v1/orders/")) {
            String id = path.substring("/api/v1/orders/".length());
            String doc = orderDocs.getOrDefault(id,
                    "{\"orderId\":\"" + escape(id) + "\",\"status\":\"UNKNOWN\"}");
            json(ex, 200, doc);
        } else {
            ex.getRequestBody().readAllBytes();
            json(ex, 404, "{\"error\":\"no route\",\"path\":\"" + escape(path) + "\"}");
        }
    }

    // GET /health
    private void health(HttpExchange ex) throws IOException {
        json(ex, 200, "{\"status\":\"UP\",\"services_running\":5,\"benchmark\":\"" + benchmark + "\"}");
    }

    // GET /metrics
    private void metrics(HttpExchange ex) throws IOException {
        long totalReqs = requests.get();
        long deploys = deployCount.get();
        String body = "fleet_requests_total " + totalReqs + "\n"
                + "fleet_deploy_count " + deploys + "\n"
                + "fleet_services_up 5\n"
                + "benchmark_requests_total{benchmark=\"" + benchmark + "\"} " + totalReqs + "\n";
        bytes(ex, 200, "text/plain; version=0.0.4", body);
    }

    private static String escape(String raw) {
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
}
