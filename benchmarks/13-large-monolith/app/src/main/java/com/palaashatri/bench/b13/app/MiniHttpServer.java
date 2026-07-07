package com.palaashatri.bench.b13.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class MiniHttpServer {

    static final class SimpleBean {
        private final int id;
        private final String name;
        SimpleBean(int id, String name) { this.id = id; this.name = name; }
        int getId() { return id; }
        String getName() { return name; }
    }

    private final String benchmark;
    private final String title;
    final long startMs = System.currentTimeMillis();
    final List<SimpleBean> beans = new ArrayList<>();
    final AtomicLong requests = new AtomicLong();
    final long[] throughputSamples = new long[120];
    final AtomicInteger sampleIdx = new AtomicInteger(0);
    volatile long compiledMethodsAtPeak = 0;
    volatile long timeToNinetyPct = 0;
    volatile long peakTps = 0;
    volatile long startupMsRecorded = -1;

    // nameHash map used to simulate JIT pressure during bean generation
    private final Map<Integer, Long> beanNameHashes = new HashMap<>();

    public MiniHttpServer(String benchmark, String title) {
        this.benchmark = benchmark;
        this.title = title;
        generateBeans();
        startSampler();
    }

    private void generateBeans() {
        for (int i = 1; i <= 500; i++) {
            String name = "SimpleBean-" + i;
            SimpleBean b = new SimpleBean(i, name);
            beans.add(b);
            // simulate JIT compilation pressure: compute hash and store
            long h = 1125899906842597L ^ i;
            for (int j = 0; j < name.length(); j++) {
                h = 31L * h + name.charAt(j);
            }
            beanNameHashes.put(i, Math.floorMod(h, 1_000_000_007L));
        }
    }

    private void startSampler() {
        long[] prev = {0L};
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                long cur = requests.get();
                long tps = cur - prev[0];
                prev[0] = cur;
                int idx = sampleIdx.getAndIncrement();
                throughputSamples[idx % 120] = tps;
                if (tps > peakTps) {
                    peakTps = tps;
                    compiledMethodsAtPeak = ManagementFactory.getCompilationMXBean().getTotalCompilationTime();
                }
                if (timeToNinetyPct == 0 && peakTps > 0 && tps >= peakTps * 9 / 10) {
                    timeToNinetyPct = System.currentTimeMillis() - startMs;
                }
            }
        });
        t.setDaemon(true);
        t.setName("throughput-sampler");
        t.start();
    }

    public void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 256);
        server.createContext("/health", this::handleHealth);
        server.createContext("/metrics", this::handleMetrics);
        server.createContext("/api/", this::handleApi);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        log("started", "\"port\":" + port + ",\"beans_loaded\":" + beans.size());
    }

    private void recordFirstRequest() {
        if (startupMsRecorded < 0) {
            synchronized (this) {
                if (startupMsRecorded < 0) {
                    startupMsRecorded = System.currentTimeMillis() - startMs;
                }
            }
        }
    }

    private void handleHealth(HttpExchange ex) throws IOException {
        long sm = startupMsRecorded >= 0 ? startupMsRecorded : (System.currentTimeMillis() - startMs);
        json(ex, 200, "{\"status\":\"UP\",\"beans_loaded\":500,\"startup_ms\":" + sm + "}");
    }

    private void handleMetrics(HttpExchange ex) throws IOException {
        long jitMs = ManagementFactory.getCompilationMXBean().getTotalCompilationTime();
        int loadedClasses = ManagementFactory.getClassLoadingMXBean().getLoadedClassCount();
        long sm = startupMsRecorded >= 0 ? startupMsRecorded : (System.currentTimeMillis() - startMs);
        String body = "# TYPE monolith_beans_loaded gauge\n"
                + "monolith_beans_loaded 500\n"
                + "# TYPE monolith_startup_ms gauge\n"
                + "monolith_startup_ms " + sm + "\n"
                + "# TYPE monolith_jit_compilation_ms gauge\n"
                + "monolith_jit_compilation_ms " + jitMs + "\n"
                + "# TYPE monolith_loaded_classes gauge\n"
                + "monolith_loaded_classes " + loadedClasses + "\n"
                + "# TYPE monolith_requests_total counter\n"
                + "monolith_requests_total " + requests.get() + "\n"
                + "# TYPE benchmark_requests_total counter\n"
                + "benchmark_requests_total{benchmark=\"" + benchmark + "\"} " + requests.get() + "\n";
        bytes(ex, 200, "text/plain; version=0.0.4", body);
    }

    private void handleApi(HttpExchange ex) throws IOException {
        requests.incrementAndGet();
        recordFirstRequest();
        String path = ex.getRequestURI().getPath();
        if (path.equals("/api/v1/monolith/health")) {
            handleMonolithHealth(ex);
        } else if (path.equals("/api/v1/monolith/warmup/status")) {
            handleWarmupStatus(ex);
        } else {
            json(ex, 404, "{\"error\":\"no route for " + escape(path) + "\",\"benchmark\":\"" + benchmark + "\"}");
        }
    }

    private void handleMonolithHealth(HttpExchange ex) throws IOException {
        long sm = startupMsRecorded >= 0 ? startupMsRecorded : (System.currentTimeMillis() - startMs);
        json(ex, 200, "{\"status\":\"UP\",\"beans_loaded\":500,\"startup_ms\":" + sm + "}");
    }

    private void handleWarmupStatus(HttpExchange ex) throws IOException {
        long now = System.currentTimeMillis();
        long sm = startupMsRecorded >= 0 ? startupMsRecorded : (now - startMs);
        long uptimeMs = now - startMs;
        long jitMs = ManagementFactory.getCompilationMXBean().getTotalCompilationTime();
        int loadedClasses = ManagementFactory.getClassLoadingMXBean().getLoadedClassCount();
        int si = sampleIdx.get();
        long curTps = si > 0 ? throughputSamples[(si - 1) % 120] : 0L;
        long t90s = timeToNinetyPct / 1000;
        String body = "{\"beans_loaded\":500"
                + ",\"startup_ms\":" + sm
                + ",\"uptime_ms\":" + uptimeMs
                + ",\"time_to_first_response_ms\":" + sm
                + ",\"time_to_90pct_s\":" + t90s
                + ",\"compiled_methods\":" + jitMs
                + ",\"jit_compilation_ms\":" + jitMs
                + ",\"loaded_classes\":" + loadedClasses
                + ",\"current_tps\":" + curTps
                + ",\"peak_tps\":" + peakTps
                + "}";
        json(ex, 200, body);
    }

    private static String escape(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"') { out.append("\\\""); }
            else if (c == '\\') { out.append("\\\\"); }
            else if (c == '\n') { out.append("\\n"); }
            else if (c == '\r') { out.append("\\r"); }
            else if (c == '\t') { out.append("\\t"); }
            else if (c < 0x20) { out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c)); }
            else { out.append(c); }
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
        try (OutputStream out = ex.getResponseBody()) { out.write(data); }
    }

    private void log(String event, String fields) {
        System.out.println("{\"event\":\"" + event + "\",\"benchmark\":\"" + benchmark + "\"," + fields + "}");
    }
}
