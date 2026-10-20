package com.palaashatri.bench.b12.app;

import com.palaashatri.bench.common.FramedTcpServer;
import com.palaashatri.bench.common.Json;
import com.palaashatri.bench.common.Routes;
import com.palaashatri.bench.common.RuntimeInfo;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Comparator;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Synthetic symbol-isolated matching over HTTP and persistent framed TCP; no real venue or gRPC. */
public final class MiniHttpServer implements AutoCloseable {
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
            String symbol = symbolValue == null ? "" : symbolValue.trim().toUpperCase(java.util.Locale.ROOT);
            Side side;
            try {
                side = Side.valueOf(sideValue == null ? "" : sideValue.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                rejected.incrementAndGet();
                return rejection("INVALID_SIDE");
            }
            if (!symbol.matches("[A-Z0-9_.-]{1,32}") || quantity <= 0 || quantity > 10_000_000 || priceNanos <= 0) {
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
    private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private HttpServer server;
    private FramedTcpServer tcp;

    public MiniHttpServer(String benchmark, String ignoredTitle) { this.benchmark = benchmark; }
    public void start(int port) throws IOException {
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "trading-shutdown"));
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 512);
            tcp = new FramedTcpServer(Executors.newVirtualThreadPerTaskExecutor(), (session, frame) -> {
                requests.incrementAndGet();
                String op = Json.string(frame, "op", "");
                String body = switch (op) {
                    case "SubmitOrder" -> submit(frame);
                    case "CancelOrder" -> orderBook.cancel(Json.string(frame, "order_id", ""));
                    case "GetOrderStatus" -> orderBook.status(Json.string(frame, "order_id", ""));
                    default -> Json.stringify(Map.of("error", "unknown_operation"));
                };
                return Json.parseObject(body);
            });
            server.createContext("/health", Routes.guard(e -> Json.reply(e, 200, Map.of("status", "UP",
                    "transport", "http-and-framed-tcp", "grpc_active", false, "tcp_port", tcp.port()))));
            server.createContext("/runtime", Routes.guard(e -> Json.bytes(e, 200, "application/json", RuntimeInfo.json())));
            server.createContext("/metrics", Routes.guard(this::metrics));
            server.createContext("/orders", Routes.guard(this::orders));
            // Backward-compatible HTTP aliases; these are never advertised as gRPC.
            server.createContext("/grpc/SubmitOrder", Routes.guard(e -> {
                if (Routes.method(e, "POST")) Json.bytes(e, 200, "application/json", submit(Json.read(e)));
            }));
            server.createContext("/grpc/CancelOrder", Routes.guard(e -> {
                if (Routes.method(e, "POST")) Json.bytes(e, 200, "application/json", orderBook.cancel(Json.string(Json.read(e), "order_id", "")));
            }));
            server.createContext("/grpc/GetOrderStatus/", Routes.guard(e -> {
                if (Routes.method(e, "GET")) Json.bytes(e, 200, "application/json", orderBook.status(e.getRequestURI().getPath().substring("/grpc/GetOrderStatus/".length())));
            }));
            server.setExecutor(executor); server.start(); RuntimeInfo.publishPort(server.getAddress().getPort());
            System.out.println(Json.stringify(Map.of("event", "started", "benchmark", benchmark, "port", server.getAddress().getPort(), "tcp_port", tcp.port())));
        } catch (IOException | RuntimeException failure) { close(); throw failure; }
    }
    private String submit(Map<String, Object> body) {
        long started = System.nanoTime();
        String result = orderBook.submit(Json.string(body, "symbol", ""), Json.string(body, "side", ""),
                Json.number(body, "quantity", -1), Json.number(body, "price_nanos", -1));
        submitted.incrementAndGet(); submitDurationNs.addAndGet(System.nanoTime() - started);
        return result;
    }
    private void orders(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        String path = exchange.getRequestURI().getPath(), method = exchange.getRequestMethod();
        if (path.equals("/orders")) {
            if (Routes.method(exchange, "POST")) Json.bytes(exchange, 200, "application/json", submit(Json.read(exchange)));
            return;
        }
        if (!path.startsWith("/orders/")) { Json.reply(exchange, 404, Map.of("error", "not_found")); return; }
        String id = path.substring("/orders/".length());
        if (method.equals("GET")) Json.bytes(exchange, 200, "application/json", orderBook.status(id));
        else if (method.equals("DELETE")) Json.bytes(exchange, 200, "application/json", orderBook.cancel(id));
        else Json.reply(exchange, 405, Map.of("error", "method_not_allowed"));
    }
    private void metrics(HttpExchange exchange) throws IOException {
        Json.bytes(exchange, 200, "text/plain; version=0.0.4", "gateway_orders_submitted_total " + submitted.get() + "\n"
                + "gateway_match_events_total " + orderBook.matchEvents.get() + "\n"
                + "gateway_filled_quantity_total " + orderBook.filledQuantity.get() + "\n"
                + "gateway_submit_duration_seconds_sum " + (submitDurationNs.get() / 1_000_000_000.0) + "\n"
                + "gateway_rejected_total " + orderBook.rejected.get() + "\n"
                + "gateway_grpc_active 0\n" + "gateway_tcp_connections " + tcp.connections() + "\n"
                + "benchmark_requests_total{benchmark=\"" + benchmark + "\"} " + requests.get() + "\n");
    }
    private static String escape(String value) {
        String json = Json.stringify(value == null ? "" : value);
        return json.substring(1, json.length() - 1);
    }
    @Override public synchronized void close() {
        if (server != null) server.stop(0);
        if (tcp != null) tcp.close();
        executor.shutdownNow();
    }
}
