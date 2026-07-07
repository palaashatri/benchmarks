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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 4-service in-JVM mesh for benchmark 02-microservices-mesh.
 * <p>
 * Port layout (relative to the main/proxy port P):
 *   P+0 – MeshProxy (main entry point)
 *   P+1 – AccountService
 *   P+2 – TransactionService
 *   P+3 – NotificationService
 * <p>
 * All HttpServer executors use virtual threads.
 */
public final class MiniHttpServer {

    // ── circuit breaker ────────────────────────────────────────────────────────

    static final class CircuitBreaker {
        private static final int FAILURE_THRESHOLD = 5;
        private static final long OPEN_DURATION_MS = 5_000L;

        private final ReentrantLock lock = new ReentrantLock();
        private int consecutiveFailures = 0;
        private long openUntil = 0L;
        private final AtomicLong openCount = new AtomicLong();

        boolean isOpen() {
            return System.currentTimeMillis() < openUntil;
        }

        void recordSuccess() {
            lock.lock();
            try { consecutiveFailures = 0; }
            finally { lock.unlock(); }
        }

        void recordFailure() {
            lock.lock();
            try {
                consecutiveFailures++;
                if (consecutiveFailures >= FAILURE_THRESHOLD) {
                    openUntil = System.currentTimeMillis() + OPEN_DURATION_MS;
                    openCount.incrementAndGet();
                }
            } finally {
                lock.unlock();
            }
        }

        long openCount() { return openCount.get(); }
    }

    // ── p99 latency tracker (ring-buffer approach) ─────────────────────────────

    static final class LatencyTracker {
        private static final int CAPACITY = 4096;
        private final ArrayBlockingQueue<Long> samples = new ArrayBlockingQueue<>(CAPACITY);

        void record(long nanos) {
            long ms100 = nanos / 100_000L; // store in units of 0.1 ms for compactness
            if (!samples.offer(ms100)) {
                samples.poll();
                samples.offer(ms100);
            }
        }

        double p99Ms() {
            List<Long> snap = new ArrayList<>(samples);
            if (snap.isEmpty()) return 0.0;
            snap.sort(null);
            int idx = (int) Math.ceil(0.99 * snap.size()) - 1;
            if (idx < 0) idx = 0;
            return snap.get(idx) / 10.0;
        }
    }

    // ── shared state ───────────────────────────────────────────────────────────

    private final String benchmark;

    // Account data
    private final ConcurrentHashMap<String, String> accounts = new ConcurrentHashMap<>();

    // Transaction log (bounded)
    private final ArrayBlockingQueue<String> txLog = new ArrayBlockingQueue<>(10_000);

    // Notification event counter
    private final AtomicLong notificationEvents = new AtomicLong();

    // Metrics
    private final AtomicLong proxyRequests = new AtomicLong();
    private final AtomicLong accountCalls = new AtomicLong();
    private final AtomicLong transactionCalls = new AtomicLong();
    private final AtomicLong notificationCalls = new AtomicLong();
    private final LatencyTracker interServiceLatency = new LatencyTracker();
    private final AtomicLong totalCircuitOpenCount = new AtomicLong();

    // Compatibility counters for old smoke paths
    private final ConcurrentHashMap<String, Long> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> documents = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong(1);

    // Circuit breakers
    private CircuitBreaker accountCb;
    private CircuitBreaker transactionCb;
    private CircuitBreaker notificationCb;

    // HttpClient for inter-service calls
    private HttpClient interServiceClient;

    // Inner service ports (set in start())
    private int accountPort;
    private int transactionPort;
    private int notificationPort;

    public MiniHttpServer(String benchmark, String title) {
        this.benchmark = benchmark;
        seedAccounts();
        // Pre-seed compatibility state
        documents.put("order-1", "{\"orderId\":\"order-1\",\"status\":\"SEEDED\"}");
    }

    // ── account seeding ────────────────────────────────────────────────────────

    private void seedAccounts() {
        for (int i = 1; i <= 2000; i++) {
            long balance = 1_000_000L + i * 17L;
            accounts.put(String.valueOf(i), buildAccountJson(String.valueOf(i), balance));
        }
        accounts.put("1001", buildAccountJson("1001", 1_500_000L));
        accounts.put("1002", buildAccountJson("1002", 1_250_000L));
    }

    private static String buildAccountJson(String id, long balance) {
        return "{\"id\":\"" + escape(id) + "\",\"balance\":" + balance + ",\"currency\":\"USD\"}";
    }

    // ── public entry point ─────────────────────────────────────────────────────

