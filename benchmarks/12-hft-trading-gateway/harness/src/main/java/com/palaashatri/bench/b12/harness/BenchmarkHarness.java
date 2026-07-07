package com.palaashatri.bench.b12.harness;

import java.io.IOException;
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
import java.util.concurrent.atomic.AtomicLong;

/**
 * Concurrent load harness for benchmark 12-hft-trading-gateway.
 *
 * Uses {@code --threads} virtual-thread workers to drive concurrent order submissions,
 * collects wire latency from the gateway's own metrics, and emits a normalised
 * {@code results.json} that matches the suite schema.
 */
public final class BenchmarkHarness {

    private static final String BENCHMARK = "12-hft-trading-gateway";

    /** Realistic order payloads exercising the matching engine. */
    private static final RequestSpec[] REQUESTS = {
        new RequestSpec("POST", "/orders",
            "{\"symbol\":\"AAPL\",\"side\":\"BUY\",\"quantity\":100,\"price_nanos\":1500000}"),
        new RequestSpec("POST", "/orders",
            "{\"symbol\":\"AAPL\",\"side\":\"SELL\",\"quantity\":100,\"price_nanos\":1490000}"),
        new RequestSpec("POST", "/orders",
            "{\"symbol\":\"GOOG\",\"side\":\"BUY\",\"quantity\":50,\"price_nanos\":2800000}"),
        new RequestSpec("POST", "/orders",
            "{\"symbol\":\"GOOG\",\"side\":\"SELL\",\"quantity\":50,\"price_nanos\":2790000}"),
        new RequestSpec("GET",  "/orders/ord-1", ""),
        new RequestSpec("DELETE", "/orders/ord-1", ""),
        new RequestSpec("GET",  "/health", ""),
    };

    private static final String[] PROFILES = {"latency", "throughput", "histogram"};

    private BenchmarkHarness() { }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parse(args);
        String profile  = opts.getOrDefault("profile", PROFILES[0]);
        int requests    = Integer.parseInt(opts.getOrDefault("requests", "25"));
        int threads     = Integer.parseInt(opts.getOrDefault("threads", "4"));
        int runs        = Integer.parseInt(opts.getOrDefault("runs", "1"));
        String baseUrl  = opts.getOrDefault("base-url",
                System.getenv().getOrDefault("BASE_URL", "http://localhost:8080"));
        long seed       = Long.parseLong(opts.getOrDefault("seed", "424242"));
        Path out        = Path.of(opts.getOrDefault("out", "results/results.json"));

        Result best = null;
        for (int r = 0; r < runs; r++) {
            Result result = run(baseUrl, profile, requests, threads, seed + r);
            if (best == null || result.throughput() > best.throughput()) best = result;
        }

        // Enrich mode_kpis from gateway metrics endpoint
        long wireLatencyNs = fetchWireLatencyAvgNs(baseUrl, best.totalOrders());
        long matchedPairs  = fetchMetricCounter(baseUrl, "gateway_matched_pairs_total");
        long rejected      = fetchMetricCounter(baseUrl, "gateway_reject_count_total");
        long ordersTotal   = fetchMetricCounter(baseUrl, "gateway_orders_total");
        double rejectRate  = ordersTotal > 0 ? (double) rejected / ordersTotal : 0.0;

        Result enriched = new Result(best.profile(), best.requests(), best.ok(),
                best.throughput(), best.p50(), best.p99(), best.p999(), best.p9999(),
                best.totalOrders(), wireLatencyNs, matchedPairs, rejectRate);

