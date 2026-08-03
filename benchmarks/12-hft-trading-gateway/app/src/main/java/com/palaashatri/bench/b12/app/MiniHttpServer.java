package com.palaashatri.bench.b12.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deterministic, local-only matching-engine benchmark prototype.
 *
 * This is synthetic JVM workload code. It has no external venue connectivity,
 * account handling, market-data feed, or ability to place real transactions.
 * The transport is HTTP and the workload remains Tier 0 until real gRPC and a
 * coordinated-omission-safe histogram harness are implemented.
 */
public final class MiniHttpServer {
    enum Side { BUY, SELL }
    enum Status { OPEN, PARTIALLY_FILLED, FILLED, CANCELLED }

    record Order(
            String id,
            String symbol,
            Side side,
            long originalQuantity,
            long remainingQuantity,
            long priceNanos,
            long sequence,
            Status status
    ) { }

    static final class SymbolBook {
        final PriorityQueue<Order> bids = new PriorityQueue<>(
                Comparator.<Order>comparingLong(Order::priceNanos)
                        .reversed()
                        .thenComparingLong(Order::sequence));
        final PriorityQueue<Order> asks = new PriorityQueue<>(
                Comparator.<Order>comparingLong(Order::priceNanos)
                        .thenComparingLong(Order::sequence));
    }

    static final class OrderBook {
        private final Map<String, SymbolBook> symbols = new ConcurrentHashMap<>();
        private final Map<String, Order> orders = new ConcurrentHashMap<>();
        private final AtomicLong idSequence = new AtomicLong(1);
        private final AtomicLong arrivalSequence = new AtomicLong(1);
        final AtomicLong matchEvents = new AtomicLong();
        final AtomicLong filledQuantity = new AtomicLong();
        final AtomicLong rejected = new AtomicLong();

        String submit(String symbolValue, String sideValue, long quantity, long priceNanos) {
            String symbol = symbolValue == null ? "" : symbolValue.trim().toUpperCase();
            Side side;
            try {
                side = Side.valueOf(sideValue == null ? "" : sideValue.trim().toUpperCase());
            } catch (IllegalArgumentException exception) {
                rejected.incrementAndGet();
                return rejection("INVALID_SIDE");
            }
            if (symbol.isBlank() || quantity <= 0 || quantity > 10_000_000 || priceNanos <= 0) {
                rejected.incrementAndGet();
                return rejection("INVALID_PARAMS");
            }

            SymbolBook book = symbols.computeIfAbsent(symbol, ignored -> new SymbolBook());
            synchronized (book) {
                String id = "ord-" + idSequence.getAndIncrement();
                Order order = new Order(
                        id,
                        symbol,
                        side,
                        quantity,
                        quantity,
                        priceNanos,
                        arrivalSequence.getAndIncrement(),
                        Status.OPEN);
                orders.put(id, order);
                queue(book, order).offer(order);
                match(book);
                return "{\"order_id\":\"" + id + "\",\"accepted\":true}";
            }
        }

        String cancel(String id) {
            Order current = orders.get(id);
            if (current == null) {
                return "{\"order_id\":\"" + escape(id)
                        + "\",\"accepted\":false,\"reason\":\"NOT_FOUND\"}";
            }
            SymbolBook book = symbols.get(current.symbol());
            synchronized (book) {
                current = orders.get(id);
                if (current.status() == Status.FILLED || current.status() == Status.CANCELLED) {
                    return "{\"order_id\":\"" + escape(id)
                            + "\",\"accepted\":false,\"reason\":\"ALREADY_"
                            + current.status() + "\"}";
                }
                queue(book, current).remove(current);
                orders.put(id, replace(current, current.remainingQuantity(), Status.CANCELLED));
                return "{\"order_id\":\"" + escape(id) + "\",\"accepted\":true}";
            }
        }

        String status(String id) {
            Order order = orders.get(id);
            if (order == null) {
                return "{\"order_id\":\"" + escape(id) + "\",\"status\":\"UNKNOWN\"}";
            }
            return "{\"order_id\":\"" + escape(order.id())
                    + "\",\"symbol\":\"" + escape(order.symbol())
                    + "\",\"side\":\"" + order.side()
                    + "\",\"status\":\"" + order.status()
                    + "\",\"original_quantity\":" + order.originalQuantity()
                    + ",\"remaining_quantity\":" + order.remainingQuantity()
                    + ",\"price_nanos\":" + order.priceNanos() + "}";
        }

        private void match(SymbolBook book) {
            while (!book.bids.isEmpty() && !book.asks.isEmpty()) {
                Order bid = book.bids.peek();
                Order ask = book.asks.peek();
                if (bid.priceNanos() < ask.priceNanos()) {
                    return;
                }
                book.bids.poll();
                book.asks.poll();
                long fill = Math.min(bid.remainingQuantity(), ask.remainingQuantity());
                bid = afterFill(bid, fill);
                ask = afterFill(ask, fill);
                orders.put(bid.id(), bid);
                orders.put(ask.id(), ask);
                if (bid.remainingQuantity() > 0) {
                    book.bids.offer(bid);
                }
                if (ask.remainingQuantity() > 0) {
                    book.asks.offer(ask);
                }
                matchEvents.incrementAndGet();
                filledQuantity.addAndGet(fill);
            }
        }

