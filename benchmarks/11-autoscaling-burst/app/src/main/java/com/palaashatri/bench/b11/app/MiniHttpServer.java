package com.palaashatri.bench.b11.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
        for (int i = 1; i <= 2000; i++) {
            balances.put("" + i, 1_000_000L + i * 17L);
        }
        balances.put("1001", 1_500_000L);
        balances.put("1002", 1_250_000L);
        documents.put("order-1", "{\"orderId\":\"order-1\",\"status\":\"SEEDED\"}");
    }

    private void health(HttpExchange ex) throws IOException {
        json(ex, 200, "{\"status\":\"UP\",\"benchmark\":\"" + benchmark + "\",\"service\":\"" + escape(title) + "\"}");
    }

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
        try {
            json(ex, 200, dispatch(method, path, query, body, n));
        } catch (IllegalArgumentException e) {
            json(ex, 404, "{\"error\":\"" + escape(e.getMessage()) + "\",\"benchmark\":\"" + benchmark + "\"}");
        }
    }

    private String dispatch(String method, String path, String query, String body, long requestId) {
        return switch (benchmark) {
            case "01-fintech-ledger" -> ledger(method, path, body, requestId);
            case "02-microservices-mesh" -> mesh(method, path, body, requestId);
            case "03-streaming-analytics" -> streaming(method, path, body, requestId);
            case "04-ml-inference-panama-vector" -> inference(method, path, body, requestId);
            case "05-polyglot-service" -> rules(method, path, body, requestId);
            case "06-massive-chat-loom" -> chat(method, path, body, requestId);
            case "07-coldstart-suite" -> functions(method, path, body, requestId);
            case "08-etl-batch" -> etl(method, path, body, requestId);
            case "09-onnx-inference" -> onnx(method, path, body, requestId);
            case "10-microservices-fleet" -> fleet(method, path, body, requestId);
            case "11-autoscaling-burst" -> autoscale(method, path, query, requestId);
            case "12-hft-trading-gateway" -> trading(method, path, body, requestId);
            case "13-large-monolith" -> monolith(method, path, body, requestId);
            default -> generic(path, requestId);
        };
    }

    private String ledger(String method, String path, String body, long requestId) {
        if (method.equals("POST") && path.equals("/transfers")) {
            String from = field(body, "from", "1001");
            String to = field(body, "to", "1002");
            long amount = number(body, "amount_cents", 125L + requestId);
            long fromBalance = balances.getOrDefault(from, 1_000_000L);
            boolean approved = amount > 0 && amount < 500_000L && fromBalance >= amount && fraudScore(from, to, amount) < 900;
            String id = "txn-" + ids.getAndIncrement();
            if (approved) {
                balances.put(from, fromBalance - amount);
                balances.merge(to, amount, Long::sum);
                append("ledger:" + from, id + ":-" + amount);
                append("ledger:" + to, id + ":" + amount);
            }
            counters.merge("transfers", 1L, Long::sum);
            return "{\"transfer_id\":\"" + id + "\",\"approved\":" + approved + ",\"from_balance_cents\":" + balances.getOrDefault(from, 0L) + ",\"fraud_score\":" + fraudScore(from, to, amount) + "}";
        }
        if (path.matches("/accounts/[^/]+/balance")) {
            String account = path.split("/")[2];
            return "{\"account_id\":\"" + escape(account) + "\",\"balance_cents\":" + balances.getOrDefault(account, 0L) + ",\"version\":" + requestId + "}";
        }
        if (path.matches("/accounts/[^/]+/transactions")) {
            String account = path.split("/")[2];
            return "{\"account_id\":\"" + escape(account) + "\",\"entries\":" + listJson(histories.get("ledger:" + account)) + "}";
        }
        return generic(path, requestId);
    }

    private String mesh(String method, String path, String body, long requestId) {
        if (method.equals("POST") && path.equals("/events")) {
            String id = field(body, "id", "evt-" + ids.getAndIncrement());
            long enriched = deterministicWork(id + body, requestId) & 0xffff;
            documents.put("flow:" + id, "{\"id\":\"" + escape(id) + "\",\"stage\":\"notified\",\"enrichment\":" + enriched + "}");
            counters.merge("events", 1L, Long::sum);
            return documents.get("flow:" + id);
        }
        if (path.startsWith("/flows/")) {
            String id = path.substring("/flows/".length());
            return documents.getOrDefault("flow:" + id, "{\"id\":\"" + escape(id) + "\",\"stage\":\"missing\"}");
        }
        if (method.equals("POST") && path.equals("/notifications/stub")) {
            counters.merge("notifications", 1L, Long::sum);
            return "{\"accepted\":true,\"fanout\":" + (1 + Math.floorMod(body.hashCode(), 8)) + "}";
        }
        return generic(path, requestId);
    }

    private String streaming(String method, String path, String body, long requestId) {
        if (method.equals("POST") && path.equals("/events")) {
            String key = field(body, "key", "device-" + (1 + requestId % 4));
            long value = number(body, "value", requestId % 100);
            counters.merge("stream:" + key, value, Long::sum);
            counters.merge("stream_count:" + key, 1L, Long::sum);
            counters.put("lag", Math.max(0L, 100L - requestId));
            return windowJson(key);
        }
        if (path.startsWith("/windows/")) {
            return windowJson(urlDecode(path.substring("/windows/".length())));
        }
        if (path.equals("/lag")) {
            return "{\"watermark_lag_ms\":" + counters.getOrDefault("lag", 0L) + ",\"backpressure\":false}";
        }
        return generic(path, requestId);
    }

    private String inference(String method, String path, String body, long requestId) {
        if (method.equals("POST") && (path.equals("/infer") || path.equals("/features"))) {
            double[] features = features(body, requestId);
            double dot = 0.0D;
            double norm = 0.0D;
            for (int i = 0; i < features.length; i++) {
                dot += features[i] * (i + 1) * 0.125D;
                norm += Math.abs(features[i]);
            }
            int klass = Math.floorMod((int) Math.round(dot * 1000), 7);
            return "{\"model\":\"local-vector\",\"class_id\":" + klass + ",\"score\":" + fmt(dot) + ",\"feature_norm\":" + fmt(norm) + ",\"runtime\":\"java\"}";
        }
        if (path.equals("/model")) {
            return "{\"name\":\"local-vector-model\",\"inputs\":8,\"runtimes\":[\"java\",\"jni-seam\",\"ffm-seam\"]}";
        }
        return generic(path, requestId);
    }

    private String rules(String method, String path, String body, long requestId) {
        if (path.equals("/rules/modes")) {
            return "{\"active\":\"pure-java\",\"available\":[\"pure-java\"],\"deferred\":[\"polyglot\"]}";
        }
        if (method.equals("POST") && (path.equals("/rules/evaluate") || path.equals("/scripts/validate"))) {
            long base = number(body, "base_price_cents", 10_000L + requestId);
            long quantity = number(body, "quantity", 1L + requestId % 5);
            long discount = (quantity >= 3 ? base / 10 : 0) + (requestId % 2 == 0 ? 250 : 0);
            long total = Math.max(0, base * quantity - discount);
            return "{\"engine\":\"pure-java\",\"valid\":true,\"total_cents\":" + total + ",\"discount_cents\":" + discount + "}";
        }
        return generic(path, requestId);
    }

    private String chat(String method, String path, String body, long requestId) {
        if (method.equals("POST") && path.matches("/rooms/[^/]+/messages")) {
            String room = path.split("/")[2];
            String msg = field(body, "message", "msg-" + requestId);
            append("room:" + room, requestId + ":" + msg);
            counters.merge("messages", 1L, Long::sum);
            return "{\"room\":\"" + escape(room) + "\",\"sequence\":" + requestId + ",\"delivered\":" + lists.get("room:" + room).size() + "}";
        }
        if (path.matches("/rooms/[^/]+/events")) {
            String room = path.split("/")[2];
            return "{\"room\":\"" + escape(room) + "\",\"events\":" + listJson(lists.get("room:" + room)) + "}";
        }
        if (method.equals("POST") && path.equals("/connections/simulate")) {
            long conns = number(body, "connections", 100 + requestId);
            counters.put("connections", conns);
            return "{\"connections\":" + conns + ",\"virtual_threads\":" + conns + ",\"accepted\":true}";
        }
        return generic(path, requestId);
    }

    private String functions(String method, String path, String body, long requestId) {
        if (path.equals("/functions")) {
            return "{\"functions\":[\"json-transform\",\"thumbnail-stub\",\"crud-tiny\",\"lightweight-infer\"],\"mode\":\"hotspot\"}";
        }
        if (method.equals("POST") && path.startsWith("/invoke/")) {
            String fn = path.substring("/invoke/".length());
            long checksum = deterministicWork(fn + body, requestId);
            return "{\"function\":\"" + escape(fn) + "\",\"mode\":\"hotspot\",\"checksum\":" + checksum + ",\"cold_start_ms\":" + (20 + requestId % 17) + "}";
        }
        return generic(path, requestId);
    }

    private String etl(String method, String path, String body, long requestId) {
        if (path.equals("/job/schema")) {
            return "{\"input\":[\"user_id\",\"event_day\",\"amount\",\"category\"],\"output\":[\"user_id\",\"event_day\",\"total_amount\",\"event_count\"]}";
        }
        if (method.equals("POST") && path.equals("/job/run")) {
            long records = number(body, "records", 10_000L + requestId);
            long groups = Math.max(1, records / 37);
            documents.put("job:last", "{\"status\":\"SUCCEEDED\",\"records\":" + records + ",\"groups\":" + groups + "}");
            return documents.get("job:last");
        }
        if (path.equals("/job/status")) {
            return documents.getOrDefault("job:last", "{\"status\":\"IDLE\"}");
        }
        return generic(path, requestId);
    }

    private String onnx(String method, String path, String body, long requestId) {
        if (path.equals("/api/v1/inference/health")) {
            return "{\"status\":\"UP\",\"model\":\"distilbert-sst2-local\"}";
        }
        if (method.equals("POST") && path.equals("/api/v1/inference/classify")) {
            String text = field(body, "text", "deterministic local inference " + requestId);
            long tokens = Math.min(128, Math.max(1, text.split("\\s+").length + 2));
            double positive = (Math.floorMod(text.hashCode(), 10_000) / 10_000.0D);
            int predicted = positive >= 0.5D ? 1 : 0;
            return "{\"predictedClass\":" + predicted + ",\"confidence\":" + fmt(Math.max(positive, 1.0D - positive)) + ",\"tokenCount\":" + tokens + ",\"provider\":\"cpu-local\"}";
        }
        return generic(path, requestId);
    }

    private String fleet(String method, String path, String body, long requestId) {
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
        return generic(path, requestId);
    }

    private String autoscale(String method, String path, String query, long requestId) {
        if (path.startsWith("/api/v1/products/")) {
            String product = path.substring("/api/v1/products/".length());
            Map<String, String> q = query(query);
            long base = 500 + Math.floorMod(product.hashCode(), 50_000);
            long regional = "EU".equals(q.getOrDefault("region", "US")) ? 125 : 0;
            long loyalty = q.getOrDefault("customerId", "").endsWith("7") ? -75 : 0;
            return "{\"productId\":\"" + escape(product) + "\",\"region\":\"" + escape(q.getOrDefault("region", "US")) + "\",\"customerId\":\"" + escape(q.getOrDefault("customerId", "unknown")) + "\",\"finalPrice\":" + Math.max(1, base + regional + loyalty) + "}";
        }
        return generic(path, requestId);
    }

    private String trading(String method, String path, String body, long requestId) {
        if (method.equals("POST") && path.equals("/grpc/SubmitOrder")) {
            String symbol = field(body, "symbol", "FOO");
            long qty = number(body, "quantity", 100 + requestId);
            long price = number(body, "price_nanos", 125_000_000L + requestId);
            boolean accepted = qty > 0 && price > 0 && qty <= 1_000_000;
            String id = "order-" + ids.getAndIncrement();
            documents.put(id, "{\"order_id\":\"" + id + "\",\"status\":\"BOOKED\",\"symbol\":\"" + escape(symbol) + "\"}");
            return "{\"order_id\":\"" + id + "\",\"accepted\":" + accepted + ",\"matching_engine_ns\":" + (300 + requestId % 100) + "}";
        }
        if (method.equals("POST") && path.equals("/grpc/CancelOrder")) {
            String id = field(body, "order_id", "order-1");
            documents.put(id, "{\"order_id\":\"" + escape(id) + "\",\"status\":\"CANCELLED\"}");
            return "{\"order_id\":\"" + escape(id) + "\",\"accepted\":true}";
        }
        if (path.startsWith("/grpc/GetOrderStatus/")) {
            String id = path.substring("/grpc/GetOrderStatus/".length());
            return documents.getOrDefault(id, "{\"order_id\":\"" + escape(id) + "\",\"status\":\"UNKNOWN\"}");
        }
        return generic(path, requestId);
    }

    private String monolith(String method, String path, String body, long requestId) {
        if (path.startsWith("/api/customers/")) {
            String id = path.substring("/api/customers/".length());
            return "{\"customerId\":\"" + escape(id) + "\",\"segments\":[\"retail\",\"loyalty\"],\"rulesEvaluated\":12}";
        }
        if (method.equals("POST") && path.equals("/api/orders")) {
            String id = "mono-order-" + ids.getAndIncrement();
            documents.put(id, "{\"orderId\":\"" + id + "\",\"state\":\"VALIDATED\",\"scheduledJobsTouched\":6}");
            return documents.get(id);
        }
        if (path.equals("/api/reports/daily")) {
            return "{\"reportDate\":\"" + Instant.now().toString().substring(0, 10) + "\",\"beansVisited\":512,\"rulesEvaluated\":12,\"orders\":" + documents.size() + "}";
        }
        return generic(path, requestId);
    }

    private String generic(String path, long requestId) {
        long work = deterministicWork(path, requestId);
        return "{\"benchmark\":\"" + benchmark + "\",\"service\":\"" + escape(title) + "\",\"path\":\"" + escape(path) + "\",\"request\":" + requestId + ",\"checksum\":" + work + ",\"ts\":\"" + Instant.now() + "\"}";
    }

    private String windowJson(String key) {
        long count = counters.getOrDefault("stream_count:" + key, 0L);
        long sum = counters.getOrDefault("stream:" + key, 0L);
        return "{\"key\":\"" + escape(key) + "\",\"window\":\"rolling-60s\",\"count\":" + count + ",\"sum\":" + sum + "}";
    }

    private int fraudScore(String from, String to, long amount) {
        return Math.floorMod((from + to + amount).hashCode(), 1000);
    }

    private void append(String key, String value) {
        lists.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
        histories.computeIfAbsent(key, ignored -> new ArrayDeque<>()).add(value);
    }

    private static double[] features(String body, long requestId) {
        double[] out = new double[8];
        for (int i = 0; i < out.length; i++) {
            out[i] = ((body.hashCode() + requestId * (i + 3)) & 0xff) / 255.0D;
        }
        return out;
    }

    private static String field(String body, String name, String fallback) {
        String quoted = "\"" + name + "\"";
        int key = body.indexOf(quoted);
        if (key < 0) {
            return fallback;
        }
        int colon = body.indexOf(':', key + quoted.length());
        if (colon < 0) {
            return fallback;
        }
        int firstQuote = body.indexOf('"', colon + 1);
        if (firstQuote < 0) {
            return fallback;
        }
        int secondQuote = body.indexOf('"', firstQuote + 1);
        if (secondQuote < 0) {
            return fallback;
        }
        return body.substring(firstQuote + 1, secondQuote);
    }

    private static long number(String body, String name, long fallback) {
        String quoted = "\"" + name + "\"";
        int key = body.indexOf(quoted);
        if (key < 0) {
            return fallback;
        }
        int colon = body.indexOf(':', key + quoted.length());
        if (colon < 0) {
            return fallback;
        }
        int start = colon + 1;
        while (start < body.length() && Character.isWhitespace(body.charAt(start))) { start++; }
        int end = start;
        while (end < body.length() && (Character.isDigit(body.charAt(end)) || body.charAt(end) == '-')) { end++; }
        if (end == start) {
            return fallback;
        }
        try {
            return Long.parseLong(body.substring(start, end));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                out.put(urlDecode(part.substring(0, eq)), urlDecode(part.substring(eq + 1)));
            }
        }
        return out;
    }

    private static String listJson(Iterable<String> values) {
        if (values == null) {
            return "[]";
        }
        StringBuilder out = new StringBuilder("[");
        boolean first = true;
        for (String value : values) {
            if (!first) { out.append(','); }
            first = false;
            out.append('"').append(escape(value)).append('"');
        }
        return out.append(']').toString();
    }

    private static long deterministicWork(String path, long seed) {
        long x = seed ^ path.hashCode();
        for (int i = 0; i < 512; i++) {
            x = (x * 2862933555777941757L) + 3037000493L;
        }
        return x;
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.6f", value);
    }

    private static String urlDecode(String in) {
        return URLDecoder.decode(in, StandardCharsets.UTF_8);
    }

    private static String escape(String in) {
        StringBuilder out = new StringBuilder(in.length());
        for (int i = 0; i < in.length(); i++) {
            char c = in.charAt(i);
            if (c == 92) {
                out.append((char) 92).append((char) 92);
            } else if (c == 34) {
                out.append((char) 92).append((char) 34);
            } else if (c == '\n') {
                out.append("\\n");
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private void log(String event, String extra) {
        System.out.println("{\"event\":\"" + event + "\",\"benchmark\":\"" + benchmark + "\"," + extra + "}");
    }

    private static void json(HttpExchange ex, int code, String body) throws IOException {
        bytes(ex, code, "application/json", body);
    }

    private static void bytes(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }
}
