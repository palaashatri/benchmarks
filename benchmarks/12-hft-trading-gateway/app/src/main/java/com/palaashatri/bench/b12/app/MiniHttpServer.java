package com.palaashatri.bench.b12.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HFT Trading Gateway — HTTP facade over an in-memory order book.
 *
 * Routes (matching the harness contract):
 *   POST /orders                       — submit order, returns {"order_id":"...","accepted":true}
 *   DELETE /orders/{id}                — cancel order
 *   GET  /orders/{id}                  — order status
 *   POST /grpc/SubmitOrder             — legacy route (same logic)
 *   POST /grpc/CancelOrder             — legacy route
 *   GET  /grpc/GetOrderStatus/{id}     — legacy route
 *   GET  /health
 *   GET  /metrics
 */
public final class MiniHttpServer {

    /* ------------------------------------------------------------------ order book */

    /**
     * Immutable order record (acts as value-type equivalent — no mutability on hot path).
     * Using a plain record here; the real value-class experiment requires --enable-preview
     * and a JDK 25 preview build.
     */
    record Order(
            String id,
            String symbol,
            String side,          // "BUY" | "SELL"
            long quantity,
            long priceNanos,
            long timestamp,
            String status         // ACCEPTED | FILLED | CANCELLED | REJECTED
    ) {}

    /** Thread-safe, price-time priority order book. */
    static final class OrderBook {
        // bids: highest price first, then earliest timestamp
        private final PriorityQueue<Order> bids = new PriorityQueue<>(
                Comparator.<Order>comparingLong(o -> -o.priceNanos())
                          .thenComparingLong(Order::timestamp));
        // asks: lowest price first, then earliest timestamp
        private final PriorityQueue<Order> asks = new PriorityQueue<>(
                Comparator.<Order>comparingLong(Order::priceNanos)
                          .thenComparingLong(Order::timestamp));

        private final ConcurrentHashMap<String, Order> orders = new ConcurrentHashMap<>();
        private final AtomicLong idGen = new AtomicLong(1);
        final AtomicLong matchedPairs = new AtomicLong();
        final AtomicLong rejectedOrders = new AtomicLong();

        /** Submit a new order; returns the ack JSON. */
        synchronized String submit(String symbol, String side, long quantity, long priceNanos) {
            if (quantity <= 0 || priceNanos <= 0 || quantity > 10_000_000) {
                rejectedOrders.incrementAndGet();
                return "{\"order_id\":\"\",\"accepted\":false,\"reason\":\"INVALID_PARAMS\"}";
            }
            String id = "ord-" + idGen.getAndIncrement();
            Order order = new Order(id, symbol, side.toUpperCase(), quantity, priceNanos,
                    System.nanoTime(), "ACCEPTED");
            orders.put(id, order);
            if ("BUY".equals(order.side())) bids.offer(order);
            else asks.offer(order);
            tryMatch();
            return "{\"order_id\":\"" + id + "\",\"accepted\":true,\"reason\":\"\"}";
        }

        /** Cancel an existing order. */
        synchronized String cancel(String orderId) {
            Order o = orders.get(orderId);
            if (o == null) return "{\"order_id\":\"" + escape(orderId) + "\",\"accepted\":false,\"reason\":\"NOT_FOUND\"}";
            if ("FILLED".equals(o.status()) || "CANCELLED".equals(o.status()))
                return "{\"order_id\":\"" + escape(orderId) + "\",\"accepted\":false,\"reason\":\"ALREADY_" + o.status() + "\"}";
            Order cancelled = new Order(o.id(), o.symbol(), o.side(), o.quantity(),
                    o.priceNanos(), o.timestamp(), "CANCELLED");
            orders.put(orderId, cancelled);
            bids.remove(o);
            asks.remove(o);
            return "{\"order_id\":\"" + escape(orderId) + "\",\"accepted\":true,\"reason\":\"\"}";
        }

