package com.palaashatri.bench.b13.harness;

import java.io.IOException;
import java.lang.management.*;
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
    private static final String BENCHMARK = "13-large-monolith";
    private static final String[] PROFILES = new String[]{"warmup-curve", "steady-state", "restart-cycle"};

    // Request specs as simple arrays: [method, path, body]
    private static final String[][] REQUESTS = new String[][]{
        new String[]{"GET", "/api/v1/monolith/health", ""},
        new String[]{"GET", "/api/v1/monolith/warmup/status", ""},
        new String[]{"GET", "/health", ""},
        new String[]{"GET", "/metrics", ""}
    };

    private BenchmarkHarness() { }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parse(args);
        String profile = opts.getOrDefault("profile", PROFILES[0]);
        int requests = Integer.parseInt(opts.getOrDefault("requests", "25"));
        int threads = Integer.parseInt(opts.getOrDefault("threads", "8"));
        int runs = Integer.parseInt(opts.getOrDefault("runs", "3"));
        String baseUrl = opts.getOrDefault("base-url", System.getenv().getOrDefault("BASE_URL", "http://localhost:8080"));
        long seed = Long.parseLong(opts.getOrDefault("seed", "424242"));
        Result r = run(baseUrl, profile, requests, threads, runs, seed);
        if (r.ok < r.totalRequests) {
            System.err.println("Warning: only " + r.ok + " of " + r.totalRequests + " benchmark requests succeeded against " + baseUrl);
        }
        Path out = Path.of(opts.getOrDefault("out", "results/results.json"));
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        Files.writeString(out, r.toJson() + System.lineSeparator());
        System.out.println(r.toJson());
    }

    static Result run(String baseUrl, String profile, int requests, int threads, int runs, long seed) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        Random random = new Random(seed ^ profile.hashCode());

        long[] latenciesMs = new long[Math.max(1, requests)];
        AtomicInteger ok = new AtomicInteger(0);

        // Collect GC time before
        long gcMsBefore = totalGcMs();

        // Wall-clock concurrent execution
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(latenciesMs.length);

        long wallStart = System.nanoTime();
        for (int i = 0; i < latenciesMs.length; i++) {
            final int idx = i;
            final String[] spec = REQUESTS[Math.floorMod(i + random.nextInt(REQUESTS.length), REQUESTS.length)];
            pool.execute(() -> {
                long start = System.nanoTime();
                try {
                    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + spec[1]))
                        .timeout(Duration.ofSeconds(10));
                    if ("POST".equals(spec[0])) {
                        builder.header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(spec[2], StandardCharsets.UTF_8));
                    } else {
                        builder.GET();
                    }
                    HttpResponse<String> res = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                    if (res.statusCode() >= 200 && res.statusCode() < 300 && !res.body().contains("\"error\"")) {
                        ok.incrementAndGet();
                    }
                } catch (IOException | InterruptedException e) {
                    // connection or protocol failure counts as failed request
                }
                latenciesMs[idx] = Math.max(1L, (System.nanoTime() - start) / 1_000_000L);
                latch.countDown();
            });
        }
        latch.await(120, TimeUnit.SECONDS);
        long wallEnd = System.nanoTime();
        pool.shutdown();

        double wallSeconds = Math.max(0.001, (wallEnd - wallStart) / 1e9);
        double throughput = latenciesMs.length / wallSeconds;

        long gcMsAfter = totalGcMs();
        long gcMs = gcMsAfter - gcMsBefore;

        // Collect system metrics
        long rssMb = collectRssMb();
        double cpuPct = collectCpuPct();

        Arrays.sort(latenciesMs);
        long p50 = pct(latenciesMs, 50.0);
        long p99 = pct(latenciesMs, 99.0);
        long p999 = pct(latenciesMs, 99.9);
        long p9999 = pct(latenciesMs, 99.99);

        // Fetch warmup status for mode KPIs
        long timeToFirstResponseMs = 0;
        long timeTo90PctS = 0;
        long jitCompilationMs = 0;
        try {
            HttpRequest warmupReq = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/monolith/warmup/status"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
            HttpResponse<String> warmupRes = client.send(warmupReq, HttpResponse.BodyHandlers.ofString());
            if (warmupRes.statusCode() == 200) {
                String body = warmupRes.body();
                timeToFirstResponseMs = number(body, "time_to_first_response_ms", 0);
                timeTo90PctS = number(body, "time_to_90pct_s", 0);
                jitCompilationMs = number(body, "jit_compilation_ms", 0);
            }
        } catch (Exception e) {
            // best-effort; leave defaults
        }

        return new Result(profile, latenciesMs.length, ok.get(), throughput,
            p50, p99, p999, p9999, gcMs, rssMb, cpuPct,
            timeToFirstResponseMs, timeTo90PctS, jitCompilationMs);
    }

    private static long totalGcMs() {
        long total = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long t = gc.getCollectionTime();
            if (t > 0) total += t;
        }
        return total;
    }

    private static long collectRssMb() {
        try {
            long pid = ProcessHandle.current().pid();
            Process proc = new ProcessBuilder("ps", "-o", "rss=", "-p", String.valueOf(pid))
                .redirectErrorStream(true)
                .start();
            String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            proc.waitFor(5, TimeUnit.SECONDS);
            if (!out.isEmpty()) {
                return Long.parseLong(out.replaceAll("\\s+", "")) / 1024;
            }
        } catch (Exception e) {
            // best-effort
        }
        return 0;
    }

    private static double collectCpuPct() {
        try {
            java.lang.management.OperatingSystemMXBean osMx = ManagementFactory.getOperatingSystemMXBean();
            if (osMx instanceof com.sun.management.OperatingSystemMXBean) {
                com.sun.management.OperatingSystemMXBean sunOs = (com.sun.management.OperatingSystemMXBean) osMx;
                double load = sunOs.getCpuLoad();
                if (load >= 0) return load * 100.0;
            }
        } catch (Exception e) {
            // best-effort
        }
        return 0.0;
    }

    private static long pct(long[] v, double p) {
        return v[Math.min(v.length - 1, Math.max(0, (int) Math.ceil((p / 100.0) * v.length) - 1))];
    }

    private static long number(String body, String name, long fallback) {
        String quoted = "\"" + name + "\"";
        int key = body.indexOf(quoted);
        if (key < 0) return fallback;
        int colon = body.indexOf(':', key + quoted.length());
        if (colon < 0) return fallback;
        int start = colon + 1;
        while (start < body.length() && Character.isWhitespace(body.charAt(start))) start++;
        int end = start;
        while (end < body.length() && (Character.isDigit(body.charAt(end)) || body.charAt(end) == '-')) end++;
        if (end == start) return fallback;
        try { return Long.parseLong(body.substring(start, end)); } catch (NumberFormatException e) { return fallback; }
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--")) {
                String k = a.substring(2);
                String v = "true";
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    v = args[++i];
                }
                out.put(k, v);
            }
        }
        return out;
    }

    static final class Result {
        final String profile;
        final int totalRequests;
        final int ok;
        final double throughput;
        final long p50;
        final long p99;
        final long p999;
        final long p9999;
        final long gcPauseMs;
        final long rssMb;
        final double cpuPct;
        final long timeToFirstResponseMs;
        final long timeTo90PctS;
        final long jitCompilationMs;

        Result(String profile, int totalRequests, int ok, double throughput,
               long p50, long p99, long p999, long p9999, long gcPauseMs, long rssMb, double cpuPct,
               long timeToFirstResponseMs, long timeTo90PctS, long jitCompilationMs) {
            this.profile = profile;
            this.totalRequests = totalRequests;
            this.ok = ok;
            this.throughput = throughput;
            this.p50 = p50;
            this.p99 = p99;
            this.p999 = p999;
            this.p9999 = p9999;
            this.gcPauseMs = gcPauseMs;
            this.rssMb = rssMb;
            this.cpuPct = cpuPct;
            this.timeToFirstResponseMs = timeToFirstResponseMs;
            this.timeTo90PctS = timeTo90PctS;
            this.jitCompilationMs = jitCompilationMs;
        }

        String toJson() {
            int cpus = Runtime.getRuntime().availableProcessors();
            String modeKpis = "\"time_to_first_response_ms\":" + timeToFirstResponseMs
                + ",\"time_to_90pct_s\":" + timeTo90PctS
                + ",\"compiled_methods\":" + jitCompilationMs;
            return "{"
                + "\"benchmark\":\"" + BENCHMARK + "\""
                + ",\"runtime\":\"openjdk-hotspot-17\""
                + ",\"gc\":\"G1\""
                + ",\"jvm_flags\":[\"-XX:+UseG1GC\"]"
                + ",\"env\":{"
                    + "\"cpu\":\"" + cpus + "\""
                    + ",\"kernel\":\"unknown\""
                    + ",\"cgroup_cpu\":\"unknown\""
                    + ",\"cgroup_mem\":\"unknown\""
                + "}"
                + ",\"load_profile\":\"" + profile + "\""
                + ",\"phases\":{\"warmup_s\":0,\"measure_s\":0}"
                + ",\"kpis\":{"
                    + "\"throughput\":" + String.format(Locale.ROOT, "%.3f", throughput)
                    + ",\"p50_ms\":" + p50
                    + ",\"p99_ms\":" + p99
                    + ",\"p999_ms\":" + p999
                    + ",\"p9999_ms\":" + p9999
                    + ",\"gc_pause_p99_ms\":" + gcPauseMs
                    + ",\"alloc_rate_mb_s\":0"
                    + ",\"rss_mb\":" + rssMb
                    + ",\"native_mem_mb\":0"
                    + ",\"cpu_util_pct\":" + String.format(Locale.ROOT, "%.1f", cpuPct)
                + "}"
                + ",\"mode_kpis\":{" + modeKpis + "}"
                + "}";
        }
    }
}
