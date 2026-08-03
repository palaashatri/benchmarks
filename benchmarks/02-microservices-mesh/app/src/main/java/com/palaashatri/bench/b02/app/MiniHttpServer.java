package com.palaashatri.bench.b02.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single-JVM, multi-server service-mesh prototype.
 *
 * The inner services bind ephemeral loopback ports so the workload cannot
 * collide with adjacent fixed ports. It remains one JVM and reports that fact.
 */
public final class MiniHttpServer {
    static final class Latencies {
        private final ArrayDeque<Long> values = new ArrayDeque<>();

        synchronized void add(long nanos) {
            if (values.size() == 4_096) values.removeFirst();
            values.addLast(nanos);
        }

        synchronized double p99Millis() {
            if (values.isEmpty()) return 0;
            List<Long> snapshot = new ArrayList<>(values);
            snapshot.sort(null);
            int index = Math.min(snapshot.size() - 1,
                    Math.max(0, (int) Math.ceil(snapshot.size() * .99) - 1));
            return snapshot.get(index) / 1_000_000.0;
        }
    }

    private final String benchmark;
    private final ConcurrentHashMap<String, String> accounts = new ConcurrentHashMap<>();
    private final ArrayDeque<String> transactions = new ArrayDeque<>();
    private final AtomicLong ids = new AtomicLong(1);
    private final AtomicLong proxyRequests = new AtomicLong();
    private final AtomicLong accountCalls = new AtomicLong();
    private final AtomicLong transactionCalls = new AtomicLong();
    private final AtomicLong notificationCalls = new AtomicLong();
    private final AtomicLong notificationsAccepted = new AtomicLong();
    private final Latencies interServiceLatencies = new Latencies();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();

    private int accountPort;
    private int transactionPort;
    private int notificationPort;

    public MiniHttpServer(String benchmark, String ignoredTitle) {
        this.benchmark = benchmark;
        for (int id = 1; id <= 2_000; id++) {
            accounts.put(Integer.toString(id),
                    "{\"id\":\"" + id + "\",\"balance_cents\":"
                            + (1_000_000L + id * 17L) + "}");
        }
    }

