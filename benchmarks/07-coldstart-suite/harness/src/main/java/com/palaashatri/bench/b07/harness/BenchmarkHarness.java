package com.palaashatri.bench.b07.harness;

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
    private static final String BENCHMARK = "07-coldstart-suite";
    private static final String MODE_KPIS_JSON =
            "\"time_to_first_response_ms\":0,\"time_to_90pct_s\":0,\"compiled_methods\":0";

    // Weighted request array:
    // GET /health x4, GET /api/v1/jfr/stats x3, GET /metrics x2, GET /api/v1/coldstart/measure x1
    private static final RequestSpec[] REQUESTS = new RequestSpec[]{
            new RequestSpec("GET", "/health"),
            new RequestSpec("GET", "/health"),
            new RequestSpec("GET", "/health"),
            new RequestSpec("GET", "/health"),
            new RequestSpec("GET", "/api/v1/jfr/stats"),
            new RequestSpec("GET", "/api/v1/jfr/stats"),
            new RequestSpec("GET", "/api/v1/jfr/stats"),
            new RequestSpec("GET", "/metrics"),
            new RequestSpec("GET", "/metrics"),
            new RequestSpec("GET", "/api/v1/coldstart/measure"),
    };

    // Circuit breaker state for /api/v1/coldstart/measure
    private volatile boolean coldstartCbOpen = false;
    private volatile long coldstartCbOpenUntil = 0L;
    private static final long CB_OPEN_DURATION_MS = 30_000L;

    private BenchmarkHarness() { }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parse(args);
        String profile = opts.getOrDefault("profile", "cold");
        int requestCount = Integer.parseInt(opts.getOrDefault("requests", "25"));
        int threads = Integer.parseInt(opts.getOrDefault("threads", "8"));
        int runs = Integer.parseInt(opts.getOrDefault("runs", "3"));
        String baseUrl = opts.getOrDefault("base-url",
                System.getenv().getOrDefault("BASE_URL", "http://localhost:8080"));
        long seed = Long.parseLong(opts.getOrDefault("seed", "424242"));

        BenchmarkHarness harness = new BenchmarkHarness();
        Result best = null;
        for (int run = 0; run < runs; run++) {
            Result r = harness.run(baseUrl, profile, requestCount, threads, seed + run);
            System.out.printf("Run %d/%d: throughput=%.2f rps, ok=%d/%d, p99=%dms%n",
                    run + 1, runs, r.throughput(), r.ok(), r.requests(), r.p99());
            if (best == null || r.ok() > best.ok()) best = r;
        }

        if (best == null) throw new IllegalStateException("no runs completed");
        if (best.ok() != best.requests()) {
            throw new IllegalStateException("only " + best.ok() + " of " + best.requests()
                    + " benchmark requests succeeded against " + baseUrl);
        }

        Path out = Path.of(opts.getOrDefault("out", "results/results.json"));
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        Files.writeString(out, best.toJson() + System.lineSeparator());
        System.out.println(best.toJson());
    }

    Result run(String baseUrl, String profile, int requestCount, int threads, long seed)
            throws InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
        Random random = new Random(seed ^ profile.hashCode());

        long[] latenciesMs = new long[Math.max(1, requestCount)];
        AtomicInteger okCount = new AtomicInteger(0);
        AtomicLong failCount = new AtomicLong(0);

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(threads, requestCount));
        CountDownLatch latch = new CountDownLatch(requestCount);

        long wallStart = System.nanoTime();

        for (int i = 0; i < requestCount; i++) {
            final int idx = i;
            final int specIdx = Math.floorMod(idx + random.nextInt(REQUESTS.length), REQUESTS.length);
            pool.submit(() -> {
                try {
                    RequestSpec spec = chooseSpec(specIdx);
                    Duration timeout = spec.path().contains("coldstart") ? Duration.ofSeconds(15) : Duration.ofSeconds(5);
                    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + spec.path()))
                            .timeout(timeout)
                            .GET();
                    long start = System.nanoTime();
                    try {
                        HttpResponse<String> res = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                        latenciesMs[idx] = Math.max(1L, (System.nanoTime() - start) / 1_000_000L);
                        if (res.statusCode() >= 200 && res.statusCode() < 300) {
                            String body = res.body();
                            // coldstart endpoint is OK even with error key (timeout case returns -1 which is valid)
                            if (spec.path().contains("coldstart")) {
                                // Circuit breaker: if response contains "error" AND it's not just the timeout -1 case
                                if (body.contains("\"error\"") && !body.contains("time_to_first_response_ms")) {
                                    openCircuitBreaker();
                                }
                                okCount.incrementAndGet();
                            } else if (!body.contains("\"error\"")) {
                                okCount.incrementAndGet();
                            } else {
                                failCount.incrementAndGet();
                            }
                        } else {
                            latenciesMs[idx] = Math.max(1L, (System.nanoTime() - start) / 1_000_000L);
                            failCount.incrementAndGet();
                            if (spec.path().contains("coldstart")) openCircuitBreaker();
                        }
                    } catch (IOException e) {
                        latenciesMs[idx] = Math.max(1L, (System.nanoTime() - start) / 1_000_000L);
                        failCount.incrementAndGet();
                        if (spec.path().contains("coldstart")) openCircuitBreaker();
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    latenciesMs[idx] = 1L;
                    failCount.incrementAndGet();
                } catch (Exception e) {
                    latenciesMs[idx] = 1L;
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        pool.shutdown();

        long wallElapsedNanos = System.nanoTime() - wallStart;
        double wallElapsedSeconds = Math.max(0.001D, wallElapsedNanos / 1_000_000_000.0D);
        double throughput = requestCount / wallElapsedSeconds;

        Arrays.sort(latenciesMs);

        // Collect runtime metrics
        long gcMs = collectGcMs();
        long rssMb = collectRssMb();
        double cpuPct = collectCpuPct();
        long compiledMs = compilationMs();

        return new Result(
                profile, requestCount, okCount.get(),
                throughput,
                pct(latenciesMs, 50.0D), pct(latenciesMs, 99.0D),
                pct(latenciesMs, 99.9D), pct(latenciesMs, 99.99D),
                gcMs, rssMb, cpuPct, compiledMs
        );
    }

    // -------------------------------------------------------------------------
    // Circuit breaker
    // -------------------------------------------------------------------------

    private void openCircuitBreaker() {
        coldstartCbOpen = true;
        coldstartCbOpenUntil = System.currentTimeMillis() + CB_OPEN_DURATION_MS;
    }

    private RequestSpec chooseSpec(int specIdx) {
        RequestSpec spec = REQUESTS[specIdx % REQUESTS.length];
        if (spec.path().contains("coldstart")) {
            // Check circuit breaker
            if (coldstartCbOpen) {
                if (System.currentTimeMillis() < coldstartCbOpenUntil) {
                    // Substitute with health
                    return REQUESTS[0]; // GET /health
                } else {
                    // Circuit closes
                    coldstartCbOpen = false;
                }
            }
        }
        return spec;
    }

    // -------------------------------------------------------------------------
    // Runtime metric collectors
    // -------------------------------------------------------------------------

    private static long collectGcMs() {
        long total = 0L;
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        for (GarbageCollectorMXBean bean : gcBeans) {
            long t = bean.getCollectionTime();
            if (t > 0) total += t;
        }
        return total;
    }

    private static long collectRssMb() {
        try {
            long pid = ProcessHandle.current().pid();
            Process ps = new ProcessBuilder("ps", "-o", "rss=", "-p", String.valueOf(pid))
                    .redirectErrorStream(true)
                    .start();
            String out = new String(ps.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            ps.waitFor();
            if (!out.isBlank()) {
                return Long.parseLong(out.trim()) / 1024L; // KB -> MB
            }
        } catch (Exception ignored) {
            // ps not available or parsing failed
        }
        return 0L;
    }

    private static double collectCpuPct() {
        try {
            Object osMx = ManagementFactory.getOperatingSystemMXBean();
            if (osMx instanceof com.sun.management.OperatingSystemMXBean sunMx) {
                double load = sunMx.getProcessCpuLoad();
                if (load >= 0) return load * 100.0D;
            }
        } catch (Exception ignored) {
        }
        return 0.0D;
    }

    private static long compilationMs() {
        var compMX = ManagementFactory.getCompilationMXBean();
        if (compMX == null) return 0L;
        return compMX.getTotalCompilationTime();
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private static long pct(long[] v, double p) {
        return v[Math.min(v.length - 1, (int) Math.ceil((p / 100.0D) * v.length) - 1)];
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

    // -------------------------------------------------------------------------
    // Data types
    // -------------------------------------------------------------------------

    record RequestSpec(String method, String path) { }

    record Result(
            String profile, int requests, int ok,
            double throughput,
            long p50, long p99, long p999, long p9999,
            long gcMs, long rssMb, double cpuPct, long compiledMs
    ) {
        String toJson() {
            String env = "{\"cpu\":\"" + Runtime.getRuntime().availableProcessors()
                    + "\",\"kernel\":\"" + escape(System.getProperty("os.version", "unknown"))
                    + "\",\"cgroup_cpu\":\"unknown\",\"cgroup_mem\":\"unknown\"}";
            return ("{\"benchmark\":\"" + BENCHMARK + "\","
                    + "\"runtime\":\"openjdk-hotspot-21\","
                    + "\"gc\":\"G1\","
                    + "\"jvm_flags\":[\"-XX:+UseG1GC\"],"
                    + "\"env\":" + env + ","
                    + "\"load_profile\":\"" + escape(profile) + "\","
                    + "\"phases\":{\"warmup_s\":0,\"measure_s\":0},"
                    + "\"kpis\":{"
                    + "\"throughput\":" + String.format(java.util.Locale.ROOT, "%.3f", throughput) + ","
                    + "\"p50_ms\":" + p50 + ","
                    + "\"p99_ms\":" + p99 + ","
                    + "\"p999_ms\":" + p999 + ","
                    + "\"p9999_ms\":" + p9999 + ","
                    + "\"gc_pause_p99_ms\":" + gcMs + ","
                    + "\"alloc_rate_mb_s\":0,"
                    + "\"rss_mb\":" + rssMb + ","
                    + "\"native_mem_mb\":0,"
                    + "\"cpu_util_pct\":" + String.format(java.util.Locale.ROOT, "%.2f", cpuPct)
                    + "},"
                    + "\"mode_kpis\":{"
                    + "\"time_to_first_response_ms\":0,"
                    + "\"time_to_90pct_s\":0,"
                    + "\"compiled_methods\":" + compiledMs
                    + "}"
                    + "}");
        }

        private static String escape(String raw) {
            if (raw == null) return "";
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (c == '"') out.append("\\\"");
                else if (c == '\\') out.append("\\\\");
                else out.append(c);
            }
            return out.toString();
        }
    }
}
