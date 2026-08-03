package com.palaashatri.bench.compat.harness;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Java 8-compatible closed-loop smoke harness. Numbers are diagnostic only. */
public final class CompatibilityHarness {
    public static void main(String[] args) throws Exception {
        String base = option(args, "base-url", "http://127.0.0.1:8080");
        int requests = Integer.parseInt(option(args, "requests", "25"));
        int threads = Integer.parseInt(option(args, "threads", "4"));
        String output = option(args, "out", "results/results.json");
        Result result = run(base, requests, threads);
        java.nio.file.Path path = Paths.get(output); if (path.getParent() != null) Files.createDirectories(path.getParent());
        Files.write(path, (result.json() + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
        System.out.println(result.json());
        if (result.failures > 0) throw new IllegalStateException(result.failures + " requests failed");
    }

    private static Result run(final String base, int requests, int threads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
        List<Future<Long>> futures = new ArrayList<Future<Long>>();
        final int[] failures = {0}; long started = System.nanoTime();
        for (int i = 0; i < requests; i++) {
            final int index = i;
            futures.add(pool.submit(new Callable<Long>() { public Long call() {
                long at = System.nanoTime();
                try {
                    HttpURLConnection connection = (HttpURLConnection) new URL(base + "/work").openConnection();
                    connection.setConnectTimeout(3000); connection.setReadTimeout(10000); connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json");
                    byte[] body = ("{\"seed\":" + (424242L + index) + ",\"size\":4096}").getBytes(StandardCharsets.UTF_8);
                    OutputStream output = connection.getOutputStream(); output.write(body); output.close();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
                    String line, response = ""; while ((line = reader.readLine()) != null) response += line; reader.close();
                    if (connection.getResponseCode() != 200 || response.indexOf("\"checksum\"") < 0) synchronized (failures) { failures[0]++; }
                } catch (Exception exception) { synchronized (failures) { failures[0]++; } }
                return Long.valueOf(System.nanoTime() - at);
            }}));
        }
        long[] latency = new long[requests]; for (int i = 0; i < requests; i++) latency[i] = futures.get(i).get().longValue();
        pool.shutdown(); long elapsed = System.nanoTime() - started; Arrays.sort(latency);
        return new Result(requests, failures[0], requests / (elapsed / 1_000_000_000.0), percentile(latency, .50), percentile(latency, .99));
    }
    private static double percentile(long[] values, double p) { int i = Math.min(values.length - 1, Math.max(0, (int) Math.ceil(values.length * p) - 1)); return values[i] / 1_000_000.0; }
    private static String option(String[] args, String name, String fallback) { String key = "--" + name; for (int i = 0; i + 1 < args.length; i++) if (key.equals(args[i])) return args[i + 1]; return fallback; }
    static final class Result { final int requests, failures; final double throughput, p50, p99; Result(int requests, int failures, double throughput, double p50, double p99) { this.requests=requests;this.failures=failures;this.throughput=throughput;this.p50=p50;this.p99=p99; }
        String json() { return String.format(Locale.ROOT, "{\"schema_version\":\"1.0.0\",\"benchmark\":\"00-runtime-compatibility\",\"run_kind\":\"smoke\",\"implementation_tier\":\"tier-1\",\"measurement_valid\":false,\"invalid_reasons\":[\"closed-loop smoke load is not measurement-valid\"],\"warnings\":[\"application telemetry unavailable\"],\"runtime\":null,\"gc\":null,\"jvm_flags\":null,\"phases\":{\"warmup_s\":null,\"measure_s\":null},\"kpis\":{\"throughput\":%.6f,\"p50_ms\":%.6f,\"p99_ms\":%.6f,\"gc_pause_p99_ms\":null,\"alloc_rate_mb_s\":null,\"rss_mb\":null,\"native_mem_mb\":null,\"cpu_util_pct\":null},\"mode_kpis\":{\"requests\":%d,\"failures\":%d}}", throughput,p50,p99,requests,failures); }
    }
}
