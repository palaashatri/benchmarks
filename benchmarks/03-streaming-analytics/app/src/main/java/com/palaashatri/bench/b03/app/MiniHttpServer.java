package com.palaashatri.bench.b03.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Deterministic in-memory event-time windowing prototype. */
public final class MiniHttpServer {
    private static final long WINDOW_MS = 10_000L;
    private static final long RETENTION_MS = 10 * 60_000L;
    private static final int MAX_WINDOWS = 10_000;

    record StreamEvent(String key, double value, long timestampMs) { }

    record WindowState(
            String key,
            long windowStartMs,
            long count,
            double sum,
            double minimum,
            double maximum) {
        double average() {
            return count == 0 ? 0 : sum / count;
        }

        WindowState add(double value) {
            return new WindowState(
                    key,
                    windowStartMs,
                    count + 1,
                    sum + value,
                    Math.min(minimum, value),
                    Math.max(maximum, value));
        }
    }

    private final String benchmark;
    private final LinkedBlockingQueue<StreamEvent> queue = new LinkedBlockingQueue<>(100_000);
    private final ConcurrentHashMap<String, WindowState> windows = new ConcurrentHashMap<>();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong consumed = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong cleanupRuns = new AtomicLong();

    public MiniHttpServer(String benchmark, String ignoredTitle) {
        this.benchmark = benchmark;
        for (int worker = 0; worker < 2; worker++) {
            Thread.ofVirtual().name("window-consumer-" + worker).start(this::consume);
        }
    }

