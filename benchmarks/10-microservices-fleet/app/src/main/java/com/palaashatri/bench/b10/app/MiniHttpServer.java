package com.palaashatri.bench.b10.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public final class MiniHttpServer {
    private final String benchmark;
    private final String title;
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong ids = new AtomicLong(1);
    private final Map<String, Long> balances = new ConcurrentHashMap<>();
    private final Map<String, ArrayDeque<String>> histories = new ConcurrentHashMap<>();
    private final Map<String, Long> counters = new ConcurrentHashMap<>();
    private final Map<String, List<String>> lists = new ConcurrentHashMap<>();
    private final Map<String, String> documents = new ConcurrentHashMap<>();

    public MiniHttpServer(String benchmark, String title) {
        this.benchmark = benchmark;
        this.title = title;
        seedState();
    }

    public void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 256);
        server.createContext("/health", this::health);
        server.createContext("/metrics", this::metrics);
        server.createContext("/actuator/health", this::health);
        server.createContext("/actuator/prometheus", this::metrics);
        server.createContext("/", this::route);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        log("started", "\"port\":" + port);
    }

    private void seedState() {
        for (int i = 1; i <= 2000; i++) { balances.put("" + i, 1_000_000L + i * 17L); }
        balances.put("1001", 1_500_000L);
        balances.put("1002", 1_250_000L);
        documents.put("order-1", "{\"orderId\":\"order-1\",\"status\":\"SEEDED\"}");
    }

    private void health(HttpExchange ex) throws IOException { json(ex, 200, "{\"status\":\"UP\",\"benchmark\":\"" + benchmark + "\",\"service\":\"" + escape(title) + "\"}"); }

    private void metrics(HttpExchange ex) throws IOException {
        String body = "# TYPE benchmark_requests_total counter\n"
                + "benchmark_requests_total{benchmark=\"" + benchmark + "\"} " + requests.get() + "\n"
                + "# TYPE benchmark_domain_events_total counter\n"
                + "benchmark_domain_events_total{benchmark=\"" + benchmark + "\"} " + counters.values().stream().mapToLong(Long::longValue).sum() + "\n"
                + "# TYPE jvm_available_processors gauge\n"
                + "jvm_available_processors " + Runtime.getRuntime().availableProcessors() + "\n";
        bytes(ex, 200, "text/plain; version=0.0.4", body);
    }

    private void route(HttpExchange ex) throws IOException {
        long n = requests.incrementAndGet();
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String query = ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        try { json(ex, 200, handle(method, path, query, body, n)); }
        catch (IllegalArgumentException e) { json(ex, 404, "{\"error\":\"" + escape(e.getMessage()) + "\",\"benchmark\":\"" + benchmark + "\"}"); }
    }

    private String handle(String method, String path, String query, String body, long requestId) {
        if (path.startsWith("/api/v1/catalog/")) {
            String product = path.substring("/api/v1/catalog/".length());
            long stock = 10 + Math.floorMod(product.hashCode(), 500);
            long price = 999 + Math.floorMod(product.hashCode(), 20_000);
            return "{\"productId\":\"" + escape(product) + "\",\"available\":" + stock + ",\"priceCents\":" + price + ",\"pricingRules\":8}";
        }
        if (method.equals("POST") && path.equals("/api/v1/orders")) {
            String id = "order-" + ids.getAndIncrement();
            String doc = "{\"orderId\":\"" + id + "\",\"status\":\"ACCEPTED\",\"auditPublished\":true}";
            documents.put(id, doc);
            return doc;
        }
        if (path.startsWith("/api/v1/orders/")) {
            String id = path.substring("/api/v1/orders/".length());
            return documents.getOrDefault(id, "{\"orderId\":\"" + escape(id) + "\",\"status\":\"UNKNOWN\"}");
        }
        throw notFound(path);
    }

    private String windowJson(String key) {
        long count = counters.getOrDefault("stream_count:" + key, 0L);
        long sum = counters.getOrDefault("stream:" + key, 0L);
        return "{\"key\":\"" + escape(key) + "\",\"window\":\"rolling-60s\",\"count\":" + count + ",\"sum\":" + sum + "}";
    }

    private void append(String key, String value) { lists.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value); histories.computeIfAbsent(key, ignored -> new ArrayDeque<>()).add(value); }
    private IllegalArgumentException notFound(String path) { return new IllegalArgumentException("no route for " + path); }

    private static double[] features(String body, long requestId) {
        double[] out = new double[8];
        for (int i = 0; i < out.length; i++) { out[i] = ((body.hashCode() + requestId * (i + 3)) & 0xff) / 255.0D; }
        return out;
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
        int start = colon + 1; while (start < body.length() && Character.isWhitespace(body.charAt(start))) start++;
        int end = start; while (end < body.length() && (Character.isDigit(body.charAt(end)) || body.charAt(end) == '-')) end++;
        if (end == start) return fallback;
        try { return Long.parseLong(body.substring(start, end)); } catch (NumberFormatException e) { return fallback; }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new LinkedHashMap<>(); if (raw == null || raw.isBlank()) return out;
        for (String part : raw.split("&")) { int eq = part.indexOf('='); if (eq > 0) out.put(urlDecode(part.substring(0, eq)), urlDecode(part.substring(eq + 1))); }
        return out;
    }

    private static long deterministicWork(String value, long salt) { long h = 1125899906842597L ^ salt; for (int i = 0; i < value.length(); i++) h = 31L * h + value.charAt(i); return Math.floorMod(h, 1_000_000_007L); }
    private static String listJson(Iterable<String> values) { if (values == null) return "[]"; StringBuilder out = new StringBuilder("["); boolean first = true; for (String value : values) { if (!first) out.append(','); out.append('"').append(escape(value)).append('"'); first = false; } return out.append(']').toString(); }
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
    private static void json(HttpExchange ex, int status, String body) throws IOException { bytes(ex, status, "application/json", body); }
    private static void bytes(HttpExchange ex, int status, String contentType, String body) throws IOException { byte[] data = body.getBytes(StandardCharsets.UTF_8); ex.getResponseHeaders().set("Content-Type", contentType); ex.sendResponseHeaders(status, data.length); try (OutputStream out = ex.getResponseBody()) { out.write(data); } }
    private void log(String event, String fields) { System.out.println("{\"event\":\"" + event + "\",\"benchmark\":\"" + benchmark + "\"," + fields + "}"); }
}
