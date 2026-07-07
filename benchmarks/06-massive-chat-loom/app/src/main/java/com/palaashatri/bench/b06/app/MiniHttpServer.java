package com.palaashatri.bench.b06.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public final class MiniHttpServer {
    private final String benchmark;
    private final String title;
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong broadcastCount = new AtomicLong();
    private final AtomicLong messageIdSeq = new AtomicLong(1);
    private final ConcurrentHashMap<String, Set<String>> rooms = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<String>> messages = new ConcurrentHashMap<>();

    public MiniHttpServer(String benchmark, String title) {
        this.benchmark = benchmark;
        this.title = title;
    }

    public void start(int port) throws IOException {
        // Warmup: spawn virtual thread to seed rooms and messages
        Thread.ofVirtual().start(() -> {
            for (int i = 1; i <= 50; i++) {
                String roomId = "room-" + i;
                rooms.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet());
                ConcurrentLinkedDeque<String> deque = messages.computeIfAbsent(roomId, k -> new ConcurrentLinkedDeque<>());
                for (int j = 1; j <= 10; j++) {
                    deque.addLast("{\"sender\":\"seed\",\"content\":\"seed message " + j + "\",\"message_id\":" + messageIdSeq.getAndIncrement() + "}");
                }
            }
        });

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 256);

        // Register specific paths before generic prefix paths to avoid conflicts
        server.createContext("/rooms/room-", this::routeRoomSpecific);
        server.createContext("/api/v1/stats", this::stats);
        server.createContext("/health", this::health);
        server.createContext("/metrics", this::metricsHandler);
        server.createContext("/actuator/health", this::health);
        server.createContext("/actuator/prometheus", this::metricsHandler);
        server.createContext("/rooms", this::roomsList);

        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        log("started", "\"port\":" + port + ",\"executor\":\"virtual-threads\"");
    }

    private void routeRoomSpecific(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();

        if ("POST".equals(method) && path.matches("/rooms/[^/]+/messages")) {
            requests.incrementAndGet();
            String roomId = path.split("/")[2];
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String sender = field(body, "sender", "anonymous");
            String content = field(body, "content", "");
            long msgId = messageIdSeq.getAndIncrement();

            rooms.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(sender);
            ConcurrentLinkedDeque<String> deque = messages.computeIfAbsent(roomId, k -> new ConcurrentLinkedDeque<>());
            deque.addLast("{\"sender\":\"" + escape(sender) + "\",\"content\":\"" + escape(content) + "\",\"message_id\":" + msgId + "}");
            // Trim to last 100
            while (deque.size() > 100) {
                deque.pollFirst();
            }
            long bc = broadcastCount.incrementAndGet();
            int delivered = deque.size();

            json(ex, 200, "{\"room_id\":\"" + escape(roomId) + "\",\"delivered\":" + delivered + ",\"message_id\":" + msgId + "}");
        } else if ("GET".equals(method) && path.matches("/rooms/[^/]+/messages")) {
            requests.incrementAndGet();
            String roomId = path.split("/")[2];
            ConcurrentLinkedDeque<String> deque = messages.computeIfAbsent(roomId, k -> new ConcurrentLinkedDeque<>());
            // Last 20
            List<String> last20 = new ArrayList<>();
            Object[] arr = deque.toArray();
            int start = Math.max(0, arr.length - 20);
            for (int i = start; i < arr.length; i++) {
                last20.add((String) arr[i]);
            }
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < last20.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(last20.get(i));
            }
            sb.append("]");
            json(ex, 200, sb.toString());
        } else if ("GET".equals(method) && path.matches("/rooms/[^/]+/subscribers")) {
            requests.incrementAndGet();
            String roomId = path.split("/")[2];
            Set<String> subs = rooms.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet());
            json(ex, 200, "{\"room_id\":\"" + escape(roomId) + "\",\"subscribers\":" + subs.size() + "}");
        } else {
            json(ex, 404, "{\"error\":\"no route for " + escape(path) + "\",\"benchmark\":\"" + benchmark + "\"}");
        }
    }

    private void roomsList(HttpExchange ex) throws IOException {
        requests.incrementAndGet();
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String roomId : rooms.keySet()) {
            if (!first) sb.append(",");
            int msgCount = messages.getOrDefault(roomId, new ConcurrentLinkedDeque<>()).size();
            int subCount = rooms.getOrDefault(roomId, Collections.emptySet()).size();
            sb.append("{\"id\":\"").append(escape(roomId)).append("\",\"messages\":").append(msgCount)
                    .append(",\"subscribers\":").append(subCount).append("}");
            first = false;
        }
        sb.append("]");
        json(ex, 200, sb.toString());
    }

    private void stats(HttpExchange ex) throws IOException {
        long totalMessages = messages.values().stream().mapToLong(ConcurrentLinkedDeque::size).sum();
        long jvmThreadCount = Thread.activeCount();
        json(ex, 200, "{\"virtual_threads_submitted\":" + requests.get()
                + ",\"active_rooms\":" + rooms.size()
                + ",\"total_broadcasts\":" + broadcastCount.get()
                + ",\"total_messages\":" + totalMessages
                + ",\"jvm_thread_count\":" + jvmThreadCount + "}");
    }

    private void health(HttpExchange ex) throws IOException {
        json(ex, 200, "{\"status\":\"UP\",\"rooms\":" + rooms.size() + ",\"executor\":\"virtual-threads\"}");
    }

    private void metricsHandler(HttpExchange ex) throws IOException {
        long totalMessages = messages.values().stream().mapToLong(ConcurrentLinkedDeque::size).sum();
        long jvmThreadCount = Thread.activeCount();
        String body = "# TYPE chat_requests_total counter\n"
                + "chat_requests_total " + requests.get() + "\n"
                + "# TYPE chat_broadcasts_total counter\n"
                + "chat_broadcasts_total " + broadcastCount.get() + "\n"
                + "# TYPE chat_rooms_active gauge\n"
                + "chat_rooms_active " + rooms.size() + "\n"
                + "# TYPE chat_messages_total gauge\n"
                + "chat_messages_total " + totalMessages + "\n"
                + "# TYPE jvm_thread_count gauge\n"
                + "jvm_thread_count " + jvmThreadCount + "\n"
                + "# TYPE benchmark_requests_total counter\n"
                + "benchmark_requests_total{benchmark=\"" + benchmark + "\"} " + requests.get() + "\n";
        bytes(ex, 200, "text/plain; version=0.0.4", body);
    }

    private static String field(String body, String name, String fallback) {
        String quoted = "\"" + name + "\"";
        int key = body.indexOf(quoted); if (key < 0) return fallback;
        int colon = body.indexOf(':', key + quoted.length()); if (colon < 0) return fallback;
        int firstQuote = body.indexOf('"', colon + 1); if (firstQuote < 0) return fallback;
        int secondQuote = body.indexOf('"', firstQuote + 1); if (secondQuote < 0) return fallback;
        return body.substring(firstQuote + 1, secondQuote);
    }

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

    private static void json(HttpExchange ex, int status, String body) throws IOException {
        bytes(ex, status, "application/json", body);
    }

    private static void bytes(HttpExchange ex, int status, String contentType, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, data.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(data);
        }
    }

    private void log(String event, String fields) {
        System.out.println("{\"event\":\"" + event + "\",\"benchmark\":\"" + benchmark + "\"," + fields + "}");
    }
}
