package com.palaashatri.bench.b05.harness;

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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class BenchmarkHarness {
    private static final String BENCHMARK = "05-polyglot-service";
    private static final String MODE_KPIS_JSON = "\"script_compile_ms\":0,\"script_exec_ms\":0,\"interop_overhead_ms\":0";
    private static final String[] PROFILES = new String[]{"baseline", "polyglot"};
    private static final RequestSpec[] REQUESTS = new RequestSpec[]{
        new RequestSpec("POST", "/api/v1/score", "{\"script\":\"function score(data){return data.value*2.0;}score(data);\",\"data\":{\"value\":42.5}}"),
        new RequestSpec("POST", "/api/v1/score/rule/1", "{\"amount\":750}"),
        new RequestSpec("POST", "/api/v1/score/rule/2", "{\"age\":30,\"income\":80000}"),
        new RequestSpec("POST", "/api/v1/score/rule/3", "{\"text\":\"normal processing request\"}"),
        new RequestSpec("GET", "/api/v1/scripts", "{}")
    };

    private BenchmarkHarness() { }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parse(args);
        String profile = opts.getOrDefault("profile", PROFILES[0]);
        int requests = Integer.parseInt(opts.getOrDefault("requests", "25"));
        String baseUrl = opts.getOrDefault("base-url", System.getenv().getOrDefault("BASE_URL", "http://localhost:8080"));
        long seed = Long.parseLong(opts.getOrDefault("seed", "424242"));
        int threads = Integer.parseInt(opts.getOrDefault("threads", "8"));
        int runs = Integer.parseInt(opts.getOrDefault("runs", "3"));
        Result r = run(baseUrl, profile, requests, seed, threads, runs);
        if (r.ok() != r.requests()) {
            throw new IllegalStateException("only " + r.ok() + " of " + r.requests() + " benchmark requests succeeded against " + baseUrl);
        }
        Path out = Path.of(opts.getOrDefault("out", "results/results.json"));
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        Files.writeString(out, r.toJson() + System.lineSeparator());
        System.out.println(r.toJson());
        System.exit(0);
    }

    static Result run(String baseUrl, String profile, int requests, long seed, int threads, int runs) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newFixedThreadPool(threads))
                .build();
        Random random = new Random(seed ^ profile.hashCode());

        long[] latenciesMs = new long[Math.max(1, requests)];
        AtomicInteger ok = new AtomicInteger(0);
        AtomicLong latencyStore = new AtomicLong(0);

        // Capture GC time before run
        long gcMsBefore = totalGcMs();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(requests);
        long wallStart = System.nanoTime();

        for (int i = 0; i < requests; i++) {
            final int idx = i;
            final RequestSpec spec = REQUESTS[Math.floorMod(idx + random.nextInt(REQUESTS.length), REQUESTS.length)];
            pool.submit(() -> {
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
                    latenciesMs[idx] = latency;
                    if (res.statusCode() >= 200 && res.statusCode() < 300 && !res.body().contains("\"error\"")) {
                        ok.incrementAndGet();
                    }
                } catch (IOException | InterruptedException e) {
                    latenciesMs[idx] = Math.max(1L, (System.nanoTime() - start) / 1_000_000L);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(60, TimeUnit.SECONDS);
        long wallEnd = System.nanoTime();
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        double wallSec = Math.max(0.001, (wallEnd - wallStart) / 1e9);
        double throughput = requests / wallSec;

        long gcMsAfter = totalGcMs();
        long gcPauseMs = Math.max(0, gcMsAfter - gcMsBefore);

        // Collect RSS via ps
        long rssMb = collectRssMb();

        // Collect CPU util
        double cpuUtil = collectCpuUtil();

        Arrays.sort(latenciesMs);
        String kernel = System.getProperty("os.version", "unknown");
        int cpus = Runtime.getRuntime().availableProcessors();

        return new Result(profile, requests, ok.get(), throughput,
                pct(latenciesMs, 50.0), pct(latenciesMs, 99.0),
                pct(latenciesMs, 99.9), pct(latenciesMs, 99.99),
                gcPauseMs, rssMb, cpuUtil, cpus, kernel);
    }

    private static long totalGcMs() {
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        return gcBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).filter(t -> t >= 0).sum();
    }

    private static long collectRssMb() {
        try {
            long pid = ProcessHandle.current().pid();
            Process ps = new ProcessBuilder("ps", "-o", "rss=", "-p", String.valueOf(pid))
                    .redirectErrorStream(true)
                    .start();
            String out = new String(ps.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            ps.waitFor(3, TimeUnit.SECONDS);
            if (!out.isEmpty()) return Long.parseLong(out.trim()) / 1024;
        } catch (Exception ignored) { }
        return 0;
    }

    private static double collectCpuUtil() {
        try {
            java.lang.management.OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOsBean) {
                double load = sunOsBean.getCpuLoad();
                if (load >= 0) return load * 100.0;
            }
        } catch (Exception ignored) { }
        return 0.0;
    }

    private static long pct(long[] v, double p) {
        return v[Math.min(v.length - 1, Math.max(0, (int) Math.ceil((p / 100.0) * v.length) - 1))];
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
                  long gcPauseMs, long rssMb, double cpuUtil, int cpus, String kernel) {
        String toJson() {
            return ("{\"benchmark\":\"%s\",\"runtime\":\"openjdk-hotspot-21\",\"gc\":\"G1\","
                    + "\"jvm_flags\":[\"-XX:+UseG1GC\"],"
                    + "\"env\":{\"cpu\":\"%d\",\"kernel\":\"%s\",\"cgroup_cpu\":\"unknown\",\"cgroup_mem\":\"unknown\"},"
                    + "\"load_profile\":\"%s\","
                    + "\"phases\":{\"warmup_s\":0,\"measure_s\":0},"
                    + "\"kpis\":{\"throughput\":%.3f,\"p50_ms\":%d,\"p99_ms\":%d,\"p999_ms\":%d,\"p9999_ms\":%d,"
                    + "\"gc_pause_p99_ms\":%d,\"alloc_rate_mb_s\":0,\"rss_mb\":%d,\"native_mem_mb\":0,\"cpu_util_pct\":%.1f},"
                    + "\"mode_kpis\":{%s}}")
                    .formatted(BENCHMARK, cpus, kernel.replace("\"", "\\\""), profile,
                               throughput, p50, p99, p999, p9999,
                               gcPauseMs, rssMb, cpuUtil, MODE_KPIS_JSON);
        }
    }
}
