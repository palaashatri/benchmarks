package com.palaashatri.bench.b02.harness;

import com.sun.management.OperatingSystemMXBean;
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
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class BenchmarkHarness {
    private static final String BENCHMARK = "02-microservices-mesh";
    private static final String MODE_KPIS_JSON = "\"inter_service_p99_ms\":0,\"circuit_open_count\":0,\"mesh_overhead_ms\":0";
    private static final String[] PROFILES = new String[]{"ramp", "storm", "partial-failure"};

    /**
     * Request specs for the mesh endpoints. All must return 200 without "error"
     * in the body for the smoke test to pass.
     */
    private static final RequestSpec[] REQUESTS = new RequestSpec[]{
        new RequestSpec("GET",  "/api/v1/users/1001", ""),
        new RequestSpec("GET",  "/api/v1/users/1002", ""),
        new RequestSpec("POST", "/api/v1/orders",
                "{\"from_id\":\"1001\",\"item\":\"widget\",\"amount\":100}"),
        new RequestSpec("GET",  "/api/v1/health", ""),
        new RequestSpec("GET",  "/metrics", "")
    };

    private BenchmarkHarness() { }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parse(args);
        String profile  = opts.getOrDefault("profile",  PROFILES[0]);
        int requests    = Integer.parseInt(opts.getOrDefault("requests", "25"));
        int threads     = Integer.parseInt(opts.getOrDefault("threads",  "8"));
        int runs        = Integer.parseInt(opts.getOrDefault("runs",     "3"));
        String baseUrl  = opts.getOrDefault("base-url",
                System.getenv().getOrDefault("BASE_URL", "http://localhost:8080"));
        long seed       = Long.parseLong(opts.getOrDefault("seed", "424242"));

        Result r = run(baseUrl, profile, requests, threads, seed);
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
            throws InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();

        int total = Math.max(1, requests);
        long[] latenciesMs = new long[total];
        AtomicInteger ok = new AtomicInteger(0);
        AtomicLong taskIdx = new AtomicLong(0);

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(threads, total));
        CountDownLatch latch = new CountDownLatch(total);

        long wallStart = System.nanoTime();

        for (int i = 0; i < total; i++) {
            final int index = i;
            pool.submit(() -> {
                try {
                    long ti = taskIdx.getAndIncrement();
                    RequestSpec spec = REQUESTS[(int) Math.floorMod(ti, REQUESTS.length)];
                    HttpRequest.Builder builder = HttpRequest.newBuilder(
                            URI.create(baseUrl + spec.path()))
                            .timeout(Duration.ofSeconds(5));
                    if ("POST".equals(spec.method())) {
                        builder.header("Content-Type", "application/json")
                               .POST(HttpRequest.BodyPublishers.ofString(
                                       spec.body(), StandardCharsets.UTF_8));
                    } else {
                        builder.GET();
                    }
                    long start = System.nanoTime();
                    try {
                        HttpResponse<String> res = client.send(
                                builder.build(), HttpResponse.BodyHandlers.ofString());
                        if (res.statusCode() >= 200 && res.statusCode() < 300
                                && !res.body().contains("\"error\"")) {
                            ok.incrementAndGet();
                        }
                    } catch (IOException e) {
                        // connection failure counts as failed request
                    }
                    latenciesMs[index] = Math.max(1L, (System.nanoTime() - start) / 1_000_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        pool.shutdown();

        long wallNs = System.nanoTime() - wallStart;
        double wallSeconds = Math.max(0.001, wallNs / 1_000_000_000.0);
        double throughput = total / wallSeconds;

        Arrays.sort(latenciesMs);

        // Collect GC time
        long gcMs = ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(GarbageCollectorMXBean::getCollectionTime)
                .filter(t -> t >= 0)
                .sum();

        // CPU load
        double cpuUtil = 0.0;
        try {
            java.lang.management.OperatingSystemMXBean os =
                    ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof OperatingSystemMXBean sunOs) {
                cpuUtil = sunOs.getProcessCpuLoad() * 100.0;
                if (cpuUtil < 0) cpuUtil = 0.0;
            }
        } catch (Exception ignored) { }

        // RSS via ps
        long rssMb = 0;
        try {
            long pid = ProcessHandle.current().pid();
            Process ps = new ProcessBuilder("ps", "-o", "rss=", "-p", String.valueOf(pid)).start();
            String rssStr = new String(ps.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!rssStr.isEmpty()) rssMb = Long.parseLong(rssStr) / 1024L;
        } catch (Exception ignored) { }

        int cpuCount = Runtime.getRuntime().availableProcessors();
        String kernel = System.getProperty("os.version", "unknown");

        return new Result(profile, total, ok.get(), throughput,
                pct(latenciesMs, 50.0), pct(latenciesMs, 99.0),
                pct(latenciesMs, 99.9), pct(latenciesMs, 99.99),
                gcMs, rssMb, cpuUtil, cpuCount, kernel);
    }

    // ── utilities ──────────────────────────────────────────────────────────────

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

    // ── data types ─────────────────────────────────────────────────────────────

    record RequestSpec(String method, String path, String body) { }

    record Result(
            String profile,
            int requests,
            int ok,
            double throughput,
            long p50,
            long p99,
            long p999,
            long p9999,
            long gcPauseP99Ms,
            long rssMb,
            double cpuUtilPct,
            int cpuCount,
            String kernel
    ) {
        String toJson() {
            return ("{\"benchmark\":\"%s\","
                    + "\"runtime\":\"openjdk-hotspot-21\","
                    + "\"gc\":\"G1\","
                    + "\"jvm_flags\":[\"-XX:+UseG1GC\"],"
                    + "\"env\":{"
                    +   "\"cpu\":\"%d\","
                    +   "\"kernel\":\"%s\","
                    +   "\"cgroup_cpu\":\"unknown\","
                    +   "\"cgroup_mem\":\"unknown\""
                    + "},"
                    + "\"load_profile\":\"%s\","
                    + "\"phases\":{\"warmup_s\":0,\"measure_s\":0},"
                    + "\"kpis\":{"
                    +   "\"throughput\":%.3f,"
                    +   "\"p50_ms\":%d,"
                    +   "\"p99_ms\":%d,"
                    +   "\"p999_ms\":%d,"
                    +   "\"p9999_ms\":%d,"
                    +   "\"gc_pause_p99_ms\":%d,"
                    +   "\"alloc_rate_mb_s\":0,"
                    +   "\"rss_mb\":%d,"
                    +   "\"native_mem_mb\":0,"
                    +   "\"cpu_util_pct\":%.1f"
                    + "},"
                    + "\"mode_kpis\":{%s}}")
                    .formatted(
                            BENCHMARK,
                            cpuCount,
                            escape(kernel),
                            profile,
                            throughput,
                            p50, p99, p999, p9999,
                            gcPauseP99Ms,
                            rssMb,
                            cpuUtilPct,
                            MODE_KPIS_JSON
                    );
        }

        private static String escape(String s) {
            return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