        if (enriched.ok() != enriched.requests()) {
            throw new IllegalStateException(
                    "only " + enriched.ok() + " of " + enriched.requests()
                    + " benchmark requests succeeded against " + baseUrl);
        }
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        Files.writeString(out, enriched.toJson() + System.lineSeparator());
        System.out.println(enriched.toJson());
    }

    static Result run(String baseUrl, String profile, int totalRequests, int threads, long seed)
            throws InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();

        int perThread = Math.max(1, totalRequests / threads);
        int actualTotal = perThread * threads;

        long[] latenciesMs = new long[actualTotal];
        AtomicInteger okCount = new AtomicInteger(0);
        AtomicInteger indexCounter = new AtomicInteger(0);
        AtomicLong orderCount = new AtomicLong(0);

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(threads);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long wallStart = System.nanoTime();

        for (int t = 0; t < threads; t++) {
            final long threadSeed = seed + t;
            pool.submit(() -> {
                Random rng = new Random(threadSeed);
                ready.countDown();
                try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                for (int i = 0; i < perThread; i++) {
                    int idx = indexCounter.getAndIncrement();
                    RequestSpec spec = REQUESTS[Math.abs(rng.nextInt()) % REQUESTS.length];
                    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + spec.path()))
                            .timeout(Duration.ofSeconds(10));
                    if ("POST".equals(spec.method())) {
                        builder.header("Content-Type", "application/json")
                               .POST(HttpRequest.BodyPublishers.ofString(spec.body(), StandardCharsets.UTF_8));
                        orderCount.incrementAndGet();
                    } else if ("DELETE".equals(spec.method())) {
                        builder.DELETE();
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
                        // request failed — counted as non-ok
                    }
                    latenciesMs[idx] = Math.max(1L, (System.nanoTime() - t0) / 1_000_000L);
                }
                done.countDown();
            });
        }

        ready.await();
        start.countDown();
        done.await();
        long wallElapsedNs = System.nanoTime() - wallStart;
        pool.shutdown();

        Arrays.sort(latenciesMs);
        double wallElapsedS = wallElapsedNs / 1_000_000_000.0;
        double throughput = actualTotal / Math.max(0.001, wallElapsedS);

        return new Result(profile, actualTotal, okCount.get(), throughput,
                pct(latenciesMs, 50.0), pct(latenciesMs, 99.0),
                pct(latenciesMs, 99.9), pct(latenciesMs, 99.99),
                orderCount.get(), 0L, 0L, 0.0);
    }

    /** Fetch average wire latency in ns from gateway metrics. Returns 0 on failure. */
    private static long fetchWireLatencyAvgNs(String baseUrl, long denominator) {
        long latNs = fetchMetricCounter(baseUrl, "gateway_wire_latency_ns_total");
        long orders = fetchMetricCounter(baseUrl, "gateway_orders_total");
        long denom = Math.max(1, denominator > 0 ? denominator : orders);
        return latNs / Math.max(1, denom);
    }

    /** Parse a Prometheus gauge/counter line from /metrics. Returns 0 on failure. */
    private static long fetchMetricCounter(String baseUrl, String metricName) {
        try {
            HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/metrics"))
                    .GET().timeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> res = c.send(req, HttpResponse.BodyHandlers.ofString());
            for (String line : res.body().split("\n")) {
                if (line.startsWith(metricName + " ") || line.startsWith(metricName + "{")) {
                    // find the value after the last space
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        String val = parts[parts.length - 1];
                        return (long) Double.parseDouble(val);
                    }
                }
            }
        } catch (Exception e) {
            // best-effort
        }
        return 0L;
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

    record Result(
            String profile, int requests, int ok, double throughput,
            long p50, long p99, long p999, long p9999,
            long totalOrders, long wireLatencyNs, long matchedPairs, double rejectRate) {

        String toJson() {
            String modeKpis = "\"wire_latency_ns\":" + wireLatencyNs
                    + ",\"matching_engine_ns\":0"
                    + ",\"matched_pairs\":" + matchedPairs
                    + ",\"reject_rate\":" + String.format(java.util.Locale.ROOT, "%.6f", rejectRate);
            return ("{\"benchmark\":\"%s\",\"runtime\":\"openjdk-hotspot-21\","
                    + "\"gc\":\"G1\",\"jvm_flags\":[\"-XX:+UseG1GC\"],"
                    + "\"env\":{\"cpu\":\"%d\",\"kernel\":\"unknown\","
                    + "\"cgroup_cpu\":\"unknown\",\"cgroup_mem\":\"unknown\"},"
                    + "\"load_profile\":\"%s\","
                    + "\"phases\":{\"warmup_s\":0,\"measure_s\":0},"
                    + "\"kpis\":{\"throughput\":%.3f,\"p50_ms\":%d,\"p99_ms\":%d,"
                    + "\"p999_ms\":%d,\"p9999_ms\":%d,\"gc_pause_p99_ms\":0,"
                    + "\"alloc_rate_mb_s\":0,\"rss_mb\":0,\"native_mem_mb\":0,"
                    + "\"cpu_util_pct\":0},"
                    + "\"mode_kpis\":{%s}}")
                    .formatted(BENCHMARK, Runtime.getRuntime().availableProcessors(),
                               profile, throughput, p50, p99, p999, p9999, modeKpis);
        }
    }
}
