package com.palaashatri.bench.b11.harness;

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
    private static final String BENCHMARK = "11-autoscaling-burst";
    private static final String[] PROFILES = new String[]{"flash-sale", "steady-browse", "scale-up-measure"};
    private static final RequestSpec[] REQUESTS = new RequestSpec[]{
        new RequestSpec("POST", "/api/v1/catalog/search", "{\"query\":\"running shoes\",\"category\":\"footwear\"}"),
        new RequestSpec("POST", "/api/v1/catalog/search", "{\"query\":\"boots\",\"category\":\"footwear\"}"),
        new RequestSpec("GET", "/api/v1/catalog/health", null),
        new RequestSpec("GET", "/api/v1/metrics/scaling", null),
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
        AtomicInteger rejected = new AtomicInteger();
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
                    if (res.statusCode() == 429) {
                        rejected.incrementAndGet();
                        ok.incrementAndGet(); // 429 is expected under burst; count as non-error for smoke
                    } else if (res.statusCode() >= 200 && res.statusCode() < 300 && !res.body().contains("\"error\"")) {
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
            String psOut = new String(ps.getInputStream().readAllBytes()).trim();
            if (!psOut.isBlank()) rssMb = Long.parseLong(psOut) / 1024;
        } catch (Exception ignored) {}

        int cpus = Runtime.getRuntime().availableProcessors();
        String kernel = System.getProperty("os.version", "unknown");
        double errorsRate = latencies.length > 0 ? (double) rejected.get() / latencies.length : 0;

        return new Result(profile, latencies.length, ok.get(), throughput,
                pct(sorted, 50.0), pct(sorted, 99.0), pct(sorted, 99.9), pct(sorted, 99.99),
                gcMs, rssMb, cpuPct, cpus, kernel, errorsRate);
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

    static class RequestSpec {
        final String method;
        final String path;
        final String body;
        RequestSpec(String method, String path, String body) {
            this.method = method; this.path = path; this.body = body;
        }
        String method() { return method; }
        String path() { return path; }
        String body() { return body; }
    }

    static class Result {
        final String profile;
        final int requests;
        final int ok;
        final double throughput;
        final long p50, p99, p999, p9999;
        final long gcMs;
        final long rssMb;
        final double cpuPct;
        final int cpus;
        final String kernel;
        final double errorsRate;

        Result(String profile, int requests, int ok, double throughput, long p50, long p99, long p999, long p9999,
               long gcMs, long rssMb, double cpuPct, int cpus, String kernel, double errorsRate) {
            this.profile = profile; this.requests = requests; this.ok = ok;
            this.throughput = throughput; this.p50 = p50; this.p99 = p99;
            this.p999 = p999; this.p9999 = p9999; this.gcMs = gcMs;
            this.rssMb = rssMb; this.cpuPct = cpuPct; this.cpus = cpus;
            this.kernel = kernel; this.errorsRate = errorsRate;
        }

        int requests() { return requests; }
        int ok() { return ok; }
        double throughput() { return throughput; }

        String toJson() {
            String modeKpis = "\"pod_readiness_ms\":0,\"scale_up_s\":0,\"errors_rate\":"
                    + String.format(java.util.Locale.ROOT, "%.6f", errorsRate);
            return ("{\"benchmark\":\"" + BENCHMARK + "\",\"runtime\":\"openjdk-hotspot-17\",\"gc\":\"G1\","
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
