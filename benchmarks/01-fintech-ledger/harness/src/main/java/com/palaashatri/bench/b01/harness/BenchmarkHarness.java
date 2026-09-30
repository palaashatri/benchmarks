package com.palaashatri.bench.b01.harness;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public final class BenchmarkHarness {
    private static final RequestSpec[] REQUESTS = {
            new RequestSpec("GET", "/accounts/1001/balance", null),
            new RequestSpec("GET", "/accounts/1001/transactions", null),
            new RequestSpec("POST", "/transfers", "{\"from\":\"1001\",\"to\":\"1002\",\"amount_cents\":1}"),
            new RequestSpec("GET", "/health", null),
            new RequestSpec("GET", "/accounts/1002/balance", null)
    };

    private BenchmarkHarness() { }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        String baseUrl = options.getOrDefault("base-url", "http://127.0.0.1:8080");
        int requestCount = Integer.parseInt(options.getOrDefault("requests", "25"));
        int threads = Integer.parseInt(options.getOrDefault("threads", "4"));
        long seed = Long.parseLong(options.getOrDefault("seed", "424242"));
        Path output = Path.of(options.getOrDefault("out", "results/results.json"));
        Result result = run(baseUrl, requestCount, threads, seed);
        if (result.failures() > 0) {
            throw new IllegalStateException(result.failures() + " smoke requests failed");
        }
        if (output.getParent() != null) Files.createDirectories(output.getParent());
        Files.writeString(output, result.toJson() + System.lineSeparator());
        System.out.println(result.toJson());
    }

    static Result run(String baseUrl, int requestCount, int threads, long seed) throws Exception {
        int count = Math.max(1, requestCount);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, threads));
        Random random = new Random(seed);
        List<Future<Sample>> futures = new ArrayList<>(count);
        long wallStart = System.nanoTime();
        for (int index = 0; index < count; index++) {
            RequestSpec spec = REQUESTS[random.nextInt(REQUESTS.length)];
            futures.add(executor.submit(() -> execute(client, baseUrl, spec)));
        }
        executor.shutdown();
        long[] latencyNs = new long[count];
        AtomicInteger failures = new AtomicInteger();
        for (int index = 0; index < count; index++) {
            Sample sample = futures.get(index).get();
            latencyNs[index] = sample.latencyNs();
            if (!sample.success()) failures.incrementAndGet();
        }
        long wallNs = System.nanoTime() - wallStart;
        Arrays.sort(latencyNs);
        return new Result(count, failures.get(), count / (wallNs / 1_000_000_000.0),
                percentileMs(latencyNs, 50), percentileMs(latencyNs, 95),
                percentileMs(latencyNs, 99), percentileMs(latencyNs, 99.9),
                percentileMs(latencyNs, 99.99));
    }

    private static Sample execute(HttpClient client, String baseUrl, RequestSpec spec) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + spec.path()))
                .timeout(Duration.ofSeconds(5));
        if (spec.body() == null) {
            builder.GET();
        } else {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(spec.body(), StandardCharsets.UTF_8));
        }
        long started = System.nanoTime();
        try {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Sample(System.nanoTime() - started,
                    response.statusCode() >= 200 && response.statusCode() < 300
                            && !response.body().contains("\"error\""));
        } catch (Exception ignored) {
            return new Sample(System.nanoTime() - started, false);
        }
    }

    private static double percentileMs(long[] values, double percentile) {
        int index = Math.min(values.length - 1,
                Math.max(0, (int) Math.ceil(percentile / 100.0 * values.length) - 1));
        return values[index] / 1_000_000.0;
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < args.length; index++) {
            if (!args[index].startsWith("--")) continue;
            String key = args[index].substring(2);
            String value = index + 1 < args.length && !args[index + 1].startsWith("--")
                    ? args[++index] : "true";
            values.put(key, value);
        }
        return values;
    }

    record RequestSpec(String method, String path, String body) { }
    record Sample(long latencyNs, boolean success) { }
    record Result(int requests, int failures, double throughput, double p50Ms,
                  double p95Ms, double p99Ms, double p999Ms, double p9999Ms) {
        String toJson() {
            return "{"
                    + "\"schema_version\":\"1.0.0\","
                    + "\"benchmark\":\"01-fintech-ledger\","
                    + "\"run_kind\":\"smoke\","
                    + "\"implementation_tier\":\"tier-1\","
                    + "\"measurement_valid\":false,"
                    + "\"invalid_reasons\":[\"legacy closed-loop smoke load is not measurement-valid\"],"
                    + "\"warnings\":[\"application JVM telemetry is intentionally unavailable\"],"
                    + "\"runtime\":null,\"gc\":null,\"jvm_flags\":null,"
                    + "\"phases\":{\"warmup_s\":null,\"measure_s\":null},"
                    + "\"kpis\":{"
                    + "\"throughput\":" + format(throughput) + ','
                    + "\"p50_ms\":" + format(p50Ms) + ','
                    + "\"p95_ms\":" + format(p95Ms) + ','
                    + "\"p99_ms\":" + format(p99Ms) + ','
                    + "\"p999_ms\":" + format(p999Ms) + ','
                    + "\"p9999_ms\":" + format(p9999Ms) + ','
                    + "\"gc_pause_p99_ms\":null,\"alloc_rate_mb_s\":null,"
                    + "\"rss_mb\":null,\"native_mem_mb\":null,\"cpu_util_pct\":null},"
                    + "\"mode_kpis\":{\"requests\":" + requests + ",\"failures\":" + failures + "}"
                    + "}";
        }
        private static String format(double value) {
            return String.format(java.util.Locale.ROOT, "%.6f", value);
        }
    }
}
