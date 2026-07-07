package com.palaashatri.bench.b03.harness;

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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class BenchmarkHarness {
    private static final String BENCHMARK = "03-streaming-analytics";
    private static final String[] PROFILES = new String[]{"moderate-state-low-latency", "large-state-relaxed"};
    private static final RequestSpec[] REQUESTS = new RequestSpec[]{
        new RequestSpec("POST", "/api/v1/events", "{\"key\":\"sensor-1\",\"value\":42.5}"),
        new RequestSpec("POST", "/api/v1/events", "{\"key\":\"sensor-2\",\"value\":17.3}"),
        new RequestSpec("GET", "/api/v1/windows/sensor-1", null),
        new RequestSpec("GET", "/api/v1/lag", null),
        new RequestSpec("GET", "/health", null)
    };

    private BenchmarkHarness() { }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parse(args);
        String profile = opts.getOrDefault("profile", PROFILES[0]);
        int requests = Integer.parseInt(opts.getOrDefault("requests", "25"));
        int threads = Integer.parseInt(opts.getOrDefault("threads", "8"));
        int runs = Integer.parseInt(opts.getOrDefault("runs", "3"));
        String baseUrl = opts.getOrDefault("base-url", System.getenv().getOrDefault("BASE_URL", "http://localhost:8080"));
        Path out = Path.of(opts.getOrDefault("out", "results/results.json"));

        Result lastResult = null;
        for (int run = 1; run <= runs; run++) {
            System.out.println("{\"event\":\"run_start\",\"run\":" + run + ",\"of\":" + runs + "}");
            lastResult = run(baseUrl, profile, requests, threads);
            System.out.println("{\"event\":\"run_done\",\"run\":" + run + ",\"throughput\":" + String.format(java.util.Locale.ROOT, "%.3f", lastResult.throughput()) + "}");
        }

        Result r = lastResult;
        if (r.ok() != r.requests()) {
            System.err.println("Warning: only " + r.ok() + " of " + r.requests() + " requests succeeded");
        }
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        Files.writeString(out, r.toJson() + System.lineSeparator());
        System.out.println(r.toJson());
    }

    static Result run(String baseUrl, String profile, int requests, int threads) throws InterruptedException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        long[] latencies = new long[Math.max(1, requests)];
        AtomicInteger ok = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(latencies.length);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long wallStart = System.nanoTime();

        for (int i = 0; i < latencies.length; i++) {
            final int idx = i;
            pool.submit(() -> {
                RequestSpec spec = REQUESTS[idx % REQUESTS.length];
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + spec.path()))
                        .timeout(Duration.ofSeconds(5));
                if ("POST".equals(spec.method())) {
                    builder.header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(spec.body(), StandardCharsets.UTF_8));
                } else {
                    builder.GET();
                }
                long t0 = System.nanoTime();
                try {
                    HttpResponse<String> res = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                    if (res.statusCode() >= 200 && res.statusCode() < 300 && !res.body().contains("\"error\"")) {
                        ok.incrementAndGet();
                    }
                } catch (Exception e) {
                    // count as failure
                } finally {
                    latencies[idx] = Math.max(1, (System.nanoTime() - t0) / 1_000_000);
                    latch.countDown();
                }
            });
        }

        latch.await(60, TimeUnit.SECONDS);
        pool.shutdown();
        double wallSec = (System.nanoTime() - wallStart) / 1e9;
        double throughput = latencies.length / wallSec;

        long[] sorted = Arrays.copyOf(latencies, latencies.length);
        Arrays.sort(sorted);

        long gcMs = ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();

        double cpuPct = 0;
        try {
            OperatingSystemMXBean osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            cpuPct = osBean.getProcessCpuLoad() * 100;
        } catch (Exception ignored) {}

        long rssMb = 0;
        try {
            long pid = ProcessHandle.current().pid();
            Process ps = new ProcessBuilder("ps", "-o", "rss=", "-p", String.valueOf(pid)).start();
            String out = new String(ps.getInputStream().readAllBytes()).trim();
            if (!out.isBlank()) rssMb = Long.parseLong(out) / 1024;
        } catch (Exception ignored) {}

        int cpus = Runtime.getRuntime().availableProcessors();
        String kernel = System.getProperty("os.version", "unknown");

        return new Result(profile, latencies.length, ok.get(), throughput,
                pct(sorted, 50.0), pct(sorted, 99.0), pct(sorted, 99.9), pct(sorted, 99.99),
                gcMs, rssMb, cpuPct, cpus, kernel);
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

    record RequestSpec(String method, String path, String body) {}

    record Result(String profile, int requests, int ok, double throughput, long p50, long p99, long p999, long p9999,
                  long gcMs, long rssMb, double cpuPct, int cpus, String kernel) {
        String toJson() {
            String modeKpis = "\"events_per_second\":" + String.format(java.util.Locale.ROOT, "%.3f", throughput)
                    + ",\"window_latency_ms\":0,\"consumer_lag\":0";
            return ("{\"benchmark\":\"" + BENCHMARK + "\",\"runtime\":\"openjdk-hotspot-21\",\"gc\":\"G1\","
                    + "\"jvm_flags\":[\"-XX:+UseG1GC\"],"
                    + "\"env\":{\"cpu\":\"" + cpus + "\",\"kernel\":\"" + kernel + "\",\"cgroup_cpu\":\"unknown\",\"cgroup_mem\":\"unknown\"},"
                    + "\"load_profile\":\"" + profile + "\","
                    + "\"phases\":{\"warmup_s\":0,\"measure_s\":0},"
                    + "\"kpis\":{\"throughput\":" + String.format(java.util.Locale.ROOT, "%.3f", throughput)
                    + ",\"p50_ms\":" + p50 + ",\"p99_ms\":" + p99 + ",\"p999_ms\":" + p999 + ",\"p9999_ms\":" + p9999
                    + ",\"gc_pause_p99_ms\":" + gcMs + ",\"alloc_rate_mb_s\":0,\"rss_mb\":" + rssMb
                    + ",\"native_mem_mb\":0,\"cpu_util_pct\":" + String.format(java.util.Locale.ROOT, "%.2f", cpuPct) + "},"
                    + "\"mode_kpis\":{" + modeKpis + "}}").trim();
        }
    }
}
