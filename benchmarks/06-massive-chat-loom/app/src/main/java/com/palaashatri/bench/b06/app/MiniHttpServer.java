package com.palaashatri.bench.b06.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** HTTP room simulator. Persistent-connection fan-out remains future Tier-1 work. */
public final class MiniHttpServer {
    private final String benchmark;
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong messagesPublished = new AtomicLong();
    private final AtomicLong deliveries = new AtomicLong();
    private final AtomicLong ids = new AtomicLong(1);
    private final ConcurrentHashMap<String, Set<String>> subscribers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<String>> messages = new ConcurrentHashMap<>();

    public MiniHttpServer(String benchmark, String ignoredTitle) { this.benchmark = benchmark; }

    public void start(int port) throws IOException {
        seed();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 256);
        server.createContext("/health", this::health);
        server.createContext("/runtime", this::runtime);
        server.createContext("/metrics", this::metrics);
        server.createContext("/api/v1/stats", this::stats);
        server.createContext("/rooms", this::rooms);
        server.createContext("/rooms/", this::room);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.printf("{\"event\":\"started\",\"benchmark\":\"%s\",\"mode\":\"http-room-simulator\",\"port\":%d,\"pid\":%d}%n",
                benchmark, port, ProcessHandle.current().pid());
    }

    private void seed() {
        for (int index = 1; index <= 50; index++) {
            String room = "room-" + index;
            subscribers.computeIfAbsent(room, ignored -> ConcurrentHashMap.newKeySet());
            messages.computeIfAbsent(room, ignored -> new ConcurrentLinkedDeque<>());
        }
    }

    private void health(HttpExchange exchange) throws IOException {
        json(exchange, 200, "{\"status\":\"UP\",\"mode\":\"http-room-simulator\",\"persistent_connections\":false}");
    }
    private void runtime(HttpExchange exchange) throws IOException {
        json(exchange, 200, "{\"pid\":" + ProcessHandle.current().pid() + ",\"run_token\":\""
                + escape(System.getenv().getOrDefault("BENCH_RUN_TOKEN", "")) + "\",\"java_version\":\""
                + escape(System.getProperty("java.version")) + "\"}");
    }

    private void rooms(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            json(exchange, 405, "{\"error\":\"method_not_allowed\"}"); return;
        }
        List<String> values = new ArrayList<>();
        for (String room : subscribers.keySet()) {
            values.add("{\"id\":\"" + escape(room) + "\",\"subscribers\":" + subscribers.get(room).size()
                    + ",\"messages\":" + messages.get(room).size() + "}");
        }
        json(exchange, 200, "[" + String.join(",", values) + "]");
    }

    private void room(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        String[] path = exchange.getRequestURI().getPath().split("/");
        if (path.length != 4) { json(exchange, 404, "{\"error\":\"not_found\"}"); return; }
        String room = path[2], action = path[3];
        subscribers.computeIfAbsent(room, ignored -> ConcurrentHashMap.newKeySet());
        messages.computeIfAbsent(room, ignored -> new ConcurrentLinkedDeque<>());
        if ("subscribers".equals(action)) {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String user = field(body, "user", "");
                if (user.isBlank()) { json(exchange, 400, "{\"error\":\"user_required\"}"); return; }
                subscribers.get(room).add(user);
                json(exchange, 200, "{\"room_id\":\"" + escape(room) + "\",\"subscribers\":" + subscribers.get(room).size() + "}");
            } else {
                json(exchange, 200, "{\"room_id\":\"" + escape(room) + "\",\"subscribers\":" + subscribers.get(room).size() + "}");
            }
            return;
        }
        if (!"messages".equals(action)) { json(exchange, 404, "{\"error\":\"not_found\"}"); return; }
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String sender = field(body, "sender", "anonymous"), content = field(body, "content", "");
            long id = ids.getAndIncrement();
            String message = "{\"message_id\":" + id + ",\"sender\":\"" + escape(sender)
                    + "\",\"content\":\"" + escape(content) + "\"}";
            ConcurrentLinkedDeque<String> queue = messages.get(room); queue.addLast(message);
            while (queue.size() > 100) queue.pollFirst();
            long delivered = subscribers.get(room).size();
            messagesPublished.incrementAndGet(); deliveries.addAndGet(delivered);
            json(exchange, 200, "{\"room_id\":\"" + escape(room) + "\",\"message_id\":" + id
                    + ",\"delivered\":" + delivered + ",\"delivery_model\":\"simulated\"}");
            return;
        }
        json(exchange, 200, "[" + String.join(",", messages.get(room)) + "]");
    }

    private void stats(HttpExchange exchange) throws IOException {
        json(exchange, 200, "{\"active_rooms\":" + subscribers.size() + ",\"subscribers\":"
                + subscribers.values().stream().mapToLong(Set::size).sum() + ",\"messages_published\":"
                + messagesPublished.get() + ",\"simulated_deliveries\":" + deliveries.get()
                + ",\"persistent_connections\":0}");
    }
    private void metrics(HttpExchange exchange) throws IOException {
        String body = "# TYPE chat_messages_published_total counter\nchat_messages_published_total " + messagesPublished.get() + "\n"
                + "# TYPE chat_simulated_deliveries_total counter\nchat_simulated_deliveries_total " + deliveries.get() + "\n"
                + "# TYPE chat_persistent_connections gauge\nchat_persistent_connections 0\n"
                + "# TYPE benchmark_requests_total counter\nbenchmark_requests_total{benchmark=\"" + benchmark + "\"} " + requests.get() + "\n";
        bytes(exchange, 200, "text/plain; version=0.0.4", body);
    }

    private static String field(String body, String name, String fallback) {
        String key="\""+name+"\"";int at=body.indexOf(key);if(at<0)return fallback;int colon=body.indexOf(':',at+key.length());int start=body.indexOf('"',colon+1),end=start<0?-1:body.indexOf('"',start+1);return colon<0||start<0||end<0?fallback:body.substring(start+1,end);
    }
    private static String escape(String value){return value==null?"":value.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n");}
    private static void json(HttpExchange exchange,int status,String body)throws IOException{bytes(exchange,status,"application/json",body);}
    private static void bytes(HttpExchange exchange,int status,String type,String body)throws IOException{byte[] payload=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type",type);exchange.sendResponseHeaders(status,payload.length);try(OutputStream output=exchange.getResponseBody()){output.write(payload);}}
}
