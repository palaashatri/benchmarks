package com.palaashatri.bench.b02.app;

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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Gateway and three independently owned service JVMs. */
public final class MiniHttpServer implements AutoCloseable {
    private final String benchmark;
    private final String role = RuntimeInfo.role();
    private final List<ChildJvm> children = new ArrayList<>();
    private final ArrayDeque<Object> transactions = new ArrayDeque<>();
    private final AtomicLong ids = new AtomicLong();
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong notifications = new AtomicLong();
    private final AtomicLong calls = new AtomicLong();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private HttpServer server;

    public MiniHttpServer(String benchmark, String ignoredTitle) { this.benchmark = benchmark; }
    public void start(int port) throws IOException {
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "mesh-shutdown"));
        try {
            if (role.equals("gateway")) {
                for (String name : List.of("account", "transaction", "notification"))
                    children.add(ChildJvm.start(BenchmarkApp.class.getName(), name, RuntimeInfo.token(), Map.of()));
            } else if (!List.of("account", "transaction", "notification").contains(role)) {
                throw new IllegalArgumentException("unknown mesh service role");
            }
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 256);
            server.setExecutor(executor);
            server.createContext("/runtime", Routes.guard(e -> Json.bytes(e, 200, "application/json", RuntimeInfo.json())));
            server.createContext("/health", Routes.guard(this::health));
            server.createContext("/state", Routes.guard(this::state));
            server.createContext("/metrics", Routes.guard(this::metrics));
            if (role.equals("gateway")) {
                server.createContext("/api/v1/users/", Routes.guard(this::user));
                server.createContext("/api/v1/orders", Routes.guard(this::order));
            } else if (role.equals("account")) server.createContext("/accounts/", Routes.guard(this::account));
            else if (role.equals("transaction")) server.createContext("/transactions", Routes.guard(this::transaction));
            else server.createContext("/events", Routes.guard(this::event));
            server.start();
            RuntimeInfo.publishPort(server.getAddress().getPort());
            System.out.println(Json.stringify(Map.of("event", "started", "benchmark", benchmark, "role", role,
                    "port", server.getAddress().getPort(), "pid", ProcessHandle.current().pid())));
        } catch (IOException | RuntimeException failure) { close(); throw failure; }
    }
    private void health(HttpExchange exchange) throws IOException {
        boolean up = children.stream().allMatch(ChildJvm::alive);
        Json.reply(exchange, up ? 200 : 503, Map.of("status", up ? "UP" : "DEGRADED", "role", role,
                "process_model", "multi-jvm-mesh", "external_processes", children.stream().filter(ChildJvm::alive).count(),
                "logical_services", role.equals("gateway") ? 4 : 1, "services", children.stream().map(ChildJvm::identity).toList()));
    }
    private void state(HttpExchange exchange) throws IOException {
        synchronized (transactions) {
            Json.reply(exchange, 200, Map.of("transactions_retained", transactions.size(), "notifications_accepted", notifications.get()));
        }
    }
    private void account(HttpExchange exchange) throws IOException {
        if (!Routes.method(exchange, "GET")) return;
        int id = Integer.parseInt(exchange.getRequestURI().getPath().substring("/accounts/".length()));
        if (id < 1 || id > 2_000) { Json.reply(exchange, 404, Map.of("error", "account_not_found")); return; }
        Json.reply(exchange, 200, Map.of("id", Integer.toString(id), "balance_cents", 1_000_000L + id * 17L));
    }
    private void transaction(HttpExchange exchange) throws IOException {
        if (!Routes.method(exchange, "POST")) return;
        var document = Map.of("id", "transaction-" + ids.incrementAndGet(), "status", "RECORDED", "request", Json.read(exchange));
        synchronized (transactions) {
            if (transactions.size() == 10_000) transactions.removeFirst();
            transactions.addLast(document);
        }
        Json.reply(exchange, 200, document);
    }
    private void event(HttpExchange exchange) throws IOException {
        if (!Routes.method(exchange, "POST")) return;
        Json.read(exchange); notifications.incrementAndGet();
        Json.reply(exchange, 202, Map.of("accepted", true));
    }
    private ChildJvm child(String name) { return children.stream().filter(c -> c.role().equals(name)).findFirst().orElseThrow(); }
    private Map<String, Object> call(String role, String method, String path, Object body) throws IOException {
        ChildJvm child = child(role);
        if (!child.alive()) throw new IOException("service stopped");
        calls.incrementAndGet();
        var response = Routes.call(client, child.port(), method, path, body == null ? null : Json.stringify(body));
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IOException("service returned " + response.statusCode());
        return Json.parseObject(response.body());
    }
    private void user(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        if (!Routes.method(exchange, "GET")) return;
        String id = exchange.getRequestURI().getPath().substring("/api/v1/users/".length());
        int number = Integer.parseInt(id);
        if (number < 1 || number > 2_000) { Json.reply(exchange, 404, Map.of("error", "account_not_found")); return; }
        try {
            var account = call("account", "GET", "/accounts/" + number, null);
            call("notification", "POST", "/events", Map.of("user_id", id, "event", "viewed"));
            Json.reply(exchange, 200, Map.of("user_id", id, "account", account));
        } catch (IOException unavailable) { Json.reply(exchange, 503, Map.of("error", "mesh_service_unavailable")); }
    }
    private void order(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        if (!Routes.method(exchange, "POST")) return;
        var body = Json.read(exchange);
        int id = Integer.parseInt(Json.string(body, "from_id", "1001"));
        if (id < 1 || id > 2_000) { Json.reply(exchange, 404, Map.of("error", "account_not_found")); return; }
        try {
            var account = call("account", "GET", "/accounts/" + id, null);
            var transaction = call("transaction", "POST", "/transactions", body);
            Json.reply(exchange, 200, Map.of("status", "ACCEPTED", "account", account, "transaction", transaction));
        } catch (IOException unavailable) { Json.reply(exchange, 503, Map.of("error", "mesh_service_unavailable")); }
    }
    private void metrics(HttpExchange exchange) throws IOException {
        String metrics = "mesh_external_processes " + children.stream().filter(ChildJvm::alive).count() + "\n"
                + "mesh_interservice_calls_total " + calls.get() + "\n"
                + "mesh_notifications_accepted_total " + notifications.get() + "\n"
                + "benchmark_requests_total{benchmark=\"" + benchmark + "\"} " + requests.get() + "\n";
        Json.bytes(exchange, 200, "text/plain; version=0.0.4", metrics);
    }
    @Override public synchronized void close() {
        if (server != null) server.stop(0);
        children.forEach(ChildJvm::close);
        executor.shutdownNow();
    }
}
