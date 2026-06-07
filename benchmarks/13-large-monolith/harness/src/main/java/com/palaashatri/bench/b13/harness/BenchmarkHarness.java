package com.palaashatri.bench.b13.harness;

import java.io.IOException;
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
import java.util.Random;

public final class BenchmarkHarness {
    private static final String BENCHMARK = "13-large-monolith";
    private static final String MODE_KPIS_JSON = """
            "time_to_first_response_ms":0, "time_to_90pct_s":0, "compiled_methods":0
            """.strip();
    private static final String[] PROFILES = new String[]{"warmup-curve", "steady-state", "restart-cycle"};
    private static final RequestSpec[] REQUESTS = new RequestSpec[]{new RequestSpec("GET", "/api/customers/1001", "{}"), new RequestSpec("POST", "/api/orders", "{\"productId\":\"1001\",\"quantity\":1}"), new RequestSpec("GET", "/api/reports/daily", "{}"), new RequestSpec("GET", "/health", "{}")};

    private BenchmarkHarness() { }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parse(args);
        String profile = opts.getOrDefault("profile", PROFILES[0]);
        int requests = Integer.parseInt(opts.getOrDefault("requests", "25"));
        String baseUrl = opts.getOrDefault("base-url", System.getenv().getOrDefault("BASE_URL", "http://localhost:8080"));
        long seed = Long.parseLong(opts.getOrDefault("seed", "424242"));
        Result r = run(baseUrl, profile, requests, seed);
        Path out = Path.of(opts.getOrDefault("out", "results/results.json"));
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        Files.writeString(out, r.toJson() + System.lineSeparator());
        System.out.println(r.toJson());
    }

    static Result run(String baseUrl, String profile, int requests, long seed) throws InterruptedException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        Random random = new Random(seed ^ profile.hashCode());
        long[] latenciesMs = new long[Math.max(1, requests)];
        int ok = 0;
        for (int i = 0; i < latenciesMs.length; i++) {
            RequestSpec spec = REQUESTS[Math.floorMod(i + random.nextInt(REQUESTS.length), REQUESTS.length)];
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + spec.path())).timeout(Duration.ofSeconds(5));
            if ("POST".equals(spec.method())) {
                builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(spec.body(), StandardCharsets.UTF_8));
            } else {
                builder.GET();
            }
            long start = System.nanoTime();
            try {
                HttpResponse<String> res = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() >= 200 && res.statusCode() < 500) {
                    ok++;
                }
            } catch (IOException e) {
                // Preserve a schema-valid result even when the app is intentionally not running during harness smoke tests.
            }
            latenciesMs[i] = Math.max(1L, (System.nanoTime() - start) / 1_000_000L);
        }
        Arrays.sort(latenciesMs);
        double elapsedSeconds = Math.max(0.001D, Arrays.stream(latenciesMs).sum() / 1000.0D);
        double throughput = latenciesMs.length / elapsedSeconds;
        return new Result(profile, latenciesMs.length, ok, throughput, pct(latenciesMs, 50.0D), pct(latenciesMs, 99.0D), pct(latenciesMs, 99.9D), pct(latenciesMs, 99.99D));
    }

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
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    v = args[++i];
                }
                out.put(k, v);
            }
        }
        return out;
    }

    record RequestSpec(String method, String path, String body) { }

    record Result(String profile, int requests, int ok, double throughput, long p50, long p99, long p999, long p9999) {
        String toJson() {
            return """
                    {"benchmark":"%s","runtime":"openjdk-hotspot-21","gc":"G1","jvm_flags":["-XX:+UseG1GC"],"env":{"cpu":"%d","kernel":"unknown","cgroup_cpu":"unknown","cgroup_mem":"unknown"},"load_profile":"%s","phases":{"warmup_s":0,"measure_s":0},"kpis":{"throughput":%.3f,"p50_ms":%d,"p99_ms":%d,"p999_ms":%d,"p9999_ms":%d,"gc_pause_p99_ms":0,"alloc_rate_mb_s":0,"rss_mb":0,"native_mem_mb":0,"cpu_util_pct":0},"mode_kpis":{%s}}
                    """.formatted(BENCHMARK, Runtime.getRuntime().availableProcessors(), profile, throughput, p50, p99, p999, p9999, MODE_KPIS_JSON).trim();
        }
    }
}
