package com.palaashatri.bench.b09.harness;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class BenchmarkHarness {
    private static final String BENCHMARK = "09-onnx-inference";
    private static final String[] PROFILES = {"cold-start", "steady-state", "comparison"};

    private static final RequestSpec[] REQUESTS = {
        new RequestSpec("POST", "/api/v1/inference/classify", "{\"text\":\"local inference is deterministic\"}"),
        new RequestSpec("POST", "/api/v1/inference/classify", "{\"features\":[5.1,3.5,1.4,0.2]}"),
        new RequestSpec("POST", "/api/v1/inference/classify", "{\"features\":[6.3,3.3,6.0,2.5]}"),
        new RequestSpec("GET",  "/api/v1/inference/health",  ""),
        new RequestSpec("GET",  "/health",                   "")
    };

    private BenchmarkHarness() { }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parse(args);
        String profile  = opts.getOrDefault("profile",   PROFILES[0]);
        int requests    = Integer.parseInt(opts.getOrDefault("requests", "50"));
        int threads     = Integer.parseInt(opts.getOrDefault("threads",  "8"));
        int runs        = Integer.parseInt(opts.getOrDefault("runs",     "3"));
        long seed       = Long.parseLong(opts.getOrDefault("seed",      "424242"));
        String baseUrl  = opts.getOrDefault("base-url",
                System.getenv().getOrDefault("BASE_URL", "http://localhost:18009"));

        Result last = null;
        for (int run = 0; run < runs; run++) {
            last = run(baseUrl, profile, requests, threads, seed + run);
        }

        if (last.ok() < last.requests()) {
            throw new IllegalStateException("only " + last.ok() + " of " + last.requests()
                    + " benchmark requests succeeded against " + baseUrl);
        }
        Path out = Path.of(opts.getOrDefault("out", "results/results.json"));
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        Files.writeString(out, last.toJson() + System.lineSeparator());
        System.out.println(last.toJson());
    }

    static Result run(String baseUrl, String profile, int requests, int threads, long seed)
            throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        int total = Math.max(1, requests);
        AtomicInteger ok = new AtomicInteger();
        AtomicLong sumLatencyMs = new AtomicLong();
        // For mode_kpis
        AtomicLong modelLoadMs = new AtomicLong(-1);
        AtomicLong inferenceUsSum = new AtomicLong();
        AtomicInteger classifyCount = new AtomicInteger();

        CountDownLatch latch = new CountDownLatch(total);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        long wallStart = System.nanoTime();

        for (int i = 0; i < total; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    RequestSpec spec = REQUESTS[idx % REQUESTS.length];
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
                        long elapsedMs = Math.max(1L, (System.nanoTime() - t0) / 1_000_000L);
                        sumLatencyMs.addAndGet(elapsedMs);
                        String body = res.body();
                        if (res.statusCode() >= 200 && res.statusCode() < 300 && !body.contains("\"error\"")) {
                            ok.incrementAndGet();
                            // Extract model_load_ms from health responses
                            if (spec.path().contains("health")) {
                                long mlt = extractLong(body, "model_load_ms");
                                if (mlt >= 0) modelLoadMs.set(mlt);
                            }
                            // Extract inference_ms from classify responses
                            if (spec.path().contains("classify")) {
                                double ims = extractDouble(body, "inference_ms");
                                if (ims >= 0) {
                                    inferenceUsSum.addAndGet((long)(ims * 1000));
                                    classifyCount.incrementAndGet();
                                }
                            }
                        }
                    } catch (IOException | InterruptedException ignored) {
                        sumLatencyMs.addAndGet(Math.max(1L, (System.nanoTime() - t0) / 1_000_000L));
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        pool.shutdown();

        long wallNs = System.nanoTime() - wallStart;
        double wallSec = Math.max(0.001, wallNs / 1_000_000_000.0);
        double throughput = total / wallSec;

        // GC metrics
        long gcMs = 0;
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        for (GarbageCollectorMXBean gc : gcBeans) {
            long t = gc.getCollectionTime();
            if (t > 0) gcMs += t;
        }

        // RSS
        double rssMb = 0;
        try {
            long pid = ProcessHandle.current().pid();
            Process ps = new ProcessBuilder("ps", "-o", "rss=", "-p", String.valueOf(pid))
                    .start();
            String out = new String(ps.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!out.isBlank()) rssMb = Long.parseLong(out) / 1024.0;
        } catch (Exception ignored) { }

        // CPU
        double cpuPct = 0;
        try {
            com.sun.management.OperatingSystemMXBean osMx =
                    (com.sun.management.OperatingSystemMXBean)
                    ManagementFactory.getOperatingSystemMXBean();
            double load = osMx.getProcessCpuLoad();
            if (load >= 0) cpuPct = load * 100.0;
        } catch (Exception ignored) { }

        String env = "{\"cpu\":" + Runtime.getRuntime().availableProcessors()
                + ",\"kernel\":\"" + escape(System.getProperty("os.version", "unknown")) + "\""
                + ",\"cgroup_cpu\":\"unknown\",\"cgroup_mem\":\"unknown\"}";

        long mlt = modelLoadMs.get() < 0 ? 0 : modelLoadMs.get();
        int cc = classifyCount.get();
        double avgInferenceMs = cc > 0 ? (inferenceUsSum.get() / 1000.0) / cc : 0.0;

        return new Result(profile, total, ok.get(), throughput, gcMs, rssMb, cpuPct, env, mlt, avgInferenceMs);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static long extractLong(String body, String key) {
        String quoted = "\"" + key + "\"";
        int k = body.indexOf(quoted);
        if (k < 0) return -1;
        int colon = body.indexOf(':', k + quoted.length());
        if (colon < 0) return -1;
        int s = colon + 1;
        while (s < body.length() && Character.isWhitespace(body.charAt(s))) s++;
        int e = s;
        while (e < body.length() && (Character.isDigit(body.charAt(e)) || body.charAt(e) == '-')) e++;
        if (e == s) return -1;
        try { return Long.parseLong(body.substring(s, e)); } catch (NumberFormatException ex) { return -1; }
    }

    private static double extractDouble(String body, String key) {
        String quoted = "\"" + key + "\"";
        int k = body.indexOf(quoted);
        if (k < 0) return -1;
        int colon = body.indexOf(':', k + quoted.length());
        if (colon < 0) return -1;
        int s = colon + 1;
        while (s < body.length() && Character.isWhitespace(body.charAt(s))) s++;
        int e = s;
        while (e < body.length() && (Character.isDigit(body.charAt(e))
                || body.charAt(e) == '-' || body.charAt(e) == '.' || body.charAt(e) == 'E'
                || body.charAt(e) == 'e' || body.charAt(e) == '+')) e++;
        if (e == s) return -1;
        try { return Double.parseDouble(body.substring(s, e)); } catch (NumberFormatException ex) { return -1; }
    }

    private static String escape(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> sb.append(c);
            }
        }
        return sb.toString();
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

    record Result(
            String profile,
            int requests,
            int ok,
            double throughput,
            long gcMs,
            double rssMb,
            double cpuPct,
            String env,
            long modelLoadMs,
            double avgInferenceMs) {

        String toJson() {
            return "{\"benchmark\":\"" + BENCHMARK + "\""
                    + ",\"runtime\":\"openjdk-hotspot-17\""
                    + ",\"gc\":\"G1\""
                    + ",\"jvm_flags\":[\"-XX:+UseG1GC\"]"
                    + ",\"env\":" + env
                    + ",\"load_profile\":\"" + profile + "\""
                    + ",\"phases\":{\"warmup_s\":0,\"measure_s\":0}"
                    + ",\"kpis\":{"
                    +   "\"throughput\":" + fmt3(throughput)
                    +   ",\"p50_ms\":0,\"p99_ms\":0,\"p999_ms\":0,\"p9999_ms\":0"
                    +   ",\"gc_pause_p99_ms\":" + gcMs
                    +   ",\"alloc_rate_mb_s\":0"
                    +   ",\"rss_mb\":" + fmt3(rssMb)
                    +   ",\"native_mem_mb\":0"
                    +   ",\"cpu_util_pct\":" + fmt3(cpuPct)
                    + "}"
                    + ",\"mode_kpis\":{"
                    +   "\"model_load_ms\":" + modelLoadMs
                    +   ",\"tokenize_ms\":0"
                    +   ",\"inference_ms\":" + fmt3(avgInferenceMs)
                    + "}"
                    + "}";
        }

        private static String fmt3(double v) {
            return String.format(java.util.Locale.ROOT, "%.3f", v);
        }
    }
}
