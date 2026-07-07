package com.palaashatri.bench.b01.harness;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class BenchmarkHarness {
    private static final String BENCHMARK = "01-fintech-ledger";
    private static final String[] PROFILES = new String[]{"steady", "salary-day-burst", "campaign-spike"};
    private static final RequestSpec[] REQUESTS = new RequestSpec[]{
            new RequestSpec("GET", "/accounts/1001/balance", "{}"),
            new RequestSpec("GET", "/accounts/1001/transactions", "{}"),
            new RequestSpec("POST", "/transfers", "{\"from\":\"1001\",\"to\":\"1002\",\"amount_cents\":125}"),
            new RequestSpec("GET", "/health", "{}"),
            new RequestSpec("GET", "/accounts/1002/balance", "{}")
    };

    private BenchmarkHarness() { }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parse(args);
        String profile = opts.getOrDefault("profile", PROFILES[0]);
        int requests = Integer.parseInt(opts.getOrDefault("requests", "25"));
        int threads = Integer.parseInt(opts.getOrDefault("threads", "8"));
        int runs = Integer.parseInt(opts.getOrDefault("runs", "1"));
        String baseUrl = opts.getOrDefault("base-url", System.getenv().getOrDefault("BASE_URL", "http://localhost:8080"));
        long seed = Long.parseLong(opts.getOrDefault("seed", "424242"));
        Path out = Path.of(opts.getOrDefault("out", "results/results.json"));

        Result lastResult = null;
        for (int run = 1; run <= runs; run++) {
            System.out.println("Run " + run + "/" + runs + " ...");
            lastResult = run(baseUrl, profile, requests, threads, seed + run);
            if (run < runs) {
                System.out.println("Run " + run + " throughput=" + String.format("%.1f", lastResult.throughput()) +
                        " ok=" + lastResult.ok() + "/" + lastResult.requests());
            }
        }

        Result r = lastResult;
        if (r.ok() == 0) {
            throw new IllegalStateException("All " + r.requests() + " benchmark requests failed against " + baseUrl);
        }
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        Files.writeString(out, r.toJson() + System.lineSeparator());
        System.out.println(r.toJson());
    }

    static Result run(String baseUrl, String profile, int requests, int threads, long seed) throws InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
        Random random = new Random(seed ^ profile.hashCode());

        int totalRequests = Math.max(1, requests);
        long[] latenciesMs = new long[totalRequests];
        AtomicInteger okCount = new AtomicInteger(0);
        AtomicLong postLatencyNs = new AtomicLong(0);
        AtomicInteger postCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        long wallStart = System.nanoTime();

        // Submit tasks
        @SuppressWarnings("unchecked")
        Future<Long>[] futures = new Future[totalRequests];
        for (int i = 0; i < totalRequests; i++) {
            final int idx = i;
            final RequestSpec spec = REQUESTS[Math.floorMod(idx + random.nextInt(REQUESTS.length), REQUESTS.length)];
            futures[i] = executor.submit(() -> {
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
                    long elapsed = System.nanoTime() - start;
                    if (res.statusCode() >= 200 && res.statusCode() < 300 && !res.body().contains("\"error\"")) {
                        okCount.incrementAndGet();
                    }
                    if ("POST".equals(spec.method())) {
                        postLatencyNs.addAndGet(elapsed);
                        postCount.incrementAndGet();
                    }
                    return Math.max(1L, elapsed / 1_000_000L);
                } catch (IOException | InterruptedException e) {
                    return Math.max(1L, (System.nanoTime() - start) / 1_000_000L);
                }
            });
        }

        // Collect results
        for (int i = 0; i < totalRequests; i++) {
            try {
                latenciesMs[i] = futures[i].get();
            } catch (Exception e) {
                latenciesMs[i] = 1L;
            }
        }

        executor.shutdown();
        long wallElapsedNs = System.nanoTime() - wallStart;
        double wallElapsedSeconds = Math.max(0.001D, wallElapsedNs / 1_000_000_000.0D);
        double throughput = totalRequests / wallElapsedSeconds;

        Arrays.sort(latenciesMs);

        // Collect JVM/OS KPIs
        long gcPauseP99Ms = collectGcPauseMs();
        long rssMb = collectRssMb();
        double cpuUtilPct = collectCpuPct();
        long avgPostMs = postCount.get() > 0 ? (postLatencyNs.get() / postCount.get()) / 1_000_000L : 0L;

        return new Result(profile, totalRequests, okCount.get(), throughput,
                pct(latenciesMs, 50.0D), pct(latenciesMs, 99.0D),
                pct(latenciesMs, 99.9D), pct(latenciesMs, 99.99D),
                gcPauseP99Ms, rssMb, cpuUtilPct, avgPostMs);
    }

    private static long collectGcPauseMs() {
        long totalMs = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long t = gc.getCollectionTime();
            if (t > 0) totalMs += t;
        }
        return totalMs;
    }

    private static long collectRssMb() {
        try {
            long pid = ProcessHandle.current().pid();
            Process proc = new ProcessBuilder("ps", "-o", "rss=", "-p", String.valueOf(pid))
                    .redirectErrorStream(true)
                    .start();
            String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            proc.waitFor();
            if (!output.isBlank()) {
                return Long.parseLong(output.trim()) / 1024L;
            }
        } catch (Exception ignored) { }
        return 0L;
    }

    private static double collectCpuPct() {
        try {
            com.sun.management.OperatingSystemMXBean osBean =
                    (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            double load = osBean.getProcessCpuLoad();
            return load >= 0 ? load * 100.0 : 0.0;
        } catch (Exception ignored) {
            return 0.0;
        }
    }

    private static long pct(long[] v, double p) {
        return v[Math.min(v.length - 1, Math.max(0, (int) Math.ceil((p / 100.0D) * v.length) - 1))];
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
                  long gcPauseP99Ms, long rssMb, double cpuUtilPct, long avgTransferMs) {
        String toJson() {
            String modeKpis = "\"avg_transfer_ms\":" + avgTransferMs +
                    ",\"app_db_ms\":0,\"fraud_api_ms\":0,\"warmup_stable_s\":0";
            return """
                    {"benchmark":"%s","runtime":"openjdk-hotspot-21","gc":"G1","jvm_flags":["-XX:+UseG1GC"],\
"env":{"cpu":"%d","kernel":"unknown","cgroup_cpu":"unknown","cgroup_mem":"unknown"},\
"load_profile":"%s","phases":{"warmup_s":0,"measure_s":0},\
"kpis":{"throughput":%.3f,"p50_ms":%d,"p99_ms":%d,"p999_ms":%d,"p9999_ms":%d,\
"gc_pause_p99_ms":%d,"alloc_rate_mb_s":0,"rss_mb":%d,"native_mem_mb":0,"cpu_util_pct":%.2f},\
"mode_kpis":{%s}}"""
                    .formatted(BENCHMARK, Runtime.getRuntime().availableProcessors(),
                            profile, throughput, p50, p99, p999, p9999,
                            gcPauseP99Ms, rssMb, cpuUtilPct, modeKpis);
        }
    }
}
