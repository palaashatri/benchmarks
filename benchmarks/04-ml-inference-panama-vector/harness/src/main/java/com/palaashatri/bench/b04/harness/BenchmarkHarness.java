package com.palaashatri.bench.b04.harness;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class BenchmarkHarness {
    private static final String BENCHMARK = "04-ml-inference-panama-vector";
    private static final String MODE_KPIS_JSON =
            "\"vector_ops_per_ms\":0,\"scalar_baseline_ms\":0,\"simd_speedup_ratio\":0";
    private static final String[] PROFILES = {"micro", "macro-single", "macro-batch"};
    private static final RequestSpec[] REQUESTS = {
        new RequestSpec("POST", "/api/v1/inference",
                "{\"features\":[0.1,0.2,0.3,0.4,0.5,0.6,0.7,0.8,0.9,1.0,0.1,0.2,0.3,0.4,0.5,0.6]}"),
        new RequestSpec("POST", "/api/v1/inference",
                "{\"features\":[1.0,0.9,0.8,0.7,0.6,0.5,0.4,0.3,0.2,0.1,0.0,0.1,0.2,0.3,0.4,0.5]}"),
        new RequestSpec("POST", "/api/v1/inference/scalar",
                "{\"features\":[0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5]}"),
        new RequestSpec("GET", "/api/v1/health", "{}"),
        new RequestSpec("GET", "/metrics", "{}"),
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

        Result r = null;
        for (int run = 0; run < runs; run++) {
            r = run(baseUrl, profile, requests, seed + run, threads, runs);
        }
        if (r == null) throw new IllegalStateException("No runs completed");
        if (r.ok() != r.requests()) {
            throw new IllegalStateException("only " + r.ok() + " of " + r.requests()
                    + " benchmark requests succeeded against " + baseUrl);
        }
        Path out = Path.of(opts.getOrDefault("out", "results/results.json"));
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        Files.writeString(out, r.toJson() + System.lineSeparator());
        System.out.println(r.toJson());
    }

    static Result run(String baseUrl, String profile, int requests, long seed,
                      int threads, int runs) throws InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
        Random random = new Random(seed ^ profile.hashCode());
        int total = Math.max(1, requests);
        long[] latenciesMs = new long[total];
        AtomicInteger okCount = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(total);

        long wallStart = System.nanoTime();

        for (int i = 0; i < total; i++) {
            final int idx = i;
            final RequestSpec spec = REQUESTS[Math.floorMod(i, REQUESTS.length)];
            pool.submit(() -> {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + spec.path()))
                        .timeout(Duration.ofSeconds(10));
                if ("POST".equals(spec.method())) {
                    builder.header("Content-Type", "application/json")
                           .POST(HttpRequest.BodyPublishers.ofString(spec.body(), StandardCharsets.UTF_8));
                } else {
                    builder.GET();
                }
                long t0 = System.nanoTime();
                try {
                    HttpResponse<String> res = client.send(builder.build(),
                            HttpResponse.BodyHandlers.ofString());
                    if (res.statusCode() >= 200 && res.statusCode() < 300
                            && !res.body().contains("\"error\"")) {
                        okCount.incrementAndGet();
                    }
                } catch (IOException | InterruptedException e) {
                    // failure counted as non-ok
                }
                latenciesMs[idx] = Math.max(1L, (System.nanoTime() - t0) / 1_000_000L);
                latch.countDown();
            });
        }

        latch.await();
        long wallEnd = System.nanoTime();
        pool.shutdown();

        double wallSec = (wallEnd - wallStart) / 1e9;
        double throughput = total / wallSec;

        Arrays.sort(latenciesMs);

        // Collect GC time
        long gcMs = ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(GarbageCollectorMXBean::getCollectionTime)
                .filter(t -> t >= 0)
                .sum();

        // Collect RSS (in MB) via ps
        long rssMb = rssFromPs();

        // Collect CPU utilisation
        double cpuUtil = cpuUtilPct();

        return new Result(profile, total, okCount.get(), throughput,
                pct(latenciesMs, 50.0), pct(latenciesMs, 99.0),
                pct(latenciesMs, 99.9), pct(latenciesMs, 99.99),
                gcMs, rssMb, cpuUtil);
    }

    // -----------------------------------------------------------------------
    // System metrics helpers
    // -----------------------------------------------------------------------

    private static long rssFromPs() {
        try {
            long pid = ProcessHandle.current().pid();
            Process p = new ProcessBuilder("ps", "-o", "rss=", "-p", String.valueOf(pid))
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor();
            return Long.parseLong(out) / 1024L; // KB -> MB
        } catch (Exception e) {
            return 0L;
        }
    }

    private static double cpuUtilPct() {
        try {
            com.sun.management.OperatingSystemMXBean osBean =
                    (com.sun.management.OperatingSystemMXBean)
                    ManagementFactory.getOperatingSystemMXBean();
            double load = osBean.getProcessCpuLoad();
            return load < 0 ? 0.0 : load * 100.0;
        } catch (Exception e) {
            return 0.0;
        }
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

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
                  long gcMs, long rssMb, double cpuUtil) {
        String toJson() {
            return """
                    {"benchmark":"%s","runtime":"openjdk-hotspot-21","gc":"G1","jvm_flags":["-XX:+UseG1GC"],\
"env":{"cpu":"%d","kernel":"%s","cgroup_cpu":"unknown","cgroup_mem":"unknown"},\
"load_profile":"%s","phases":{"warmup_s":0,"measure_s":0},\
"kpis":{"throughput":%.3f,"p50_ms":%d,"p99_ms":%d,"p999_ms":%d,"p9999_ms":%d,\
"gc_pause_p99_ms":%d,"alloc_rate_mb_s":0,"rss_mb":%d,"native_mem_mb":0,"cpu_util_pct":%.2f},\
"mode_kpis":{%s}}""".formatted(
                    BENCHMARK,
                    Runtime.getRuntime().availableProcessors(),
                    System.getProperty("os.version", "unknown"),
                    profile,
                    throughput, p50, p99, p999, p9999,
                    gcMs, rssMb, cpuUtil,
                    MODE_KPIS_JSON
            );
        }
    }
}
