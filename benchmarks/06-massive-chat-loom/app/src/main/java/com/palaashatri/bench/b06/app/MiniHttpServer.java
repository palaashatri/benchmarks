package com.palaashatri.bench.b06.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public final class MiniHttpServer {
    private final String benchmark;
    private final String title;
    private final AtomicLong requests = new AtomicLong();

    public MiniHttpServer(String benchmark, String title) {
        this.benchmark = benchmark;
        this.title = title;
    }

    public void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 128);
        server.createContext("/health", this::health);
        server.createContext("/metrics", this::metrics);
        server.createContext("/actuator/health", this::health);
        server.createContext("/actuator/prometheus", this::metrics);
        server.createContext("/", this::route);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("""
                {"event":"started","benchmark":"%s","port":%d}
                """.formatted(benchmark, port).trim());
    }

    private void health(HttpExchange ex) throws IOException {
        json(ex, 200, """
                {"status":"UP","benchmark":"%s"}
                """.formatted(benchmark).trim());
    }

    private void metrics(HttpExchange ex) throws IOException {
        String body = """
                # TYPE benchmark_requests_total counter
                benchmark_requests_total{benchmark="%s"} %d
                # TYPE jvm_available_processors gauge
                jvm_available_processors %d
                """.formatted(benchmark, requests.get(), Runtime.getRuntime().availableProcessors());
        bytes(ex, 200, "text/plain; version=0.0.4", body);
    }

    private void route(HttpExchange ex) throws IOException {
        long n = requests.incrementAndGet();
        String path = ex.getRequestURI().getPath();
        long work = deterministicWork(path, n);
        String body = """
                {"benchmark":"%s","service":"%s","path":"%s","request":%d,"checksum":%d,"ts":"%s"}
                """.formatted(benchmark, escape(title), escape(path), n, work, Instant.now()).trim();
        json(ex, 200, body);
    }

    private static long deterministicWork(String path, long seed) {
        long x = seed ^ path.hashCode();
        for (int i = 0; i < 512; i++) {
            x = (x * 2862933555777941757L) + 3037000493L;
        }
        return x;
    }

    private static String escape(String in) {
        StringBuilder out = new StringBuilder(in.length());
        for (int i = 0; i < in.length(); i++) {
            char c = in.charAt(i);
            if (c == 92) {
                out.append((char) 92).append((char) 92);
            } else if (c == 34) {
                out.append((char) 92).append((char) 34);
            } else {
                out.append(c);
            }
        }
        return out.toString();
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