    public void start(int port) throws IOException {
        accountPort = startAccountService();
        transactionPort = startTransactionService();
        notificationPort = startNotificationService();

        HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 512);
        proxy.createContext("/health", this::health);
        proxy.createContext("/runtime", this::runtime);
        proxy.createContext("/metrics", this::metrics);
        proxy.createContext("/api/v1/users/", this::user);
        proxy.createContext("/api/v1/orders", this::order);
        proxy.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        proxy.start();
        System.out.printf(
                "{\"event\":\"started\",\"benchmark\":\"%s\","
                        + "\"process_model\":\"single-jvm-multi-server\","
                        + "\"port\":%d,\"pid\":%d}%n",
                benchmark,
                port,
                ProcessHandle.current().pid());
    }

    private int startAccountService() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 256);
        server.createContext("/accounts/", exchange -> {
            String id = exchange.getRequestURI().getPath().substring("/accounts/".length());
            String account = accounts.get(id);
            if (account == null) {
                json(exchange, 404, "{\"error\":\"account_not_found\"}");
            } else {
                json(exchange, 200, account);
            }
        });
        server.createContext("/health", exchange ->
                json(exchange, 200, "{\"status\":\"UP\",\"service\":\"account\"}"));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return server.getAddress().getPort();
    }

    private int startTransactionService() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 256);
        server.createContext("/transactions", exchange -> {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                json(exchange, 405, "{\"error\":\"method_not_allowed\"}");
                return;
            }
            String body = new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String id = "transaction-" + ids.getAndIncrement();
            String document = "{\"id\":\"" + id + "\",\"status\":\"RECORDED\","
                    + "\"request\":" + (body.isBlank() ? "{}" : body) + "}";
            synchronized (transactions) {
                if (transactions.size() == 10_000) transactions.removeFirst();
                transactions.addLast(document);
            }
            json(exchange, 200, document);
        });
        server.createContext("/health", exchange ->
                json(exchange, 200, "{\"status\":\"UP\",\"service\":\"transaction\"}"));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return server.getAddress().getPort();
    }

    private int startNotificationService() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 256);
        server.createContext("/events", exchange -> {
            exchange.getRequestBody().readAllBytes();
            notificationsAccepted.incrementAndGet();
            json(exchange, 202, "{\"accepted\":true}");
        });
        server.createContext("/health", exchange ->
                json(exchange, 200, "{\"status\":\"UP\",\"service\":\"notification\"}"));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return server.getAddress().getPort();
    }

    private void health(HttpExchange exchange) throws IOException {
        json(exchange, 200,
                "{\"status\":\"UP\",\"logical_services\":4,"
                        + "\"external_processes\":0,"
                        + "\"process_model\":\"single-jvm-multi-server\"}");
    }

    private void runtime(HttpExchange exchange) throws IOException {
        json(exchange, 200,
                "{\"pid\":" + ProcessHandle.current().pid()
                        + ",\"run_token\":\""
                        + escape(System.getenv().getOrDefault("BENCH_RUN_TOKEN", ""))
                        + "\",\"java_version\":\""
                        + escape(System.getProperty("java.version")) + "\"}");
    }

    private void user(HttpExchange exchange) throws IOException {
        proxyRequests.incrementAndGet();
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            json(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        String id = exchange.getRequestURI().getPath().substring("/api/v1/users/".length());
        String account = call("GET", accountPort, "/accounts/" + id, null, accountCalls);
        if (account == null) {
            json(exchange, 503, "{\"error\":\"account_service_unavailable\"}");
            return;
        }
        notificationCalls.incrementAndGet();
        client.sendAsync(
                request("POST", notificationPort, "/events",
                        "{\"user_id\":\"" + escape(id) + "\",\"event\":\"viewed\"}"),
                HttpResponse.BodyHandlers.discarding());
        json(exchange, 200,
                "{\"user_id\":\"" + escape(id) + "\",\"account\":" + account + "}");
    }

    private void order(HttpExchange exchange) throws IOException {
        proxyRequests.incrementAndGet();
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            json(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        String body = new String(
                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String accountId = field(body, "from_id", "1001");
        String account = call(
                "GET", accountPort, "/accounts/" + accountId, null, accountCalls);
        if (account == null) {
            json(exchange, 503, "{\"error\":\"account_service_unavailable\"}");
            return;
        }
        String transaction = call(
                "POST", transactionPort, "/transactions", body, transactionCalls);
        if (transaction == null) {
            json(exchange, 503, "{\"error\":\"transaction_service_unavailable\"}");
            return;
        }
        json(exchange, 200,
                "{\"status\":\"ACCEPTED\",\"account\":" + account
                        + ",\"transaction\":" + transaction + "}");
    }

    private String call(
            String method,
            int port,
            String path,
            String body,
            AtomicLong counter) {
        counter.incrementAndGet();
        long started = System.nanoTime();
        try {
            HttpResponse<String> response = client.send(
                    request(method, port, path, body),
                    HttpResponse.BodyHandlers.ofString());
            interServiceLatencies.add(System.nanoTime() - started);
            return response.statusCode() >= 200 && response.statusCode() < 300
                    ? response.body()
                    : null;
        } catch (Exception exception) {
            interServiceLatencies.add(System.nanoTime() - started);
            return null;
        }
    }

    private static HttpRequest request(String method, int port, String path, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(2));
        return "POST".equals(method)
                ? builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                body == null ? "{}" : body, StandardCharsets.UTF_8))
                        .build()
                : builder.GET().build();
    }

    private void metrics(HttpExchange exchange) throws IOException {
        int transactionCount;
        synchronized (transactions) {
            transactionCount = transactions.size();
        }
        String body = "# TYPE mesh_proxy_requests_total counter\n"
                + "mesh_proxy_requests_total " + proxyRequests.get() + "\n"
                + "# TYPE mesh_interservice_calls_total counter\n"
                + "mesh_interservice_calls_total{service=\"account\"} " + accountCalls.get() + "\n"
                + "mesh_interservice_calls_total{service=\"transaction\"} " + transactionCalls.get() + "\n"
                + "mesh_interservice_calls_total{service=\"notification\"} " + notificationCalls.get() + "\n"
                + "# TYPE mesh_interservice_latency_p99_seconds gauge\n"
                + "mesh_interservice_latency_p99_seconds "
                + format(interServiceLatencies.p99Millis() / 1_000.0) + "\n"
                + "# TYPE mesh_transactions_retained gauge\n"
                + "mesh_transactions_retained " + transactionCount + "\n"
                + "# TYPE mesh_notifications_accepted_total counter\n"
                + "mesh_notifications_accepted_total " + notificationsAccepted.get() + "\n"
                + "# TYPE mesh_external_processes gauge\n"
                + "mesh_external_processes 0\n"
                + "# TYPE benchmark_requests_total counter\n"
                + "benchmark_requests_total{benchmark=\"" + benchmark + "\"} "
                + proxyRequests.get() + "\n";
        bytes(exchange, 200, "text/plain; version=0.0.4", body);
    }

    private static String field(String body, String name, String fallback) {
        String key = "\"" + name + "\"";
        int at = body.indexOf(key);
        if (at < 0) return fallback;
        int colon = body.indexOf(':', at + key.length());
        int start = body.indexOf('"', colon + 1);
        int end = start < 0 ? -1 : body.indexOf('"', start + 1);
        return colon < 0 || start < 0 || end < 0
                ? fallback
                : body.substring(start + 1, end);
    }

    private static String escape(String value) {
        return value == null
                ? ""
                : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.9f", value);
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
