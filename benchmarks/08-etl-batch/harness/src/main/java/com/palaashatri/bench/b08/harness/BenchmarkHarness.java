package com.palaashatri.bench.b08.harness;

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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class BenchmarkHarness {
    private static final String BENCHMARK = "08-etl-batch";
    private static final String[] PROFILES = new String[]{"small", "large", "offheap"};
    private static final RequestSpec[] REQUESTS = new RequestSpec[]{
        new RequestSpec("POST", "/api/v1/etl/run", "{}"),
        new RequestSpec("GET", "/api/v1/etl/jobs", "{}"),
        new RequestSpec("GET", "/health", "{}"),
        new RequestSpec("GET", "/metrics", "{}")
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

        Result r = null;
        for (int i = 0; i < runs; i++) {
            r = run(baseUrl, profile, requests, threads, seed);
            System.out.println("Run " + (i + 1) + "/" + runs + ": throughput=" + String.format("%.1f", r.throughput()) + " rps");
        }
        if (r.ok() != r.requests()) {
            throw new IllegalStateException("only " + r.ok() + " of " + r.requests() + " benchmark requests succeeded against " + baseUrl);
        }
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        Files.writeString(out, r.toJson() + System.lineSeparator());
        System.out.println(r.toJson());
    }

    static Result run(String baseUrl, String profile, int totalRequests, int threads, long seed) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();
        Random random = new Random(seed ^ profile.hashCode());
        long[] latenciesMs = new long[Math.max(1, totalRequests)];
        AtomicInteger ok = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        long wallStart = System.nanoTime();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < latenciesMs.length; i++) {
            final int idx = i;
            final RequestSpec spec = REQUESTS[Math.floorMod(idx + random.nextInt(REQUESTS.length), REQUESTS.length)];
            futures.add(pool.submit(() -> {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + spec.path())).timeout(Duration.ofSeconds(30));
                if ("POST".equals(spec.method())) builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(spec.body(), StandardCharsets.UTF_8));
                else builder.GET();
                long start = System.nanoTime();
                try {
                    HttpResponse<String> res = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                    if (res.statusCode() >= 200 && res.statusCode() < 300 && !res.body().contains("\"error\"")) ok.incrementAndGet();
                } catch (Exception e) { /* count as failed */ }
                latenciesMs[idx] = Math.max(1L, (System.nanoTime() - start) / 1_000_000L);
            }));
        }
        for (Future<?> f : futures) { try { f.get(); } catch (Exception e) { /* already counted */ } }
        pool.shutdown();

        double wallElapsedSeconds = Math.max(0.001, (System.nanoTime() - wallStart) / 1_000_000_000.0);
        double throughput = totalRequests / wallElapsedSeconds;
        Arrays.sort(latenciesMs);

        // Collect real KPIs
        long gcPauseMs = ManagementFactory.getGarbageCollectorMXBeans().stream()
            .mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();
        long rssMb = getRssMb();
        double cpuPct = 0;
        try {
            OperatingSystemMXBean osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            cpuPct = osBean.getProcessCpuLoad() * 100;
        } catch (Exception ignored) {}

        // Get avg records_per_second from /api/v1/etl/jobs
        double avgRecordsPerSec = 0;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/etl/jobs")).GET().timeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            avgRecordsPerSec = parseAvgRecordsPerSec(res.body());
        } catch (Exception ignored) {}

        return new Result(profile, latenciesMs.length, ok.get(), throughput,
            pct(latenciesMs, 50), pct(latenciesMs, 99), pct(latenciesMs, 99.9), pct(latenciesMs, 99.99),
            gcPauseMs, rssMb, cpuPct, avgRecordsPerSec);
    }

    private static double parseAvgRecordsPerSec(String json) {
        List<Double> vals = new ArrayList<>();
        int i = 0;
        String key = "\"records_per_second\":";
        while ((i = json.indexOf(key, i)) >= 0) {
            i += key.length();
            int end = i;
            while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.' || json.charAt(end) == '-')) end++;
            if (end > i) {
                try { vals.add(Double.parseDouble(json.substring(i, end))); } catch (NumberFormatException ignored) {}
            }
        }
        return vals.isEmpty() ? 0 : vals.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private static long getRssMb() {
        try {
            long pid = ProcessHandle.current().pid();
            Process p = new ProcessBuilder("ps", "-o", "rss=", "-p", String.valueOf(pid)).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor(2, TimeUnit.SECONDS);
            return Long.parseLong(out) / 1024;
        } catch (Exception e) { return 0; }
    }

    private static long pct(long[] v, double p) { return v[Math.min(v.length - 1, (int) Math.ceil((p / 100.0) * v.length) - 1)]; }
    private static Map<String, String> parse(String[] args) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--")) { String k = a.substring(2); String v = "true"; if (i + 1 < args.length && !args[i + 1].startsWith("--")) v = args[++i]; out.put(k, v); }
        }
        return out;
    }
    record RequestSpec(String method, String path, String body) {}
    record Result(String profile, int requests, int ok, double throughput, long p50, long p99, long p999, long p9999, long gcPauseMs, long rssMb, double cpuPct, double avgRecordsPerSec) {
        String toJson() {
            String modeKpis = String.format(Locale.ROOT, "\"records_per_second\":%.2f,\"chunk_time_ms\":0,\"io_throughput_mb_s\":0", avgRecordsPerSec);
            return String.format(Locale.ROOT,
                "{\"benchmark\":\"%s\",\"runtime\":\"openjdk-hotspot-21\",\"gc\":\"G1\",\"jvm_flags\":[\"-XX:+UseG1GC\"],\"env\":{\"cpu\":\"%d\",\"kernel\":\"%s\",\"cgroup_cpu\":\"unknown\",\"cgroup_mem\":\"unknown\"},\"load_profile\":\"%s\",\"phases\":{\"warmup_s\":0,\"measure_s\":0},\"kpis\":{\"throughput\":%.3f,\"p50_ms\":%d,\"p99_ms\":%d,\"p999_ms\":%d,\"p9999_ms\":%d,\"gc_pause_p99_ms\":%d,\"alloc_rate_mb_s\":0,\"rss_mb\":%d,\"native_mem_mb\":0,\"cpu_util_pct\":%.1f},\"mode_kpis\":{%s}}",
                BENCHMARK, Runtime.getRuntime().availableProcessors(),
                System.getProperty("os.version", "unknown"),
                profile, throughput, p50, p99, p999, p9999,
                gcPauseMs, rssMb, cpuPct, modeKpis);
        }
    }
}