        /** Get the status of an order. */
        String status(String orderId) {
            Order o = orders.get(orderId);
            if (o == null) return "{\"order_id\":\"" + escape(orderId) + "\",\"status\":\"UNKNOWN\"}";
            return "{\"order_id\":\"" + escape(o.id()) + "\",\"status\":\"" + o.status()
                    + "\",\"symbol\":\"" + escape(o.symbol()) + "\",\"side\":\"" + o.side()
                    + "\",\"quantity\":" + o.quantity() + ",\"price_nanos\":" + o.priceNanos() + "}";
        }

        long orderCount() { return orders.size(); }

        /** Attempt to match top-of-book bids vs asks (price-crossing). */
        private void tryMatch() {
            while (!bids.isEmpty() && !asks.isEmpty()) {
                Order bid = bids.peek();
                Order ask = asks.peek();
                // A bid crosses the ask if bid price >= ask price
                if (bid.priceNanos() >= ask.priceNanos()) {
                    bids.poll();
                    asks.poll();
                    orders.put(bid.id(), new Order(bid.id(), bid.symbol(), bid.side(),
                            bid.quantity(), bid.priceNanos(), bid.timestamp(), "FILLED"));
                    orders.put(ask.id(), new Order(ask.id(), ask.symbol(), ask.side(),
                            ask.quantity(), ask.priceNanos(), ask.timestamp(), "FILLED"));
                    matchedPairs.incrementAndGet();
                } else {
                    break;
                }
            }
        }
    }

    /* ------------------------------------------------------------------ server */

    private final String benchmark;
    @SuppressWarnings("unused")
    private final String title;

    private final OrderBook book = new OrderBook();
    private final AtomicLong requests = new AtomicLong();
    /** Cumulative sum of per-request wire latency in nanoseconds. */
    private final AtomicLong wireLatencyNsTotal = new AtomicLong();
    private final AtomicLong ordersTotal = new AtomicLong();

    public MiniHttpServer(String benchmark, String title) {
        this.benchmark = benchmark;
        this.title = title;
    }

