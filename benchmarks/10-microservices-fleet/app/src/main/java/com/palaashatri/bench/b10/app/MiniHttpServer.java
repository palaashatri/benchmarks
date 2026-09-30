package com.palaashatri.bench.b10.app;

import com.palaashatri.bench.common.ChildJvm;
import com.palaashatri.bench.common.Json;
import com.palaashatri.bench.common.Routes;
import com.palaashatri.bench.common.RuntimeInfo;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Five replica JVMs with readiness-gated, independently serialized redeployment. */
public final class MiniHttpServer implements AutoCloseable {
    private record Replica(int id, long generation, ChildJvm child) {
        Map<String, Object> identity() {
            return Map.of("id", id, "generation", generation, "pid", child.pid(), "port", child.port(),
                    "status", child.alive() ? "UP" : "DOWN");
        }
    }
    private final String benchmark;
    private final boolean gateway = RuntimeInfo.role().equals("gateway");
    private final int serviceId = Integer.parseInt(System.getenv().getOrDefault("BENCH_SERVICE_ID", "0"));
    private final long generation = Long.parseLong(System.getenv().getOrDefault("BENCH_GENERATION", "1"));
    private final AtomicReferenceArray<Replica> replicas = new AtomicReferenceArray<>(5);
    private final Object[] deployLocks = {new Object(), new Object(), new Object(), new Object(), new Object()};
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong replacements = new AtomicLong();
    private final AtomicLong orderIds = new AtomicLong(1);
    private final ConcurrentHashMap<String, Object> orders = new ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService executor = Executors.newCachedThreadPool();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private HttpServer server;
    private boolean closing;

