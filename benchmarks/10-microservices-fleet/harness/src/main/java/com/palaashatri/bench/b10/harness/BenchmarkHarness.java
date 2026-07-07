package com.palaashatri.bench.b10.harness;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class BenchmarkHarness {
    private static final String BENCHMARK = "10-microservices-fleet";
    private static final String[] PROFILES = new String[]{"rolling-deploy", "steady-state", "failure-injection"};
    private static final RequestSpec[] REQUESTS = new RequestSpec[]{
        new RequestSpec("GET",  "/api/v1/fleet/status",             ""),
        new RequestSpec("GET",  "/api/v1/service/0/inventory/item-5",  ""),
        new RequestSpec("GET",  "/api/v1/service/1/inventory/item-10", ""),
        new RequestSpec("POST", "/api/v1/fleet/deploy/2",           "{}"),
        new RequestSpec("GET",  "/health",                           "")
    };

    private BenchmarkHarness() { }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parse(args);
        String profile  = opts.getOrDefault("profile",   PROFILES[0]);
        int requests    = Integer.parseInt(opts.getOrDefault("requests", "25"));
        int threads     = Integer.parseInt(opts.getOrDefault("threads",  "8"));
        int runs        = Integer.parseInt(opts.getOrDefault("runs",     "3"));
        String baseUrl  = opts.getOrDefault("base-url",
                System.getenv().getOrDefault("BASE_URL", "http://localhost:8080"));
        long seed = Long.parseLong(opts.getOrDefault("seed", "424242"));

        Result r = null;
        for (int i = 0; i < runs; i++) {
            r = run(baseUrl, profile, requests, threads, seed);
        }
        // r is non-null because runs >= 1
        assert r != null;
        if (r.ok() != r.requests()) {
            throw new IllegalStateException(
                    "only " + r.ok() + " of " + r.requests()
                    + " benchmark requests succeeded against " + baseUrl);
        }
        Path out = Path.of(opts.getOrDefault("out", "results/results.json"));
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        Files.writeString(out, r.toJson() + System.lineSeparator());
        System.out.println(r.toJson());
    }

    static Result run(String baseUrl, String profile, int requests, int threads, long seed)
            throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        Random random = new Random(seed ^ profile.hashCode());

        long[] latenciesMs = new long[Math.max(1, requests)];
        AtomicInteger ok = new AtomicInteger();
        AtomicLong deployTimeAccum = new AtomicLong();
        CountDownLatch latch = new CountDownLatch(latenciesMs.length);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        long wallStart = System.nanoTime();
        for (int i = 0; i < latenciesMs.length; i++) {
            final int idx = i;
            // choose spec deterministically but spread across request types
            final RequestSpec spec = REQUESTS[Math.floorMod(idx, REQUESTS.length)];
            pool.submit(() -> {
                HttpRequest.Builder builder = HttpRequest
                        .newBuilder(URI.create(baseUrl + spec.path()))
                        .timeout(Duration.ofSeconds(5));
                if ("POST".equals(spec.method())) {
                    builder.header("Content-Type", "application/json")
                           .POST(HttpRequest.BodyPublishers.ofString(spec.body(), StandardCharsets.UTF_8));
                } else {
                    builder.GET();
                }
                long start = System.nanoTime();
                try {
                    HttpResponse<String> res = client.send(
                            builder.build(), HttpResponse.BodyHandlers.ofString());
                    long latMs = Math.max(1L, (System.nanoTime() - start) / 1_000_000L);
                    latenciesMs[idx] = latMs;
                    boolean success = res.statusCode() >= 200 && res.statusCode() < 300
                            && !res.body().contains("\"error\"");
                    if (success) {
                        ok.incrementAndGet();
                        // extract deploy_time_ms from deploy responses
                        if (spec.path().contains("/fleet/deploy/")) {
                            long dt = extractLong(res.body(), "deploy_time_ms", 0L);
                            deployTimeAccum.addAndGet(dt);
                        }
                    }
                } catch (IOException | InterruptedException e) {
                    latenciesMs[idx] = 1L;
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        pool.shutdown();
        long wallEnd = System.nanoTime();
        double wallSec = Math.max(0.001, (wallEnd - wallStart) / 1_000_000_000.0);
        double throughput = latenciesMs.length / wallSec;

        Arrays.sort(latenciesMs);

        // GC time
        long gcMs = ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(GarbageCollectorMXBean::getCollectionTime)
                .filter(t -> t >= 0)
                .sum();

        // RSS
        long rssMb = 0L;
        try {
            long pid = ProcessHandle.current().pid();
            Process proc = new ProcessBuilder("ps", "-o", "rss=", "-p", String.valueOf(pid))
                    .redirectErrorStream(true)
                    .start();
            String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            rssMb = Long.parseLong(out) / 1024L;
        } catch (Exception ignored) { }

        // CPU
        double cpuPct = 0.0;
        try {
            java.lang.management.OperatingSystemMXBean osBean =
                    ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOs) {
                cpuPct = sunOs.getProcessCpuLoad() * 100.0;
            }
        } catch (Exception ignored) { }

        // env string
        String env = "\"cpu\":\"" + Runtime.getRuntime().availableProcessors()
                + "\",\"kernel\":\"" + System.getProperty("os.version", "unknown")
                + "\",\"cgroup_cpu\":\"unknown\",\"cgroup_mem\":\"unknown\"";

        long deployTimeMs = deployTimeAccum.get();

        return new Result(profile, latenciesMs.length, ok.get(), throughput,
                pct(latenciesMs, 50.0), pct(latenciesMs, 99.0),
                pct(latenciesMs, 99.9), pct(latenciesMs, 99.99),
                gcMs, rssMb, (long) cpuPct, env, deployTimeMs);
    }

    private static long extractLong(String body, String name, long fallback) {
        String key = "\"" + name + "\"";
        int idx = body.indexOf(key);
        if (idx < 0) return fallback;
        int colon = body.indexOf(':', idx + key.length());
        if (colon < 0) return fallback;
        int start = colon + 1;
        while (start < body.length() && Character.isWhitespace(body.charAt(start))) start++;
        int end = start;
        while (end < body.length() && (Character.isDigit(body.charAt(end)) || body.charAt(end) == '-')) end++;
        if (end == start) return fallback;
        try { return Long.parseLong(body.substring(start, end)); } catch (NumberFormatException e) { return fallback; }
    }

    private static long pct(long[] v, double p) {
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
                  long gcMs, long rssMb, long cpuPct, String env, long deployTimeMs) {
        String toJson() {
            return """
                    {"benchmark":"%s","runtime":"openjdk-hotspot-17","gc":"G1","jvm_flags":["-XX:+UseG1GC"],\
"env":{%s},"load_profile":"%s","phases":{"warmup_s":0,"measure_s":0},\
"kpis":{"throughput":%.3f,"p50_ms":%d,"p99_ms":%d,"p999_ms":%d,"p9999_ms":%d,\
"gc_pause_p99_ms":%d,"alloc_rate_mb_s":0,"rss_mb":%d,"native_mem_mb":0,"cpu_util_pct":%d},\
"mode_kpis":{"deploy_time_ms":%d,"availability_pct":0,"p99_during_deploy_ms":0}}"""
                    .formatted(BENCHMARK, env, profile, throughput,
                            p50, p99, p999, p9999,
                            gcMs, rssMb, cpuPct, deployTimeMs);
        }
    }
}
