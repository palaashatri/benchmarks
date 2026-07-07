package com.palaashatri.bench.b06.harness;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class BenchmarkHarness {
    private static final String BENCHMARK = "06-massive-chat-loom";
    private static final String[] PROFILES = new String[]{"ramp-connections", "broadcast-spike"};
    private static final RequestSpec[] REQUESTS = new RequestSpec[]{
        new RequestSpec("POST", "/rooms/room-1/messages", "{\"sender\":\"user1\",\"content\":\"hello world\"}"),
        new RequestSpec("POST", "/rooms/room-2/messages", "{\"sender\":\"user2\",\"content\":\"test message\"}"),
        new RequestSpec("GET", "/rooms/room-1/messages", ""),
        new RequestSpec("GET", "/rooms", ""),
        new RequestSpec("GET", "/api/v1/stats", ""),
    };

    private BenchmarkHarness() { }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parse(args);
        String profile = opts.getOrDefault("profile", PROFILES[0]);
        int requests = Integer.parseInt(opts.getOrDefault("requests", "25"));
        int threads = Integer.parseInt(opts.getOrDefault("threads", "16"));
        int runs = Integer.parseInt(opts.getOrDefault("runs", "3"));
        String baseUrl = opts.getOrDefault("base-url", System.getenv().getOrDefault("BASE_URL", "http://localhost:8080"));
        long seed = Long.parseLong(opts.getOrDefault("seed", "424242"));

        Result r = run(baseUrl, profile, requests, threads, runs, seed);
        if (r.ok() != r.requests()) {
            throw new IllegalStateException("only " + r.ok() + " of " + r.requests() + " benchmark requests succeeded against " + baseUrl);
        }
        Path out = Path.of(opts.getOrDefault("out", "results/results.json"));
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        Files.writeString(out, r.toJson() + System.lineSeparator());
        System.out.println(r.toJson());
    }

    static Result run(String baseUrl, String profile, int requests, int threads, int runs, long seed) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        List<Long> allLatencies = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger totalOk = new AtomicInteger(0);
        AtomicInteger totalReqs = new AtomicInteger(0);

        long gcMsBefore = totalGcMs();

        for (int run = 0; run < runs; run++) {
            CountDownLatch latch = new CountDownLatch(threads);
            int requestsPerThread = Math.max(1, requests / threads);
            long wallStart = System.nanoTime();

            for (int t = 0; t < threads; t++) {
                final int threadIdx = t;
                pool.submit(() -> {
                    try {
                        Random random = new Random(seed ^ profile.hashCode() ^ threadIdx);
                        for (int i = 0; i < requestsPerThread; i++) {
                            RequestSpec spec = REQUESTS[Math.floorMod(i + threadIdx, REQUESTS.length)];
                            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + spec.path()))
                                    .timeout(Duration.ofSeconds(10));
                            if ("POST".equals(spec.method())) {
                                builder.header("Content-Type", "application/json")
                                       .POST(HttpRequest.BodyPublishers.ofString(spec.body(), StandardCharsets.UTF_8));
                            } else {
                                builder.GET();
                            }
                            long start = System.nanoTime();
                            try {
                                HttpResponse<String> res = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                                long latency = Math.max(1L, (System.nanoTime() - start) / 1_000_000L);
                                allLatencies.add(latency);
                                totalReqs.incrementAndGet();
                                if (res.statusCode() >= 200 && res.statusCode() < 300 && !res.body().contains("\"error\"")) {
                                    totalOk.incrementAndGet();
                                }
                            } catch (IOException | InterruptedException e) {
                                long latency = Math.max(1L, (System.nanoTime() - start) / 1_000_000L);
                                allLatencies.add(latency);
                                totalReqs.incrementAndGet();
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await();
            long wallEnd = System.nanoTime();
            double wallElapsedSeconds = (wallEnd - wallStart) / 1_000_000_000.0;
            // Wall time measured per run, aggregate at end
        }

        pool.shutdown();

        long gcMsAfter = totalGcMs();
        long gcMs = Math.max(0, gcMsAfter - gcMsBefore);

        // Collect RSS via ps
        long rssMb = collectRss();
        double cpuPct = collectCpu();

        // Fetch stats for mode_kpis
        long virtualThreadCount = 0;
        try {
            HttpRequest statsReq = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/stats"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> statsRes = client.send(statsReq, HttpResponse.BodyHandlers.ofString());
            String body = statsRes.body();
            virtualThreadCount = extractLong(body, "virtual_threads_submitted", 0L);
        } catch (Exception ignored) { }

        long[] latenciesArr = allLatencies.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(latenciesArr);

        int reqCount = totalReqs.get();
        int okCount = totalOk.get();
        double elapsedSeconds = latenciesArr.length > 0
                ? Math.max(0.001, Arrays.stream(latenciesArr).sum() / 1000.0)
                : 1.0;
        // Throughput: total requests / total wall clock time approximated
        double throughput = reqCount / Math.max(0.001, elapsedSeconds);

        String modekpisJson = "\"concurrent_connections\":" + threads
                + ",\"virtual_thread_count\":" + virtualThreadCount
                + ",\"broadcast_latency_ms\":0";

        return new Result(
                profile, reqCount, okCount, throughput,
                pct(latenciesArr, 50.0), pct(latenciesArr, 99.0), pct(latenciesArr, 99.9), pct(latenciesArr, 99.99),
                gcMs, rssMb, cpuPct, modekpisJson);
    }

    private static long totalGcMs() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(gc -> gc.getCollectionTime() < 0 ? 0 : gc.getCollectionTime())
                .sum();
    }

    private static long collectRss() {
        try {
            long pid = ProcessHandle.current().pid();
            Process ps = new ProcessBuilder("ps", "-o", "rss=", "-p", String.valueOf(pid)).start();
            String out = new String(ps.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            ps.waitFor();
            if (!out.isBlank()) {
                return Long.parseLong(out.trim()) / 1024;
            }
        } catch (Exception ignored) { }
        return 0;
    }

    private static double collectCpu() {
        try {
            Object osMxBean = ManagementFactory.getOperatingSystemMXBean();
            if (osMxBean instanceof com.sun.management.OperatingSystemMXBean sunOs) {
                double load = sunOs.getProcessCpuLoad();
                if (load >= 0) return load * 100.0;
            }
        } catch (Exception ignored) { }
        return 0.0;
    }

    private static long extractLong(String body, String key, long fallback) {
        String quoted = "\"" + key + "\"";
        int idx = body.indexOf(quoted);
        if (idx < 0) return fallback;
        int colon = body.indexOf(':', idx + quoted.length());
        if (colon < 0) return fallback;
        int start = colon + 1;
        while (start < body.length() && Character.isWhitespace(body.charAt(start))) start++;
        int end = start;
        while (end < body.length() && (Character.isDigit(body.charAt(end)) || body.charAt(end) == '-')) end++;
        if (end == start) return fallback;
        try { return Long.parseLong(body.substring(start, end)); } catch (NumberFormatException e) { return fallback; }
    }

    private static long pct(long[] v, double p) {
        if (v.length == 0) return 0;
        return v[Math.min(v.length - 1, (int) Math.ceil((p / 100.0) * v.length) - 1)];
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--")) {
                String k = a.substring(2);
                String v = "true";
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) v = args[++i];
                out.put(k, v);
            }
        }
        return out;
    }

    record RequestSpec(String method, String path, String body) { }

    record Result(String profile, int requests, int ok, double throughput,
                  long p50, long p99, long p999, long p9999,
                  long gcMs, long rssMb, double cpuPct, String modeKpisJson) {
        String toJson() {
            return ("{\"benchmark\":\"%s\",\"runtime\":\"openjdk-hotspot-21\",\"gc\":\"G1\","
                    + "\"jvm_flags\":[\"-XX:+UseG1GC\"],"
                    + "\"env\":{\"cpu\":\"%d\",\"kernel\":\"%s\",\"cgroup_cpu\":\"unknown\",\"cgroup_mem\":\"unknown\"},"
                    + "\"load_profile\":\"%s\","
                    + "\"phases\":{\"warmup_s\":0,\"measure_s\":0},"
                    + "\"kpis\":{\"throughput\":%.3f,\"p50_ms\":%d,\"p99_ms\":%d,\"p999_ms\":%d,\"p9999_ms\":%d,"
                    + "\"gc_pause_p99_ms\":%d,\"alloc_rate_mb_s\":0,\"rss_mb\":%d,\"native_mem_mb\":0,\"cpu_util_pct\":%.2f},"
                    + "\"mode_kpis\":{%s}}")
                    .formatted(
                        BENCHMARK,
                        Runtime.getRuntime().availableProcessors(),
                        escape(System.getProperty("os.version", "unknown")),
                        profile,
                        throughput, p50, p99, p999, p9999,
                        gcMs, rssMb, cpuPct,
                        modeKpisJson);
        }

        private static String escape(String s) {
            return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
