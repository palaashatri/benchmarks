package com.palaashatri.bench.load;

import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;

/** Open-loop HTTP load generator for the ledger contract. */
public final class OpenLoopLedger {
    private OpenLoopLedger() { }

    public static void main(String[] arguments) throws Exception {
        Map<String, String> options = options(arguments);
        String baseUrl = options.getOrDefault("base-url", "http://127.0.0.1:8080");
        double targetRate = Double.parseDouble(options.getOrDefault("target-rate", "100"));
        int warmupSeconds = Integer.parseInt(options.getOrDefault("warmup-seconds", "3"));
        int measureSeconds = Integer.parseInt(options.getOrDefault("measure-seconds", "5"));
        int threads = Integer.parseInt(options.getOrDefault("threads", "16"));
        long seed = Long.parseLong(options.getOrDefault("seed", "424242"));
        String runKind = options.getOrDefault("run-kind", "smoke");
        Path output = Path.of(options.getOrDefault("out", "build/results.json"));
        Path histogramOutput = Path.of(
                options.getOrDefault("histogram-out", "build/latency.hgrm"));

        if (!(targetRate > 0) || warmupSeconds < 0 || measureSeconds < 1 || threads < 1) {
            throw new IllegalArgumentException("invalid rate, phase duration, or thread count");
        }
        Result result = execute(
                baseUrl,
                targetRate,
                warmupSeconds,
                measureSeconds,
                threads,
                seed,
                histogramOutput);
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, result.toJson(runKind) + System.lineSeparator());
        System.out.println(result.toJson(runKind));
        if (result.measureErrors > 0 || result.measureCompleted != result.measureScheduled) {
            throw new IllegalStateException(
                    "load run failed: completed=" + result.measureCompleted
                            + " scheduled=" + result.measureScheduled
                            + " errors=" + result.measureErrors);
        }
    }

    static Result execute(
            String baseUrl,
            double targetRate,
            int warmupSeconds,
            int measureSeconds,
            int threads,
            long seed,
            Path histogramOutput) throws Exception {
        long intervalNs = Math.max(1L, Math.round(1_000_000_000.0 / targetRate));
        long warmupCount = Math.round(targetRate * warmupSeconds);
        long measureCount = Math.round(targetRate * measureSeconds);
        long totalCount = warmupCount + measureCount;
        if (totalCount > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("planned request count exceeds implementation limit");
        }

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .executor(executor)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        Recorder warmupRecorder = new Recorder(3);
        Recorder measureRecorder = new Recorder(3);
        AtomicLong warmupCompleted = new AtomicLong();
        AtomicLong measureCompleted = new AtomicLong();
        AtomicLong warmupErrors = new AtomicLong();
        AtomicLong measureErrors = new AtomicLong();
        CountDownLatch completion = new CountDownLatch((int) totalCount);
        Random random = new Random(seed);

        long epoch = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
        long measureStart = epoch + warmupCount * intervalNs;
        for (long index = 0; index < totalCount; index++) {
            long scheduled = epoch + index * intervalNs;
            waitUntil(scheduled);
            boolean measurement = index >= warmupCount;
            RequestSpec spec = request(random);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + spec.path))
                    .timeout(Duration.ofSeconds(10));
            if (spec.body == null) {
                builder.GET();
            } else {
                builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                spec.body, StandardCharsets.UTF_8));
            }
            client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                    .whenComplete((response, failure) -> {
                        long latency = Math.max(1L, System.nanoTime() - scheduled);
                        Recorder recorder = measurement ? measureRecorder : warmupRecorder;
                        recorder.recordValueWithExpectedInterval(latency, intervalNs);
                        boolean successful = failure == null
                                && response.statusCode() >= 200
                                && response.statusCode() < 300
                                && !response.body().contains("\"error\"");
                        if (measurement) {
                            measureCompleted.incrementAndGet();
                            if (!successful) measureErrors.incrementAndGet();
                        } else {
                            warmupCompleted.incrementAndGet();
                            if (!successful) warmupErrors.incrementAndGet();
                        }
                        completion.countDown();
                    });
        }

        long timeoutSeconds = Math.max(30L, warmupSeconds + measureSeconds + 30L);
        boolean finished = completion.await(timeoutSeconds, TimeUnit.SECONDS);
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
        if (!finished) {
            throw new IllegalStateException("timed out waiting for in-flight requests");
        }

        Histogram warmup = warmupRecorder.getIntervalHistogram();
        Histogram measurement = measureRecorder.getIntervalHistogram();
        Files.createDirectories(histogramOutput.toAbsolutePath().getParent());
        try (PrintStream stream = new PrintStream(
                Files.newOutputStream(histogramOutput), false, StandardCharsets.UTF_8)) {
            measurement.outputPercentileDistribution(stream, 5, 1_000_000.0);
        }

        double throughput = measureCompleted.get() / (double) measureSeconds;
        return new Result(
                targetRate,
                intervalNs,
                warmupSeconds,
                measureSeconds,
                warmupCount,
                measureCount,
                warmupCompleted.get(),
                measureCompleted.get(),
                warmupErrors.get(),
                measureErrors.get(),
                throughput,
                warmup,
                measurement,
                measureStart,
                histogramOutput.toString());
    }

    private static RequestSpec request(Random random) {
        int choice = random.nextInt(100);
        int account = 1 + random.nextInt(2_000);
        if (choice < 50) {
            int target = 1 + random.nextInt(1_999);
            if (target >= account) target++;
            long amount = 1 + random.nextInt(100);
            return new RequestSpec(
                    "/transfers",
                    "{\"from\":\"" + account + "\",\"to\":\"" + target
                            + "\",\"amount_cents\":" + amount + "}");
        }
        if (choice < 80) {
            return new RequestSpec("/accounts/" + account + "/balance", null);
        }
        return new RequestSpec("/accounts/" + account + "/transactions", null);
    }

    private static void waitUntil(long scheduled) {
        while (true) {
            long remaining = scheduled - System.nanoTime();
            if (remaining <= 0) return;
            if (remaining > 200_000) {
                LockSupport.parkNanos(remaining - 100_000);
            } else {
                Thread.onSpinWait();
            }
        }
    }

    private static Map<String, String> options(String[] arguments) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < arguments.length; index++) {
            String argument = arguments[index];
            if (!argument.startsWith("--")) continue;
            String key = argument.substring(2);
            String value = "true";
            if (index + 1 < arguments.length && !arguments[index + 1].startsWith("--")) {
                value = arguments[++index];
            }
            values.put(key, value);
        }
        return values;
    }

    record RequestSpec(String path, String body) { }

    static final class Result {
        final double targetRate;
        final long expectedIntervalNs;
        final int warmupSeconds;
        final int measureSeconds;
        final long warmupScheduled;
        final long measureScheduled;
        final long warmupCompleted;
        final long measureCompleted;
        final long warmupErrors;
        final long measureErrors;
        final double throughput;
        final Histogram warmup;
        final Histogram measurement;
        final long measurementStartNano;
        final String histogramPath;

        Result(
                double targetRate,
                long expectedIntervalNs,
                int warmupSeconds,
                int measureSeconds,
                long warmupScheduled,
                long measureScheduled,
                long warmupCompleted,
                long measureCompleted,
                long warmupErrors,
                long measureErrors,
                double throughput,
                Histogram warmup,
                Histogram measurement,
                long measurementStartNano,
                String histogramPath) {
            this.targetRate = targetRate;
            this.expectedIntervalNs = expectedIntervalNs;
            this.warmupSeconds = warmupSeconds;
            this.measureSeconds = measureSeconds;
            this.warmupScheduled = warmupScheduled;
            this.measureScheduled = measureScheduled;
            this.warmupCompleted = warmupCompleted;
            this.measureCompleted = measureCompleted;
            this.warmupErrors = warmupErrors;
            this.measureErrors = measureErrors;
            this.throughput = throughput;
            this.warmup = warmup;
            this.measurement = measurement;
            this.measurementStartNano = measurementStartNano;
            this.histogramPath = histogramPath;
        }

        String toJson(String runKind) {
            return "{"
                    + "\"schema_version\":\"1.0.0\","
                    + "\"benchmark\":\"01-fintech-ledger\","
                    + "\"run_kind\":\"" + escape(runKind) + "\","
                    + "\"implementation_tier\":\"tier-1\","
                    + "\"measurement_valid\":false,"
                    + "\"invalid_reasons\":[\"application-process telemetry and repetition aggregation are required before promotion\"],"
                    + "\"warnings\":[],"
                    + "\"load_model\":\"open-loop\","
                    + "\"coordinated_omission_corrected\":true,"
                    + "\"target_rate\":" + format(targetRate) + ','
                    + "\"expected_interval_ns\":" + expectedIntervalNs + ','
                    + "\"phases\":{\"warmup_s\":" + warmupSeconds
                    + ",\"measure_s\":" + measureSeconds + "},"
                    + "\"scheduled\":{\"warmup\":" + warmupScheduled
                    + ",\"measurement\":" + measureScheduled + "},"
                    + "\"completed\":{\"warmup\":" + warmupCompleted
                    + ",\"measurement\":" + measureCompleted + "},"
                    + "\"errors\":{\"warmup\":" + warmupErrors
                    + ",\"measurement\":" + measureErrors + "},"
                    + "\"kpis\":{"
                    + "\"throughput\":" + format(throughput) + ','
                    + "\"p50_ms\":" + millis(measurement.getValueAtPercentile(50)) + ','
                    + "\"p95_ms\":" + millis(measurement.getValueAtPercentile(95)) + ','
                    + "\"p99_ms\":" + millis(measurement.getValueAtPercentile(99)) + ','
                    + "\"p999_ms\":" + millis(measurement.getValueAtPercentile(99.9)) + ','
                    + "\"p9999_ms\":" + millis(measurement.getValueAtPercentile(99.99)) + ','
                    + "\"max_ms\":" + millis(measurement.getMaxValue()) + ','
                    + "\"mean_ms\":" + format(measurement.getMean() / 1_000_000.0) + ','
                    + "\"gc_pause_p99_ms\":null,\"alloc_rate_mb_s\":null,"
                    + "\"rss_mb\":null,\"native_mem_mb\":null,\"cpu_util_pct\":null},"
                    + "\"histogram_path\":\"" + escape(histogramPath) + "\""
                    + "}";
        }

        private static String millis(long nanos) {
            return format(nanos / 1_000_000.0);
        }
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.6f", value);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