    public void start(int port) throws IOException {
        accountPort = port + 1;
        transactionPort = port + 2;
        notificationPort = port + 3;

        accountCb = new CircuitBreaker();
        transactionCb = new CircuitBreaker();
        notificationCb = new CircuitBreaker();

        interServiceClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();

        startAccountService(accountPort);
        startTransactionService(transactionPort);
        startNotificationService(notificationPort);

        startProxy(port);
        log("started", "\"port\":" + port
                + ",\"accountPort\":" + accountPort
                + ",\"transactionPort\":" + transactionPort
                + ",\"notificationPort\":" + notificationPort);
    }

    // ── AccountService ─────────────────────────────────────────────────────────

    private void startAccountService(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 256);
        server.createContext("/accounts", ex -> {
            String path = ex.getRequestURI().getPath();
            // /accounts/{id}
            if (path.startsWith("/accounts/")) {
                String id = path.substring("/accounts/".length());
                String account = accounts.get(id);
                if (account != null) {
                    json(ex, 200, account);
                } else {
                    json(ex, 404, "{\"error\":\"account not found\",\"id\":\"" + escape(id) + "\"}");
                }
            } else {
                json(ex, 400, "{\"error\":\"bad request\"}");
            }
        });
        server.createContext("/health", ex -> json(ex, 200, "{\"status\":\"UP\",\"service\":\"account\"}"));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    // ── TransactionService ─────────────────────────────────────────────────────

    private void startTransactionService(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 256);
        server.createContext("/transactions", ex -> {
            String method = ex.getRequestMethod();
            if ("POST".equals(method)) {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String txId = "tx-" + System.nanoTime();
                String fromId = field(body, "from_id", "unknown");
                String item = field(body, "item", "unknown");
                long amount = number(body, "amount", 0L);
                String txJson = "{\"txId\":\"" + txId + "\",\"from\":\"" + escape(fromId)
                        + "\",\"item\":\"" + escape(item) + "\",\"amount\":" + amount
                        + ",\"status\":\"RECORDED\"}";
                if (!txLog.offer(txJson)) txLog.poll(); // drop oldest if full
                txLog.offer(txJson);
                json(ex, 200, txJson);
            } else {
                json(ex, 405, "{\"error\":\"method not allowed\"}");
            }
        });
        server.createContext("/health", ex -> json(ex, 200, "{\"status\":\"UP\",\"service\":\"transaction\"}"));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    // ── NotificationService ────────────────────────────────────────────────────

    private void startNotificationService(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 256);
        server.createContext("/events", ex -> {
            // Read and discard body; always accept
            ex.getRequestBody().readAllBytes();
            notificationEvents.incrementAndGet();
            json(ex, 200, "{\"accepted\":true}");
        });
        server.createContext("/health", ex -> json(ex, 200, "{\"status\":\"UP\",\"service\":\"notification\"}"));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    // ── MeshProxy ─────────────────────────────────────────────────────────────

    private void startProxy(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 512);
        server.createContext("/api/v1/", this::proxyRoute);
        server.createContext("/health", this::proxyHealth);
        server.createContext("/metrics", this::proxyMetrics);
        // Backwards-compatible paths (used by app smoke test)
        server.createContext("/actuator/health", this::proxyHealth);
        server.createContext("/actuator/prometheus", this::proxyMetrics);
        server.createContext("/events", this::compatEvents);
        server.createContext("/flows/", this::compatFlows);
        server.createContext("/notifications/stub", this::compatNotifications);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    private void proxyRoute(HttpExchange ex) throws IOException {
        proxyRequests.incrementAndGet();
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();

        try {
            if ("GET".equals(method) && path.startsWith("/api/v1/users/")) {
                String userId = path.substring("/api/v1/users/".length());
                handleGetUser(ex, userId);
            } else if ("POST".equals(method) && path.equals("/api/v1/orders")) {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                handlePostOrder(ex, body);
            } else if ("GET".equals(method) && path.equals("/api/v1/health")) {
                handleMeshHealth(ex);
            } else {
                json(ex, 404, "{\"error\":\"route not found\",\"path\":\"" + escape(path) + "\"}");
            }
        } catch (Exception e) {
            json(ex, 500, "{\"error\":\"internal\",\"msg\":\"" + escape(e.getMessage() == null ? "null" : e.getMessage()) + "\"}");
        }
    }