    public void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 512);
        server.createContext("/health",           this::health);
        server.createContext("/metrics",          this::metrics);
        server.createContext("/actuator/health",  this::health);
        server.createContext("/actuator/prometheus", this::metrics);
        server.createContext("/orders",           this::ordersRoute);
        server.createContext("/grpc/SubmitOrder", this::submitOrderRoute);
        server.createContext("/grpc/CancelOrder", this::cancelOrderRoute);
        server.createContext("/grpc/GetOrderStatus/", this::getOrderStatusRoute);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        log("started", "\"port\":" + port);
    }

    /* ------------------------------------------------------------------ handlers */

    private void health(HttpExchange ex) throws IOException {
        json(ex, 200, "{\"status\":\"UP\",\"benchmark\":\"" + benchmark
                + "\",\"orders_total\":" + ordersTotal.get()
                + ",\"matched_pairs\":" + book.matchedPairs.get() + "}");
    }

    private void metrics(HttpExchange ex) throws IOException {
        long total = ordersTotal.get();
        long latNs = wireLatencyNsTotal.get();
        String body =
                "# TYPE gateway_orders_total counter\n"
                + "gateway_orders_total " + total + "\n"
                + "# TYPE gateway_matched_pairs_total counter\n"
                + "gateway_matched_pairs_total " + book.matchedPairs.get() + "\n"
                + "# TYPE gateway_wire_latency_ns_total counter\n"
                + "gateway_wire_latency_ns_total " + latNs + "\n"
                + "# TYPE gateway_reject_count_total counter\n"
                + "gateway_reject_count_total " + book.rejectedOrders.get() + "\n"
                + "# TYPE jvm_available_processors gauge\n"
                + "jvm_available_processors " + Runtime.getRuntime().availableProcessors() + "\n"
                + "# TYPE benchmark_requests_total counter\n"
                + "benchmark_requests_total{benchmark=\"" + benchmark + "\"} " + requests.get() + "\n";
        bytes(ex, 200, "text/plain; version=0.0.4", body);
    }

    /**
     * /orders route — handles POST (submit) and sub-paths for DELETE/GET.
     * Also handles /orders/{id} for GET and DELETE.
     */
    private void ordersRoute(HttpExchange ex) throws IOException {
        long t0 = System.nanoTime();
        requests.incrementAndGet();
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();

        try {
            if ("POST".equalsIgnoreCase(method) && "/orders".equals(path)) {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String symbol = field(body, "symbol", "UNKNOWN");
                String side   = field(body, "side", "BUY");
                long qty      = number(body, "quantity", 100L);
                long price    = number(body, "price_nanos", 1_500_000L);
                ordersTotal.incrementAndGet();
                wireLatencyNsTotal.addAndGet(System.nanoTime() - t0);
                json(ex, 200, book.submit(symbol, side, qty, price));
                return;
            }
            // /orders/{id}
            if (path.startsWith("/orders/")) {
                String id = path.substring("/orders/".length());
                if ("DELETE".equalsIgnoreCase(method)) {
                    json(ex, 200, book.cancel(id));
                    return;
                }
                if ("GET".equalsIgnoreCase(method)) {
                    json(ex, 200, book.status(id));
                    return;
                }
            }
            json(ex, 404, "{\"error\":\"no route for " + escape(method) + " " + escape(path) + "\"}");
        } finally {
            wireLatencyNsTotal.addAndGet(System.nanoTime() - t0);
        }
    }

    /** Legacy /grpc/SubmitOrder route. */
    private void submitOrderRoute(HttpExchange ex) throws IOException {
        long t0 = System.nanoTime();
        requests.incrementAndGet();
        ordersTotal.incrementAndGet();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String symbol = field(body, "symbol", "FOO");
        String side   = field(body, "side", "BUY");
        long qty      = number(body, "quantity", 100L);
        long price    = number(body, "price_nanos", 125_000_000L);
        wireLatencyNsTotal.addAndGet(System.nanoTime() - t0);
        json(ex, 200, book.submit(symbol, side, qty, price));
    }

    /** Legacy /grpc/CancelOrder route. */
    private void cancelOrderRoute(HttpExchange ex) throws IOException {
        requests.incrementAndGet();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String id = field(body, "order_id", "");
        json(ex, 200, book.cancel(id));
    }

    /** Legacy /grpc/GetOrderStatus/{id} route. */
    private void getOrderStatusRoute(HttpExchange ex) throws IOException {
        requests.incrementAndGet();
        String path = ex.getRequestURI().getPath();
        String id = path.substring("/grpc/GetOrderStatus/".length());
        String s = book.status(id);
        int code = s.contains("\"UNKNOWN\"") ? 200 : 200; // always 200 for legacy compat
        json(ex, code, s);
    }

    /* ------------------------------------------------------------------ helpers */

    private static String field(String body, String name, String fallback) {
        String quoted = "\"" + name + "\"";
        int key = body.indexOf(quoted);
        if (key < 0) return fallback;
        int colon = body.indexOf(':', key + quoted.length());
        if (colon < 0) return fallback;
        int firstQuote = body.indexOf('"', colon + 1);
        if (firstQuote < 0) return fallback;
        int secondQuote = body.indexOf('"', firstQuote + 1);
        if (secondQuote < 0) return fallback;
        return body.substring(firstQuote + 1, secondQuote);
    }

    private static long number(String body, String name, long fallback) {
        String quoted = "\"" + name + "\"";
        int key = body.indexOf(quoted);
        if (key < 0) return fallback;
        int colon = body.indexOf(':', key + quoted.length());
        if (colon < 0) return fallback;
        int start = colon + 1;
        while (start < body.length() && Character.isWhitespace(body.charAt(start))) start++;
        int end = start;
        while (end < body.length() && (Character.isDigit(body.charAt(end)) || body.charAt(end) == '-')) end++;
        if (end == start) return fallback;
        try { return Long.parseLong(body.substring(start, end)); } catch (NumberFormatException e) { return fallback; }
    }

    private static String escape(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"'  -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default   -> { if (c < 0x20) out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c)); else out.append(c); }
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
    private void log(String event, String fields) {
        System.out.println("{\"event\":\"" + event + "\",\"benchmark\":\"" + benchmark + "\"," + fields + "}");
    }
}