    public void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 256);
        server.createContext("/health", this::health);
        server.createContext("/runtime", this::runtime);
        server.createContext("/metrics", this::metrics);
        server.createContext("/api/v1/events", this::events);
        server.createContext("/api/v1/windows", this::window);
        server.createContext("/api/v1/lag", this::lag);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.printf(
                "{\"event\":\"started\",\"benchmark\":\"%s\","
                        + "\"state_backend\":\"in-memory\",\"external_broker\":false,"
                        + "\"port\":%d,\"pid\":%d}%n",
                benchmark,
                port,
                ProcessHandle.current().pid());
    }

    private void consume() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                StreamEvent event = queue.poll(100, TimeUnit.MILLISECONDS);
                if (event == null) continue;
                long start = Math.floorDiv(event.timestampMs(), WINDOW_MS) * WINDOW_MS;
                String id = windowId(event.key(), start);
                windows.compute(id, (ignored, current) -> current == null
                        ? new WindowState(
                                event.key(), start, 1, event.value(), event.value(), event.value())
                        : current.add(event.value()));
                long total = consumed.incrementAndGet();
                if ((total & 255) == 0 || windows.size() > MAX_WINDOWS) {
                    cleanup(event.timestampMs());
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void cleanup(long newestTimestampMs) {
        long cutoff = newestTimestampMs - RETENTION_MS;
        windows.entrySet().removeIf(entry -> entry.getValue().windowStartMs() < cutoff);
        if (windows.size() > MAX_WINDOWS) {
            List<Map.Entry<String, WindowState>> oldest = new ArrayList<>(windows.entrySet());
            oldest.sort(Comparator.comparingLong(entry -> entry.getValue().windowStartMs()));
            int remove = windows.size() - MAX_WINDOWS;
            for (int index = 0; index < remove; index++) {
                windows.remove(oldest.get(index).getKey(), oldest.get(index).getValue());
            }
        }
        cleanupRuns.incrementAndGet();
    }

    private void health(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        json(exchange, 200,
                "{\"status\":\"UP\",\"state_backend\":\"in-memory\","
                        + "\"external_broker\":false,\"queue_depth\":" + queue.size()
                        + ",\"active_windows\":" + windows.size() + "}");
    }

    private void runtime(HttpExchange exchange) throws IOException {
        json(exchange, 200,
                "{\"pid\":" + ProcessHandle.current().pid()
                        + ",\"run_token\":\""
                        + escape(System.getenv().getOrDefault("BENCH_RUN_TOKEN", ""))
                        + "\",\"java_version\":\""
                        + escape(System.getProperty("java.version")) + "\"}");
    }

    private void events(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            json(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        String body = new String(
                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String key = stringField(body, "key", "");
        double value = doubleField(body, "value", Double.NaN);
        long timestamp = longField(body, "timestamp_ms", System.currentTimeMillis());
        if (key.isBlank() || !Double.isFinite(value) || timestamp < 0) {
            rejected.incrementAndGet();
            json(exchange, 400, "{\"accepted\":false,\"reason\":\"invalid_event\"}");
            return;
        }
        boolean enqueued = queue.offer(new StreamEvent(key, value, timestamp));
        if (enqueued) {
            accepted.incrementAndGet();
            json(exchange, 202,
                    "{\"accepted\":true,\"event_time_ms\":" + timestamp
                            + ",\"queue_depth\":" + queue.size() + "}");
        } else {
            rejected.incrementAndGet();
            json(exchange, 429, "{\"accepted\":false,\"reason\":\"queue_full\"}");
        }
    }

    private void window(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            json(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        String path = exchange.getRequestURI().getPath();
        String prefix = "/api/v1/windows/";
        if (!path.startsWith(prefix) || path.length() == prefix.length()) {
            json(exchange, 400, "{\"error\":\"key_required\"}");
            return;
        }
        String key = URLDecoder.decode(path.substring(prefix.length()), StandardCharsets.UTF_8);
        WindowState latest = windows.values().stream()
                .filter(candidate -> candidate.key().equals(key))
                .max(Comparator.comparingLong(WindowState::windowStartMs))
                .orElse(null);
        if (latest == null) {
            json(exchange, 404,
                    "{\"error\":\"window_not_found\",\"key\":\"" + escape(key) + "\"}");
            return;
        }
        json(exchange, 200, windowJson(latest));
    }

    private void lag(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        long acceptedValue = accepted.get();
        long consumedValue = consumed.get();
        json(exchange, 200,
                "{\"accepted\":" + acceptedValue
                        + ",\"consumed\":" + consumedValue
                        + ",\"lag\":" + Math.max(0, acceptedValue - consumedValue)
                        + ",\"queue_depth\":" + queue.size() + "}");
    }

    private void metrics(HttpExchange exchange) throws IOException {
        String body = "# TYPE streaming_events_accepted_total counter\n"
                + "streaming_events_accepted_total " + accepted.get() + "\n"
                + "# TYPE streaming_events_consumed_total counter\n"
                + "streaming_events_consumed_total " + consumed.get() + "\n"
                + "# TYPE streaming_events_rejected_total counter\n"
                + "streaming_events_rejected_total " + rejected.get() + "\n"
                + "# TYPE streaming_consumer_lag gauge\n"
                + "streaming_consumer_lag " + Math.max(0, accepted.get() - consumed.get()) + "\n"
                + "# TYPE streaming_active_windows gauge\n"
                + "streaming_active_windows " + windows.size() + "\n"
                + "# TYPE streaming_cleanup_runs_total counter\n"
                + "streaming_cleanup_runs_total " + cleanupRuns.get() + "\n"
                + "# TYPE streaming_external_broker gauge\n"
                + "streaming_external_broker 0\n"
                + "# TYPE benchmark_requests_total counter\n"
                + "benchmark_requests_total{benchmark=\"" + benchmark + "\"} "
                + requests.get() + "\n";
        bytes(exchange, 200, "text/plain; version=0.0.4", body);
    }

    private static String windowId(String key, long start) {
        return key + '\u0000' + start;
    }

    private static String windowJson(WindowState window) {
        return "{\"key\":\"" + escape(window.key())
                + "\",\"window_start_ms\":" + window.windowStartMs()
                + ",\"window_size_ms\":" + WINDOW_MS
                + ",\"count\":" + window.count()
                + ",\"sum\":" + format(window.sum())
                + ",\"average\":" + format(window.average())
                + ",\"minimum\":" + format(window.minimum())
                + ",\"maximum\":" + format(window.maximum()) + "}";
    }

    private static String stringField(String body, String name, String fallback) {
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

    private static long longField(String body, String name, long fallback) {
        double value = doubleField(body, name, fallback);
        return Double.isFinite(value) && value >= Long.MIN_VALUE && value <= Long.MAX_VALUE
                ? (long) value
                : fallback;
    }

    private static double doubleField(String body, String name, double fallback) {
        String key = "\"" + name + "\"";
        int at = body.indexOf(key);
        if (at < 0) return fallback;
        int colon = body.indexOf(':', at + key.length());
        if (colon < 0) return fallback;
        int start = colon + 1;
        while (start < body.length() && Character.isWhitespace(body.charAt(start))) start++;
        int end = start;
        while (end < body.length()) {
            char character = body.charAt(end);
            if (!(Character.isDigit(character)
                    || character == '-'
                    || character == '+'
                    || character == '.'
                    || character == 'e'
                    || character == 'E')) {
                break;
            }
            end++;
        }
        try {
            return Double.parseDouble(body.substring(start, end));
        } catch (RuntimeException ignored) {
            return fallback;
        }
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
