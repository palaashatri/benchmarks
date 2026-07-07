package com.palaashatri.bench.b03.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class MiniHttpServer {
    record StreamEvent(String key, double value, long timestamp) {}
    record WindowState(String key, long windowStart, long count, double sum, double min, double max) {
        double avg() { return count == 0 ? 0 : sum / count; }
    }

    private final String benchmark;
    private final LinkedBlockingQueue<StreamEvent> queue = new LinkedBlockingQueue<>(100_000);
    private final ConcurrentHashMap<String, WindowState> windows = new ConcurrentHashMap<>();
    private final AtomicLong eventsPublished = new AtomicLong();
    private final AtomicLong eventsConsumed = new AtomicLong();
    private final AtomicLong requestCount = new AtomicLong();

    public MiniHttpServer(String benchmark, String title) {
        this.benchmark = benchmark;
        startConsumers();
    }

    private void startConsumers() {
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        for (int i = 0; i < 2; i++) {
            executor.submit(this::consumerLoop);
        }
    }

    private void consumerLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                StreamEvent e = queue.poll(100, TimeUnit.MILLISECONDS);
                if (e == null) continue;
                long windowStart = (e.timestamp() / 10_000) * 10_000;
                String windowKey = e.key() + ":" + windowStart;
                windows.compute(windowKey, (k, w) -> {
                    if (w == null) return new WindowState(e.key(), windowStart, 1, e.value(), e.value(), e.value());
                    return new WindowState(e.key(), windowStart, w.count() + 1, w.sum() + e.value(),
                            Math.min(w.min(), e.value()), Math.max(w.max(), e.value()));
                });
                eventsConsumed.incrementAndGet();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 256);
        server.createContext("/health", this::health);
        server.createContext("/metrics", this::metrics);
        server.createContext("/api/v1/events", this::handleEvents);
        server.createContext("/api/v1/windows", this::handleWindows);
        server.createContext("/api/v1/lag", this::handleLag);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("{\"event\":\"started\",\"benchmark\":\"" + benchmark + "\",\"port\":" + port + "}");
    }

    private void health(HttpExchange ex) throws IOException {
        requestCount.incrementAndGet();
        json(ex, 200, "{\"status\":\"UP\",\"queue_depth\":" + queue.size() + ",\"windows\":" + windows.size() + "}");
    }

    private void metrics(HttpExchange ex) throws IOException {
        requestCount.incrementAndGet();
        long pub = eventsPublished.get();
        long con = eventsConsumed.get();
        String body = "streaming_events_published_total " + pub + "\n"
                + "streaming_events_consumed_total " + con + "\n"
                + "streaming_consumer_lag " + (pub - con) + "\n"
                + "streaming_windows_active " + windows.size() + "\n"
                + "jvm_available_processors " + Runtime.getRuntime().availableProcessors() + "\n"
                + "benchmark_requests_total{benchmark=\"" + benchmark + "\"} " + requestCount.get() + "\n";
        bytes(ex, 200, "text/plain; version=0.0.4", body);
    }

    private void handleEvents(HttpExchange ex) throws IOException {
        requestCount.incrementAndGet();
        String method = ex.getRequestMethod();
        if (!"POST".equals(method)) { json(ex, 405, "{\"error\":\"method not allowed\"}"); return; }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String key = field(body, "key", "sensor-default");
        double value = numberDouble(body, "value", 0.0);
        long ts = System.currentTimeMillis();
        StreamEvent event = new StreamEvent(key, value, ts);
        boolean accepted = queue.offer(event);
        if (accepted) eventsPublished.incrementAndGet();
        json(ex, 200, "{\"accepted\":" + accepted + ",\"queue_depth\":" + queue.size() + "}");
    }

    private void handleWindows(HttpExchange ex) throws IOException {
        requestCount.incrementAndGet();
        String path = ex.getRequestURI().getPath();
        // /api/v1/windows/{key} or /api/v1/windows
        String prefix = "/api/v1/windows";
        if (path.length() > prefix.length() + 1) {
            String key = urlDecode(path.substring(prefix.length() + 1));
            // Find latest window for this key
            WindowState latest = null;
            for (var entry : windows.entrySet()) {
                if (entry.getValue().key().equals(key)) {
                    if (latest == null || entry.getValue().windowStart() > latest.windowStart()) {
                        latest = entry.getValue();
                    }
                }
            }
            if (latest == null) {
                json(ex, 200, "{\"key\":\"" + escape(key) + "\",\"count\":0,\"sum\":0,\"avg\":0,\"min\":0,\"max\":0,\"window_start\":0}");
            } else {
                WindowState w = latest;
                json(ex, 200, "{\"key\":\"" + escape(w.key()) + "\",\"count\":" + w.count()
                        + ",\"sum\":" + fmt(w.sum()) + ",\"avg\":" + fmt(w.avg())
                        + ",\"min\":" + fmt(w.min()) + ",\"max\":" + fmt(w.max())
                        + ",\"window_start\":" + w.windowStart() + "}");
            }
        } else {
            // Return all windows (up to 100)
            List<WindowState> all = new ArrayList<>(windows.values());
            int limit = Math.min(100, all.size());
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < limit; i++) {
                if (i > 0) sb.append(',');
                WindowState w = all.get(i);
                sb.append("{\"key\":\"").append(escape(w.key())).append("\",\"count\":").append(w.count())
                        .append(",\"sum\":").append(fmt(w.sum())).append(",\"avg\":").append(fmt(w.avg()))
                        .append(",\"min\":").append(fmt(w.min())).append(",\"max\":").append(fmt(w.max()))
                        .append(",\"window_start\":").append(w.windowStart()).append('}');
            }
            sb.append(']');
            json(ex, 200, sb.toString());
        }
    }

    private void handleLag(HttpExchange ex) throws IOException {
        requestCount.incrementAndGet();
        long pub = eventsPublished.get();
        long con = eventsConsumed.get();
        json(ex, 200, "{\"queue_depth\":" + queue.size()
                + ",\"events_published\":" + pub
                + ",\"events_consumed\":" + con
                + ",\"consumer_lag\":" + (pub - con) + "}");
    }

    private static String field(String body, String name, String fallback) {
        String quoted = "\"" + name + "\"";
        int key = body.indexOf(quoted); if (key < 0) return fallback;
        int colon = body.indexOf(':', key + quoted.length()); if (colon < 0) return fallback;
        int firstQuote = body.indexOf('"', colon + 1); if (firstQuote < 0) return fallback;
        int secondQuote = body.indexOf('"', firstQuote + 1); if (secondQuote < 0) return fallback;
        return body.substring(firstQuote + 1, secondQuote);
    }

    private static double numberDouble(String body, String name, double fallback) {
        String quoted = "\"" + name + "\"";
        int key = body.indexOf(quoted); if (key < 0) return fallback;
        int colon = body.indexOf(':', key + quoted.length()); if (colon < 0) return fallback;
        int start = colon + 1; while (start < body.length() && Character.isWhitespace(body.charAt(start))) start++;
        int end = start; while (end < body.length() && (Character.isDigit(body.charAt(end)) || body.charAt(end) == '-' || body.charAt(end) == '.')) end++;
        if (end == start) return fallback;
        try { return Double.parseDouble(body.substring(start, end)); } catch (NumberFormatException e) { return fallback; }
    }

    private static String fmt(double v) { return String.format(java.util.Locale.ROOT, "%.6f", v); }
    private static String urlDecode(String raw) { return URLDecoder.decode(raw, StandardCharsets.UTF_8); }
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
                default -> { if (c < 0x20) out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c)); else out.append(c); }
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