    private void handleGetUser(HttpExchange ex, String userId) throws IOException {
        // Call AccountService
        String accountJson = callAccount(userId);
        if (accountJson == null) {
            json(ex, 503, "{\"error\":\"account service unavailable\"}");
            return;
        }

        // Fire-and-forget notification (do NOT block on it)
        final String notifyBody = "{\"userId\":\"" + escape(userId) + "\",\"event\":\"user_viewed\"}";
        final int notPort = notificationPort;
        notificationCalls.incrementAndGet();
        interServiceClient.sendAsync(
                HttpRequest.newBuilder(URI.create("http://localhost:" + notPort + "/events"))
                        .POST(HttpRequest.BodyPublishers.ofString(notifyBody, StandardCharsets.UTF_8))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(1))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        ).whenComplete((resp, err) -> {
            if (err != null) notificationCb.recordFailure();
            else notificationCb.recordSuccess();
        });

        json(ex, 200, "{\"userId\":\"" + escape(userId) + "\",\"account\":" + accountJson + "}");
    }

    private void handlePostOrder(HttpExchange ex, String body) throws IOException {
        String fromId = field(body, "from_id", "1001");
        String item = field(body, "item", "item");
        long amount = number(body, "amount", 0L);

        // 1. Verify account
        String accountJson = callAccount(fromId);
        if (accountJson == null) {
            json(ex, 503, "{\"error\":\"account service unavailable\"}");
            return;
        }

        // 2. Record transaction
        String txBody = "{\"from_id\":\"" + escape(fromId) + "\",\"item\":\"" + escape(item) + "\",\"amount\":" + amount + "}";
        String txResult = callTransaction(txBody);
        if (txResult == null) {
            json(ex, 503, "{\"error\":\"transaction service unavailable\"}");
            return;
        }

        json(ex, 200, "{\"status\":\"ACCEPTED\",\"account\":" + accountJson + ",\"transaction\":" + txResult + "}");
    }

    private void handleMeshHealth(HttpExchange ex) throws IOException {
        boolean accountUp = pingService("http://localhost:" + accountPort + "/health");
        boolean transactionUp = pingService("http://localhost:" + transactionPort + "/health");
        boolean notificationUp = pingService("http://localhost:" + notificationPort + "/health");
        String overall = (accountUp && transactionUp && notificationUp) ? "UP" : "DEGRADED";
        json(ex, 200, "{\"status\":\"" + overall + "\","
                + "\"account\":\"" + (accountUp ? "UP" : "DOWN") + "\","
                + "\"transaction\":\"" + (transactionUp ? "UP" : "DOWN") + "\","
                + "\"notification\":\"" + (notificationUp ? "UP" : "DOWN") + "\"}");
    }

    private void proxyHealth(HttpExchange ex) throws IOException {
        json(ex, 200, "{\"status\":\"UP\",\"benchmark\":\"" + benchmark + "\"}");
    }

    private void proxyMetrics(HttpExchange ex) throws IOException {
        long circuitOpenCount = accountCb.openCount() + transactionCb.openCount() + notificationCb.openCount();
        double p99 = interServiceLatency.p99Ms();
        String body = "# TYPE benchmark_requests_total counter\n"
                + "benchmark_requests_total{benchmark=\"" + benchmark + "\"} " + proxyRequests.get() + "\n"
                + "# TYPE inter_service_calls_total counter\n"
                + "inter_service_calls_total{service=\"account\"} " + accountCalls.get() + "\n"
                + "inter_service_calls_total{service=\"transaction\"} " + transactionCalls.get() + "\n"
                + "inter_service_calls_total{service=\"notification\"} " + notificationCalls.get() + "\n"
                + "# TYPE inter_service_p99_ms gauge\n"
                + "inter_service_p99_ms " + String.format(java.util.Locale.ROOT, "%.1f", p99) + "\n"
                + "# TYPE circuit_breaker_open_count counter\n"
                + "circuit_breaker_open_count " + circuitOpenCount + "\n"
                + "# TYPE jvm_available_processors gauge\n"
                + "jvm_available_processors " + Runtime.getRuntime().availableProcessors() + "\n";
        bytes(ex, 200, "text/plain; version=0.0.4", body);
    }

    // ── backwards-compat routes ────────────────────────────────────────────────

    private void compatEvents(HttpExchange ex) throws IOException {
        proxyRequests.incrementAndGet();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (!"POST".equals(ex.getRequestMethod())) {
            json(ex, 405, "{\"error\":\"method not allowed\"}");
            return;
        }
        String id = field(body, "id", "evt-" + ids.getAndIncrement());
        long enriched = deterministicWork(id + body, proxyRequests.get()) & 0xffff;
        String result = "{\"id\":\"" + escape(id) + "\",\"stage\":\"notified\",\"enrichment\":" + enriched + "}";
        documents.put("flow:" + id, result);
        counters.merge("events", 1L, Long::sum);
        json(ex, 200, result);
    }

    private void compatFlows(HttpExchange ex) throws IOException {
        proxyRequests.incrementAndGet();
        String path = ex.getRequestURI().getPath();
        String id = path.substring("/flows/".length());
        String result = documents.getOrDefault("flow:" + id,
                "{\"id\":\"" + escape(id) + "\",\"stage\":\"missing\"}");
        json(ex, 200, result);
    }

    private void compatNotifications(HttpExchange ex) throws IOException {
        proxyRequests.incrementAndGet();
        ex.getRequestBody().readAllBytes(); // consume body
        counters.merge("notifications", 1L, Long::sum);
        json(ex, 200, "{\"accepted\":true,\"fanout\":1}");
    }

    // ── inter-service call helpers ─────────────────────────────────────────────

    private String callAccount(String accountId) {
        if (accountCb.isOpen()) return null;
        accountCalls.incrementAndGet();
        long start = System.nanoTime();
        try {
            HttpRequest req = HttpRequest.newBuilder(
                    URI.create("http://localhost:" + accountPort + "/accounts/" + accountId))
                    .GET()
                    .timeout(Duration.ofSeconds(2))
                    .build();
            HttpResponse<String> resp = interServiceClient.send(req, HttpResponse.BodyHandlers.ofString());
            interServiceLatency.record(System.nanoTime() - start);
            if (resp.statusCode() == 200) {
                accountCb.recordSuccess();
                return resp.body();
            }
            accountCb.recordFailure();
            return null;
        } catch (Exception e) {
            interServiceLatency.record(System.nanoTime() - start);
            accountCb.recordFailure();
            return null;
        }
    }

    private String callTransaction(String txBody) {
        if (transactionCb.isOpen()) return null;
        transactionCalls.incrementAndGet();
        long start = System.nanoTime();
        try {
            HttpRequest req = HttpRequest.newBuilder(
                    URI.create("http://localhost:" + transactionPort + "/transactions"))
                    .POST(HttpRequest.BodyPublishers.ofString(txBody, StandardCharsets.UTF_8))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(2))
                    .build();
            HttpResponse<String> resp = interServiceClient.send(req, HttpResponse.BodyHandlers.ofString());
            interServiceLatency.record(System.nanoTime() - start);
            if (resp.statusCode() == 200) {
                transactionCb.recordSuccess();
                return resp.body();
            }
            transactionCb.recordFailure();
            return null;
        } catch (Exception e) {
            interServiceLatency.record(System.nanoTime() - start);
            transactionCb.recordFailure();
            return null;
        }
    }

    private boolean pingService(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(1))
                    .build();
            HttpResponse<String> resp = interServiceClient.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    // ── JSON / HTTP utilities ──────────────────────────────────────────────────

    private static void json(HttpExchange ex, int status, String body) throws IOException {
        bytes(ex, status, "application/json", body);
    }

    private static void bytes(HttpExchange ex, int status, String contentType, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, data.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(data); }
    }

    private static String field(String body, String name, String fallback) {
        String quoted = "\"" + name + "\"";
        int key = body.indexOf(quoted); if (key < 0) return fallback;
        int colon = body.indexOf(':', key + quoted.length()); if (colon < 0) return fallback;
        int firstQuote = body.indexOf('"', colon + 1); if (firstQuote < 0) return fallback;
        int secondQuote = body.indexOf('"', firstQuote + 1); if (secondQuote < 0) return fallback;
        return body.substring(firstQuote + 1, secondQuote);
    }

    private static long number(String body, String name, long fallback) {
        String quoted = "\"" + name + "\"";
        int key = body.indexOf(quoted); if (key < 0) return fallback;
        int colon = body.indexOf(':', key + quoted.length()); if (colon < 0) return fallback;
        int start = colon + 1;
        while (start < body.length() && Character.isWhitespace(body.charAt(start))) start++;
        int end = start;
        while (end < body.length() && (Character.isDigit(body.charAt(end)) || body.charAt(end) == '-')) end++;
        if (end == start) return fallback;
        try { return Long.parseLong(body.substring(start, end)); }
        catch (NumberFormatException e) { return fallback; }
    }

    private static long deterministicWork(String value, long salt) {
        long h = 1125899906842597L ^ salt;
        for (int i = 0; i < value.length(); i++) h = 31L * h + value.charAt(i);
        return Math.floorMod(h, 1_000_000_007L);
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
                    if (c < 0x20) out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }

    private void log(String event, String fields) {
        System.out.println("{\"event\":\"" + event + "\",\"benchmark\":\"" + benchmark + "\"," + fields + "}");
    }
}
