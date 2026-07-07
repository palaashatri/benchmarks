package com.palaashatri.bench.b11.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class MiniHttpServer {

    static class TokenBucket {
        private final long capacity;
        private final long refillPerSecond;
        private volatile long tokens;
        private volatile long lastRefill = System.nanoTime();

        TokenBucket(long capacity, long refillPerSecond) {
            this.capacity = capacity;
            this.refillPerSecond = refillPerSecond;
            this.tokens = capacity;
        }

        synchronized boolean tryConsume() {
            refill();
            if (tokens > 0) { tokens--; return true; }
            return false;
        }

        private void refill() {
            long now = System.nanoTime();
            long elapsed = now - lastRefill;
            long toAdd = elapsed * refillPerSecond / 1_000_000_000L;
            if (toAdd > 0) { tokens = Math.min(capacity, tokens + toAdd); lastRefill = now; }
        }

        long getTokens() { return tokens; }
    }

    private final String benchmark;
    private final TokenBucket tokenBucket = new TokenBucket(100, 500);
    private final ThreadPoolExecutor workPool = new ThreadPoolExecutor(
            2, 2, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(1000));
    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong rejectedRequests = new AtomicLong();
    private final AtomicLong scaleUpCount = new AtomicLong();
    private final AtomicLong requestCount = new AtomicLong();

    public MiniHttpServer(String benchmark, String title) {
        this.benchmark = benchmark;
        startMonitor();
    }

    private void startMonitor() {
        Thread monitor = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(500);
                    int queueDepth = workPool.getQueue().size();
                    if (queueDepth > 10 && workPool.getCorePoolSize() < 32) {
                        int newSize = Math.min(32, workPool.getCorePoolSize() + 4);
                        workPool.setCorePoolSize(newSize);
                        workPool.setMaximumPoolSize(newSize);
                        scaleUpCount.incrementAndGet();
                    }
                    if (queueDepth < 2 && workPool.getCorePoolSize() > 2) {
                        int newSize = Math.max(2, workPool.getCorePoolSize() - 2);
                        workPool.setMaximumPoolSize(newSize);
                        workPool.setCorePoolSize(newSize);
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
        monitor.setDaemon(true);
        monitor.start();
    }

    public void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 256);
        server.createContext("/health", this::health);
        server.createContext("/metrics", this::metrics);
        server.createContext("/api/v1/catalog/search", this::handleSearch);
        server.createContext("/api/v1/catalog/health", this::handleCatalogHealth);
        server.createContext("/api/v1/metrics/scaling", this::handleScaling);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("{\"event\":\"started\",\"benchmark\":\"" + benchmark + "\",\"port\":" + port + "}");
    }

    private void health(HttpExchange ex) throws IOException {
        requestCount.incrementAndGet();
        json(ex, 200, "{\"status\":\"UP\"}");
    }

    private void metrics(HttpExchange ex) throws IOException {
        requestCount.incrementAndGet();
        long total = totalRequests.get();
        long rejected = rejectedRequests.get();
        String body = "catalog_requests_total " + total + "\n"
                + "catalog_rejected_total " + rejected + "\n"
                + "catalog_pool_size " + workPool.getCorePoolSize() + "\n"
                + "catalog_queue_depth " + workPool.getQueue().size() + "\n"
                + "catalog_scale_up_total " + scaleUpCount.get() + "\n"
                + "benchmark_requests_total{benchmark=\"" + benchmark + "\"} " + requestCount.get() + "\n";
        bytes(ex, 200, "text/plain; version=0.0.4", body);
    }

    private void handleSearch(HttpExchange ex) throws IOException {
        requestCount.incrementAndGet();
        if (!"POST".equals(ex.getRequestMethod())) { json(ex, 405, "{\"error\":\"method not allowed\"}"); return; }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String query = field(body, "query", "unknown");
        String category = field(body, "category", "general");
        totalRequests.incrementAndGet();
        if (!tokenBucket.tryConsume()) {
            rejectedRequests.incrementAndGet();
            json(ex, 429, "{\"error\":\"rate limit exceeded\",\"query\":\"" + escape(query) + "\"}");
            return;
        }
        try {
            workPool.submit(() -> {
                try { Thread.sleep(2); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            });
        } catch (java.util.concurrent.RejectedExecutionException ree) {
            rejectedRequests.incrementAndGet();
            json(ex, 429, "{\"error\":\"pool queue full\",\"query\":\"" + escape(query) + "\"}");
            return;
        }
        json(ex, 200, "{\"query\":\"" + escape(query) + "\",\"category\":\"" + escape(category) + "\",\"results\":[],\"total\":0}");
    }

    private void handleCatalogHealth(HttpExchange ex) throws IOException {
        requestCount.incrementAndGet();
        json(ex, 200, "{\"status\":\"UP\",\"pool_size\":" + workPool.getCorePoolSize()
                + ",\"queue_depth\":" + workPool.getQueue().size()
                + ",\"rate_limit\":500}");
    }

    private void handleScaling(HttpExchange ex) throws IOException {
        requestCount.incrementAndGet();
        json(ex, 200, "{\"core_pool_size\":" + workPool.getCorePoolSize()
                + ",\"queue_depth\":" + workPool.getQueue().size()
                + ",\"scale_up_count\":" + scaleUpCount.get()
                + ",\"reject_count\":" + rejectedRequests.get()
                + ",\"token_bucket_tokens\":" + tokenBucket.getTokens() + "}");
    }

    private static String field(String body, String name, String fallback) {
        String quoted = "\"" + name + "\"";
        int key = body.indexOf(quoted); if (key < 0) return fallback;
        int colon = body.indexOf(':', key + quoted.length()); if (colon < 0) return fallback;
        int firstQuote = body.indexOf('"', colon + 1); if (firstQuote < 0) return fallback;
        int secondQuote = body.indexOf('"', firstQuote + 1); if (secondQuote < 0) return fallback;
        return body.substring(firstQuote + 1, secondQuote);
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new LinkedHashMap<>(); if (raw == null || raw.isBlank()) return out;
        for (String part : raw.split("&")) { int eq = part.indexOf('='); if (eq > 0) out.put(part.substring(0, eq), part.substring(eq + 1)); }
        return out;
    }

    private static String escape(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default: if (c < 0x20) out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c)); else out.append(c);
            }
        }
        return out.toString();
    }
    private static void json(HttpExchange ex, int status, String body) throws IOException { bytes(ex, status, "application/json", body); }
    private static void bytes(HttpExchange ex, int status, String contentType, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, data.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(data); }
    }
}