        private static Order afterFill(Order order, long fill) {
            long remaining = order.remainingQuantity() - fill;
            return replace(
                    order,
                    remaining,
                    remaining == 0 ? Status.FILLED : Status.PARTIALLY_FILLED);
        }

        private static Order replace(Order order, long remaining, Status status) {
            return new Order(
                    order.id(),
                    order.symbol(),
                    order.side(),
                    order.originalQuantity(),
                    remaining,
                    order.priceNanos(),
                    order.sequence(),
                    status);
        }

        private static PriorityQueue<Order> queue(SymbolBook book, Order order) {
            return order.side() == Side.BUY ? book.bids : book.asks;
        }

        private static String rejection(String reason) {
            return "{\"order_id\":\"\",\"accepted\":false,\"reason\":\""
                    + reason + "\"}";
        }
    }

    private final String benchmark;
    private final OrderBook orderBook = new OrderBook();
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong submitDurationNs = new AtomicLong();

    public MiniHttpServer(String benchmark, String ignoredTitle) {
        this.benchmark = benchmark;
    }

    public void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 512);
        server.createContext("/health", this::health);
        server.createContext("/runtime", this::runtime);
        server.createContext("/metrics", this::metrics);
        server.createContext("/orders", this::orders);
        server.createContext("/grpc/SubmitOrder", this::legacySubmit);
        server.createContext("/grpc/CancelOrder", this::legacyCancel);
        server.createContext("/grpc/GetOrderStatus/", this::legacyStatus);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.printf(
                "{\"event\":\"started\",\"benchmark\":\"%s\","
                        + "\"transport\":\"http-prototype\",\"port\":%d,\"pid\":%d}%n",
                benchmark,
                port,
                ProcessHandle.current().pid());
    }

    private void health(HttpExchange exchange) throws IOException {
        json(exchange, 200,
                "{\"status\":\"UP\",\"transport\":\"http-prototype\","
                        + "\"grpc_active\":false}");
    }

    private void runtime(HttpExchange exchange) throws IOException {
        json(exchange, 200,
                "{\"pid\":" + ProcessHandle.current().pid()
                        + ",\"run_token\":\""
                        + escape(System.getenv().getOrDefault("BENCH_RUN_TOKEN", ""))
                        + "\",\"java_version\":\""
                        + escape(System.getProperty("java.version")) + "\"}");
    }

    private void metrics(HttpExchange exchange) throws IOException {
        String body = "# TYPE gateway_orders_submitted_total counter\n"
                + "gateway_orders_submitted_total " + submitted.get() + "\n"
                + "# TYPE gateway_match_events_total counter\n"
                + "gateway_match_events_total " + orderBook.matchEvents.get() + "\n"
                + "# TYPE gateway_filled_quantity_total counter\n"
                + "gateway_filled_quantity_total " + orderBook.filledQuantity.get() + "\n"
                + "# TYPE gateway_submit_duration_seconds_sum counter\n"
                + "gateway_submit_duration_seconds_sum "
                + format(submitDurationNs.get() / 1_000_000_000.0) + "\n"
                + "# TYPE gateway_rejected_total counter\n"
                + "gateway_rejected_total " + orderBook.rejected.get() + "\n"
                + "# TYPE gateway_grpc_active gauge\n"
                + "gateway_grpc_active 0\n"
                + "# TYPE benchmark_requests_total counter\n"
                + "benchmark_requests_total{benchmark=\"" + benchmark + "\"} "
                + requests.get() + "\n";
        bytes(exchange, 200, "text/plain; version=0.0.4", body);
    }

    private void orders(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        if ("POST".equalsIgnoreCase(method) && "/orders".equals(path)) {
            submit(exchange);
            return;
        }
        if (path.startsWith("/orders/")) {
            String id = path.substring("/orders/".length());
            if ("GET".equalsIgnoreCase(method)) {
                json(exchange, 200, orderBook.status(id));
                return;
            }
            if ("DELETE".equalsIgnoreCase(method)) {
                json(exchange, 200, orderBook.cancel(id));
                return;
            }
        }
        json(exchange, 404, "{\"error\":\"not_found\"}");
    }

    private void submit(HttpExchange exchange) throws IOException {
        long started = System.nanoTime();
        String body = new String(
                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String response = orderBook.submit(
                field(body, "symbol", ""),
                field(body, "side", ""),
                number(body, "quantity", -1),
                number(body, "price_nanos", -1));
        submitted.incrementAndGet();
        json(exchange, 200, response);
        submitDurationNs.addAndGet(System.nanoTime() - started);
    }

    private void legacySubmit(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        submit(exchange);
    }

    private void legacyCancel(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        String body = new String(
                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        json(exchange, 200, orderBook.cancel(field(body, "order_id", "")));
    }

    private void legacyStatus(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        String id = exchange.getRequestURI().getPath()
                .substring("/grpc/GetOrderStatus/".length());
        json(exchange, 200, orderBook.status(id));
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

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.9f", value);
    }

    private static void json(HttpExchange exchange, int status, String body)
            throws IOException {
        bytes(exchange, status, "application/json", body);
    }

    private static void bytes(
            HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }
}
