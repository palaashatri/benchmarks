package com.palaashatri.bench.smoke;

import com.palaashatri.bench.common.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared functional smoke runner. It intentionally emits no performance KPIs. */
public final class SmokeHarness {
    public record Request(String method, String path, String body) {
        public static Request get(String path) { return new Request("GET", path, null); }
        public static Request post(String path, String body) { return new Request("POST", path, body); }
    }
    private SmokeHarness() { }

    public static void run(String benchmark, String[] args, Request... operations) throws Exception {
        Map<String, String> options = options(args);
        int requests = positive(options, "requests", 25, 1_000_000);
        int threads = positive(options, "threads", 4, 256);
        int runs = positive(options, "runs", 1, 100);
        int total = Math.multiplyExact(requests, runs);
        String base = options.getOrDefault("base-url", System.getenv().getOrDefault("BASE_URL", "http://127.0.0.1:8080"));
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        URI uri = URI.create(base);
        if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null)
            throw new IllegalArgumentException("base-url must be an HTTP URL");
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        Map<String, Object> identity = identity(client, base);
        var failures = Collections.synchronizedList(new ArrayList<String>());
        AtomicInteger ok = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(Math.min(threads, requests));
        final String target = base;
        try {
            for (int run = 0; run < runs; run++) {
                var futures = new ArrayList<java.util.concurrent.Future<?>>();
                for (int index = 0; index < requests; index++) {
                    Request operation = operations[index % operations.length];
                    futures.add(pool.submit(() -> {
                        try {
                            var response = send(client, target, operation);
                            Object body = Json.parseObject(response.body());
                            @SuppressWarnings("unchecked") var object = (Map<String, Object>) body;
                            if (response.statusCode() < 200 || response.statusCode() >= 300
                                    || object.containsKey("error") || Boolean.FALSE.equals(object.get("ok")))
                                throw new IOException("HTTP " + response.statusCode() + " " + response.body());
                            ok.incrementAndGet();
                        } catch (Exception failure) {
                            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                            synchronized (failures) {
                                if (failures.size() < 10) failures.add(operation.path() + ": " + failure.getMessage());
                            }
                        }
                    }));
                }
                for (var future : futures) future.get();
            }
        } finally { pool.shutdownNow(); }
        if (!identity.equals(identity(client, base))) throw new IOException("application identity changed during smoke run");
        var warnings = new ArrayList<String>();
        warnings.add("Functional smoke only; phased open-loop measurements and application telemetry are unavailable");
        if (options.containsKey("profile") || options.containsKey("seed"))
            warnings.add("Legacy profile/seed arguments do not select a performance workload");
        var result = new LinkedHashMap<String, Object>();
        result.put("schema_version", "1.0.0"); result.put("benchmark", benchmark);
        result.put("run_kind", "smoke"); result.put("implementation_tier", "tier-1");
        result.put("measurement_valid", false); result.put("invalid_reasons", List.of("functional_smoke_only"));
        result.put("warnings", warnings); result.put("kpis", null);
        result.put("requests", total); result.put("ok", ok.get()); result.put("errors", failures);
        result.put("runs", runs); result.put("profile", "functional-smoke");
        result.put("application_runtime", identity); result.put("application_pid", identity.get("pid"));
        result.put("runtime", identity.get("java_version")); result.put("gc", identity.get("collectors"));
        result.put("jvm_flags", identity.get("jvm_args"));
        Path output = Path.of(options.getOrDefault("out", "results/results.json"));
        if (output.getParent() != null) Files.createDirectories(output.getParent());
        String json = Json.stringify(result);
        Files.writeString(output, json + System.lineSeparator());
        System.out.println(json);
        if (ok.get() != total) throw new IOException("only " + ok.get() + " of " + total + " smoke requests succeeded");
    }

    private static HttpResponse<String> send(HttpClient client, String base, Request operation) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(base + operation.path())).timeout(Duration.ofSeconds(5));
        if (operation.body() == null) request.GET();
        else request.header("Content-Type", "application/json").method(operation.method(), HttpRequest.BodyPublishers.ofString(operation.body()));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, Object> identity(HttpClient client, String base) throws IOException, InterruptedException {
        var response = send(client, base, Request.get("/runtime"));
        if (response.statusCode() != 200) throw new IOException("application runtime identity is unavailable");
        var identity = Json.parseObject(response.body());
        if (Json.number(identity, "pid", -1) < 1 || !(identity.get("java_version") instanceof String)
                || !(identity.get("jvm_args") instanceof List) || !(identity.get("collectors") instanceof List))
            throw new IOException("incomplete application runtime identity");
        return identity;
    }

    private static int positive(Map<String, String> options, String key, int fallback, int maximum) {
        int value = Integer.parseInt(options.getOrDefault(key, Integer.toString(fallback)));
        if (value < 1 || value > maximum) throw new IllegalArgumentException(key + " must be between 1 and " + maximum);
        return value;
    }

    private static Map<String, String> options(String[] args) {
        Set<String> supported = Set.of("base-url", "requests", "threads", "runs", "out", "profile", "seed");
        var options = new LinkedHashMap<String, String>();
        for (int index = 0; index < args.length; index++) {
            String key = args[index];
            if (!key.startsWith("--") || !supported.contains(key.substring(2)) || index + 1 == args.length)
                throw new IllegalArgumentException("unsupported or missing argument: " + key);
            options.put(key.substring(2), args[++index]);
        }
        return options;
    }
}