    public MiniHttpServer(String benchmark, String ignoredTitle) { this.benchmark = benchmark; }
    private Replica launch(int id, long generation) throws IOException {
        return new Replica(id, generation, ChildJvm.start(BenchmarkApp.class.getName(), "replica", RuntimeInfo.token(),
                Map.of("BENCH_SERVICE_ID", Integer.toString(id), "BENCH_GENERATION", Long.toString(generation))));
    }
    public void start(int port) throws IOException {
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "fleet-shutdown"));
        try {
            if (gateway) for (int id = 0; id < replicas.length(); id++) replicas.set(id, launch(id, 1));
            else if (!RuntimeInfo.role().equals("replica")) throw new IllegalArgumentException("invalid fleet role");
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 256);
            server.setExecutor(executor);
            server.createContext("/runtime", Routes.guard(e -> Json.bytes(e, 200, "application/json", RuntimeInfo.json())));
            server.createContext("/health", Routes.guard(this::health));
            server.createContext("/metrics", Routes.guard(this::metrics));
            if (gateway) {
                server.createContext("/api/v1/fleet/status", Routes.guard(this::status));
                server.createContext("/api/v1/fleet/deploy/", Routes.guard(this::deploy));
                server.createContext("/api/v1/service/", Routes.guard(this::forwardInventory));
                server.createContext("/api/v1/catalog/", Routes.guard(this::catalog));
                server.createContext("/api/v1/orders", Routes.guard(this::orders));
            } else server.createContext("/inventory/", Routes.guard(this::inventory));
            server.start(); RuntimeInfo.publishPort(server.getAddress().getPort());
            System.out.println(Json.stringify(Map.of("event", "started", "benchmark", benchmark,
                    "role", RuntimeInfo.role(), "port", server.getAddress().getPort(), "pid", ProcessHandle.current().pid())));
        } catch (IOException | RuntimeException failure) { close(); throw failure; }
    }
    private ArrayList<Map<String, Object>> identities() {
        var result = new ArrayList<Map<String, Object>>();
        for (int id = 0; id < replicas.length(); id++) {
            Replica replica = replicas.get(id);
            if (replica != null) result.add(replica.identity());
        }
        return result;
    }
    private long aliveReplicas() { return identities().stream().filter(s -> s.get("status").equals("UP")).count(); }
    private void health(HttpExchange exchange) throws IOException {
        long live = aliveReplicas();
        boolean up = !gateway || live == 5;
        Json.reply(exchange, up ? 200 : 503, Map.of("status", up ? "UP" : "DEGRADED", "external_processes", live,
                "process_model", "multi-jvm-fleet", "role", RuntimeInfo.role()));
    }
    private void status(HttpExchange exchange) throws IOException {
        if (!Routes.method(exchange, "GET")) return;
        Json.reply(exchange, 200, Map.of("process_model", "multi-jvm-fleet", "external_processes", aliveReplicas(), "services", identities()));
    }
    private int id(String text) { return Integer.parseInt(text); }
    private boolean exists(HttpExchange exchange, int id) throws IOException {
        if (id >= 0 && id < replicas.length()) return true;
        Json.reply(exchange, 404, Map.of("error", "service_not_found")); return false;
    }
    private void deploy(HttpExchange exchange) throws IOException {
        if (!Routes.method(exchange, "POST")) return;
        Json.read(exchange);
        int id = id(exchange.getRequestURI().getPath().substring("/api/v1/fleet/deploy/".length()));
        if (!exists(exchange, id)) return;
        synchronized (deployLocks[id]) {
            Replica previous = replicas.get(id);
            long started = System.nanoTime();
            Replica replacement;
            try { replacement = launch(id, previous.generation + 1); }
            catch (IOException failed) { Json.reply(exchange, 503, Map.of("error", "replacement_not_ready")); return; }
            synchronized (this) {
                if (closing) { replacement.child.close(); Json.reply(exchange, 503, Map.of("error", "shutting_down")); return; }
                replicas.set(id, replacement);
            }
            previous.child.close();
            replacements.incrementAndGet();
            Json.reply(exchange, 200, Map.of("service_id", id, "previous_generation", previous.generation,
                    "new_generation", replacement.generation, "previous_pid", previous.child.pid(), "new_pid", replacement.child.pid(),
                    "external_process_restarted", true, "deploy_time_ms", (System.nanoTime() - started) / 1_000_000.0));
        }
    }
    private void forwardInventory(HttpExchange exchange) throws IOException {
        if (!Routes.method(exchange, "GET")) return;
        String[] path = exchange.getRequestURI().getPath().substring("/api/v1/service/".length()).split("/", 3);
        if (path.length != 3 || !path[1].equals("inventory")) { Json.reply(exchange, 400, Map.of("error", "bad_path")); return; }
        int id = id(path[0]);
        if (!exists(exchange, id)) return;
        Replica replica = replicas.get(id);
        requests.incrementAndGet();
        try {
            var response = Routes.call(client, replica.child.port(), "GET", "/inventory/" + path[2], null);
            Json.bytes(exchange, response.statusCode(), "application/json", response.body());
        } catch (IOException unavailable) { Json.reply(exchange, 503, Map.of("error", "replica_unavailable")); }
    }
    private void inventory(HttpExchange exchange) throws IOException {
        if (!Routes.method(exchange, "GET")) return;
        String item = exchange.getRequestURI().getPath().substring("/inventory/".length());
        if (!item.matches("item-[0-9]{1,2}")) { Json.reply(exchange, 404, Map.of("error", "item_not_found")); return; }
        requests.incrementAndGet();
        Json.reply(exchange, 200, Map.of("service_id", serviceId, "generation", generation, "pid", ProcessHandle.current().pid(),
                "item_id", item, "value", "value-" + item.substring(5) + "-service-" + serviceId + "-generation-" + generation));
    }
    private void catalog(HttpExchange exchange) throws IOException {
        if (!Routes.method(exchange, "GET")) return;
        String product = exchange.getRequestURI().getPath().substring("/api/v1/catalog/".length());
        Json.reply(exchange, 200, Map.of("productId", product, "available", 10 + Math.floorMod(product.hashCode(), 500),
                "priceCents", 999 + Math.floorMod(product.hashCode(), 20_000)));
    }
    private void orders(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (exchange.getRequestMethod().equals("POST") && path.equals("/api/v1/orders")) {
            Json.read(exchange);
            String id = "order-" + orderIds.incrementAndGet();
            var body = Map.of("orderId", id, "status", "ACCEPTED");
            orders.put(id, body); Json.reply(exchange, 200, body); return;
        }
        if (exchange.getRequestMethod().equals("GET") && path.startsWith("/api/v1/orders/")) {
            String id = path.substring("/api/v1/orders/".length());
            Json.reply(exchange, 200, orders.getOrDefault(id, Map.of("orderId", id, "status", "UNKNOWN"))); return;
        }
        Json.reply(exchange, 405, Map.of("error", "method_not_allowed"));
    }
    private void metrics(HttpExchange exchange) throws IOException {
        Json.bytes(exchange, 200, "text/plain; version=0.0.4", "fleet_external_processes " + aliveReplicas() + "\n"
                + "fleet_deploy_count " + replacements.get() + "\n" + "fleet_requests_total " + requests.get() + "\n");
    }
    @Override public synchronized void close() {
        closing = true;
        if (server != null) server.stop(0);
        for (int id = 0; id < replicas.length(); id++) if (replicas.get(id) != null) replicas.get(id).child.close();
        executor.shutdownNow();
    }
}
