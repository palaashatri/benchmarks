package com.palaashatri.bench.b11.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single-JVM capacity-scaling simulator.
 *
 * This workload changes a local worker pool only. It never claims to create
 * processes, containers, pods, or external replicas.
 */
public final class MiniHttpServer {
    static final class TokenBucket {
        private final long capacity;
        private final long refillPerSecond;
        private long tokens;
        private long lastRefillNs = System.nanoTime();

        TokenBucket(long capacity, long refillPerSecond) {
            this.capacity = capacity;
            this.refillPerSecond = refillPerSecond;
            this.tokens = capacity;
        }

        synchronized boolean tryConsume() {
            refill();
            if (tokens == 0) return false;
            tokens--;
            return true;
        }

        synchronized long tokens() {
            refill();
            return tokens;
        }

        private void refill() {
            long now = System.nanoTime();
            long elapsed = now - lastRefillNs;
            long added = elapsed * refillPerSecond / 1_000_000_000L;
            if (added > 0) {
                tokens = Math.min(capacity, tokens + added);
                lastRefillNs = now;
            }
        }
    }

    private final String benchmark;
    private final TokenBucket tokenBucket = new TokenBucket(200, 1_000);
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(
            2,
            2,
            60,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1_000),
            new ThreadPoolExecutor.AbortPolicy());
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong scaleUps = new AtomicLong();
    private final AtomicLong scaleDowns = new AtomicLong();

    public MiniHttpServer(String benchmark, String ignoredTitle) {
        this.benchmark = benchmark;
        startMonitor();
    }

    private void startMonitor() {
        Thread monitor = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(100);
                    int queueDepth = workers.getQueue().size();
                    int current = workers.getCorePoolSize();
                    if (queueDepth > 10 && current < 32) {
                        resize(Math.min(32, current + 4));
                        scaleUps.incrementAndGet();
                    } else if (queueDepth == 0 && workers.getActiveCount() <= 1 && current > 2) {
                        resize(Math.max(2, current - 2));
                        scaleDowns.incrementAndGet();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "local-capacity-monitor");
        monitor.setDaemon(true);
        monitor.start();
    }

    private void resize(int target) {
        synchronized (workers) {
            int currentMaximum = workers.getMaximumPoolSize();
            if (target > currentMaximum) {
                workers.setMaximumPoolSize(target);
                workers.setCorePoolSize(target);
            } else {
                workers.setCorePoolSize(target);
                workers.setMaximumPoolSize(target);
            }
        }
    }

    public void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 256);
        server.createContext("/health", this::health);
        server.createContext("/runtime", this::runtime);
        server.createContext("/metrics", this::metrics);
        server.createContext("/api/v1/catalog/search", this::search);
        server.createContext("/api/v1/catalog/health", this::health);
        server.createContext("/api/v1/metrics/scaling", this::scaling);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.printf(
                "{\"event\":\"started\",\"benchmark\":\"%s\","
                        + "\"scaling_model\":\"single-jvm-thread-pool\","
                        + "\"port\":%d,\"pid\":%d}%n",
                benchmark,
                port,
                ProcessHandle.current().pid());
    }

    private void health(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        json(exchange, 200,
                "{\"status\":\"UP\",\"scaling_model\":\"single-jvm-thread-pool\","
                        + "\"external_replicas\":0,\"worker_capacity\":"
                        + workers.getCorePoolSize() + "}");
    }

    private void runtime(HttpExchange exchange) throws IOException {
        json(exchange, 200,
                "{\"pid\":" + ProcessHandle.current().pid()
                        + ",\"run_token\":\""
                        + escape(System.getenv().getOrDefault("BENCH_RUN_TOKEN", ""))
                        + "\",\"java_version\":\""
                        + escape(System.getProperty("java.version")) + "\"}");
    }

    private void search(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            json(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        String body = new String(
                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String query = field(body, "query", "unknown");
        long workMs = Math.max(1, Math.min(500, number(body, "work_ms", 25)));
        if (!tokenBucket.tryConsume()) {
            rejected.incrementAndGet();
            json(exchange, 429,
                    "{\"accepted\":false,\"reason\":\"rate_limited\","
                            + "\"query\":\"" + escape(query) + "\"}");
            return;
        }
        try {
            workers.execute(() -> {
                try {
                    Thread.sleep(workMs);
                    completed.incrementAndGet();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            accepted.incrementAndGet();
            json(exchange, 202,
                    "{\"accepted\":true,\"completed\":false,"
                            + "\"execution_model\":\"queued-local-work\","
                            + "\"query\":\"" + escape(query) + "\"}");
        } catch (RejectedExecutionException full) {
            rejected.incrementAndGet();
            json(exchange, 429,
                    "{\"accepted\":false,\"reason\":\"queue_full\","
                            + "\"query\":\"" + escape(query) + "\"}");
        }
    }

    private void scaling(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        json(exchange, 200,
                "{\"scaling_model\":\"single-jvm-thread-pool\","
                        + "\"external_replicas\":0,"
                        + "\"worker_capacity\":" + workers.getCorePoolSize()
                        + ",\"active_workers\":" + workers.getActiveCount()
                        + ",\"queue_depth\":" + workers.getQueue().size()
                        + ",\"scale_up_count\":" + scaleUps.get()
                        + ",\"scale_down_count\":" + scaleDowns.get()
                        + ",\"accepted\":" + accepted.get()
                        + ",\"completed\":" + completed.get()
                        + ",\"rejected\":" + rejected.get()
                        + ",\"token_bucket_tokens\":" + tokenBucket.tokens() + "}");
    }

    private void metrics(HttpExchange exchange) throws IOException {
        String body = "# TYPE capacity_requests_accepted_total counter\n"
                + "capacity_requests_accepted_total " + accepted.get() + "\n"
                + "# TYPE capacity_requests_completed_total counter\n"
                + "capacity_requests_completed_total " + completed.get() + "\n"
                + "# TYPE capacity_requests_rejected_total counter\n"
                + "capacity_requests_rejected_total " + rejected.get() + "\n"
                + "# TYPE capacity_worker_threads gauge\n"
                + "capacity_worker_threads " + workers.getCorePoolSize() + "\n"
                + "# TYPE capacity_queue_depth gauge\n"
                + "capacity_queue_depth " + workers.getQueue().size() + "\n"
                + "# TYPE capacity_external_replicas gauge\n"
                + "capacity_external_replicas 0\n"
                + "# TYPE benchmark_requests_total counter\n"
                + "benchmark_requests_total{benchmark=\"" + benchmark + "\"} "
                + requests.get() + "\n";
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

    private static long number(String body, String name, long fallback) {
        String key = "\"" + name + "\"";
        int at = body.indexOf(key);
        if (at < 0) return fallback;
        int colon = body.indexOf(':', at + key.length());
        if (colon < 0) return fallback;
        int start = colon + 1;
        while (start < body.length() && Character.isWhitespace(body.charAt(start))) start++;
        int end = start;
        while (end < body.length()
                && (Character.isDigit(body.charAt(end)) || body.charAt(end) == '-')) end++;
        try {
            return Long.parseLong(body.substring(start, end));
        } catch (RuntimeException ignored) {
            return fallback;
        }
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
