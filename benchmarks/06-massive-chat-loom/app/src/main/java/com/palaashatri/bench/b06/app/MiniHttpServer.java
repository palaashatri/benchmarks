package com.palaashatri.bench.b06.app;

import com.palaashatri.bench.common.FramedTcpServer;
import com.palaashatri.bench.common.Json;
import com.palaashatri.bench.common.Routes;
import com.palaashatri.bench.common.RuntimeInfo;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Virtual-thread persistent TCP fan-out with an HTTP control plane. */
public final class MiniHttpServer implements AutoCloseable {
    private static final class Room {
        final Map<FramedTcpServer.Session, String> subscribers = new ConcurrentHashMap<>();
        final Set<String> registrations = ConcurrentHashMap.newKeySet();
        final ArrayDeque<Map<String, Object>> history = new ArrayDeque<>();
    }
    private final String benchmark;
    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private final AtomicLong published = new AtomicLong();
    private final AtomicLong queued = new AtomicLong();
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong requests = new AtomicLong();
    private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private HttpServer server;
    private FramedTcpServer tcp;
    public MiniHttpServer(String benchmark, String ignoredTitle) {
        this.benchmark = benchmark;
        for (int i = 1; i <= 50; i++) rooms.put("room-" + i, new Room());
    }
    private synchronized Room room(String name) {
        if (!name.matches("[A-Za-z0-9_.-]{1,64}")) throw new IllegalArgumentException("invalid room name");
        if (!rooms.containsKey(name) && rooms.size() >= 1_000) throw new IllegalArgumentException("room capacity reached");
        return rooms.computeIfAbsent(name, ignored -> new Room());
    }
    public void start(int port) throws IOException {
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "chat-shutdown"));
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 256);
            tcp = new FramedTcpServer(Executors.newVirtualThreadPerTaskExecutor(), new FramedTcpServer.Handler() {
                @Override public Map<String, Object> onFrame(FramedTcpServer.Session session, Map<String, Object> frame) {
                    String op = Json.string(frame, "op", "");
                    if (op.equals("ping")) return Map.of("op", "pong");
                    if (!Set.of("subscribe", "unsubscribe", "publish").contains(op)) return Map.of("error", "unknown_operation");
                    String name = Json.string(frame, "room", "");
                    Room room = room(name);
                    if (op.equals("subscribe")) {
                        String user = Json.string(frame, "user", "");
                        if (user.isBlank() || user.length() > 64) throw new IllegalArgumentException("invalid user");
                        room.subscribers.put(session, user);
                        if (!session.isOpen()) room.subscribers.remove(session);
                        return Map.of("subscribed", true, "room", name, "user", user);
                    }
                    if (op.equals("unsubscribe")) { room.subscribers.remove(session); return Map.of("unsubscribed", true, "room", name); }
                    String user = room.subscribers.get(session);
                    if (user == null) return Map.of("error", "subscription_required");
                    return publish(name, room, user, Json.string(frame, "content", ""));
                }
                @Override public void onClose(FramedTcpServer.Session session) { rooms.values().forEach(room -> room.subscribers.remove(session)); }
                @Override public void onWritten(FramedTcpServer.Session session, Map<String, Object> frame) {
                    if ("message".equals(frame.get("op"))) delivered.incrementAndGet();
                }
            });
            server.createContext("/health", Routes.guard(e -> Json.reply(e, 200, Map.of("status", "UP",
                    "mode", "persistent-tcp-chat", "persistent_connections", true, "tcp_port", tcp.port()))));
            server.createContext("/runtime", Routes.guard(e -> Json.bytes(e, 200, "application/json", RuntimeInfo.json())));
            server.createContext("/metrics", Routes.guard(this::metrics));
            server.createContext("/api/v1/stats", Routes.guard(this::stats));
            server.createContext("/rooms", Routes.guard(this::rooms));
            server.setExecutor(executor); server.start(); RuntimeInfo.publishPort(server.getAddress().getPort());
            System.out.println(Json.stringify(Map.of("event", "started", "benchmark", benchmark, "port", server.getAddress().getPort(), "tcp_port", tcp.port())));
        } catch (IOException | RuntimeException failure) { close(); throw failure; }
    }
    private Map<String, Object> publish(String name, Room room, String sender, String content) {
        if (content.getBytes(StandardCharsets.UTF_8).length > 16_384) throw new IllegalArgumentException("message too large");
        synchronized (room) {
            long id = ids.incrementAndGet();
            var message = Map.<String, Object>of("op", "message", "room", name, "message_id", id, "sender", sender, "content", content);
            room.history.addLast(message);
            if (room.history.size() > 100) room.history.removeFirst();
            long count = room.subscribers.keySet().stream().filter(session -> session.offer(message)).count();
            published.incrementAndGet(); queued.addAndGet(count);
            return Map.of("room_id", name, "message_id", id, "queued_deliveries", count, "delivery_model", "persistent-tcp");
        }
    }
    private void rooms(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/rooms")) {
            if (!Routes.method(exchange, "GET")) return;
            var list = new ArrayList<Object>();
            rooms.forEach((name, room) -> {
                synchronized (room) { list.add(Map.of("id", name, "subscribers", room.subscribers.size(), "messages", room.history.size())); }
            });
            Json.reply(exchange, 200, list); return;
        }
        String[] pieces = path.split("/");
        if (pieces.length != 4) { Json.reply(exchange, 404, Map.of("error", "not_found")); return; }
        String name = pieces[2], action = pieces[3];
        Room room = room(name);
        if (action.equals("subscribers")) {
            if (exchange.getRequestMethod().equals("POST")) {
                String user = Json.string(Json.read(exchange), "user", "");
                if (user.isBlank() || user.length() > 64) throw new IllegalArgumentException("invalid user");
                room.registrations.add(user);
            } else if (!Routes.method(exchange, "GET")) return;
            Json.reply(exchange, 200, Map.of("room_id", name, "subscribers", room.subscribers.size(),
                    "registered_users", room.registrations.size(), "socket_required", true)); return;
        }
        if (!action.equals("messages")) { Json.reply(exchange, 404, Map.of("error", "not_found")); return; }
        if (exchange.getRequestMethod().equals("POST")) {
            var frame = Json.read(exchange);
            Json.reply(exchange, 200, publish(name, room, Json.string(frame, "sender", "anonymous"), Json.string(frame, "content", "")));
        } else {
            if (!Routes.method(exchange, "GET")) return;
            synchronized (room) { Json.reply(exchange, 200, room.history); }
        }
    }
    private void stats(HttpExchange exchange) throws IOException {
        Json.reply(exchange, 200, Map.of("active_rooms", rooms.size(), "subscribers", rooms.values().stream().mapToLong(r -> r.subscribers.size()).sum(),
                "persistent_connections", tcp.connections(), "messages_published", published.get(), "queued_deliveries", queued.get(),
                "socket_deliveries", delivered.get(), "delivery_definition", "flushed-to-socket; no client acknowledgement"));
    }
    private void metrics(HttpExchange exchange) throws IOException {
        Json.bytes(exchange, 200, "text/plain; version=0.0.4", "chat_messages_published_total " + published.get() + "\n"
                + "chat_socket_deliveries_total " + delivered.get() + "\n" + "chat_queued_deliveries_total " + queued.get() + "\n"
                + "chat_persistent_connections " + tcp.connections() + "\n"
                + "benchmark_requests_total{benchmark=\"" + benchmark + "\"} " + requests.get() + "\n");
    }
    @Override public synchronized void close() {
        if (server != null) server.stop(0);
        if (tcp != null) tcp.close();
        executor.shutdownNow();
    }
}
