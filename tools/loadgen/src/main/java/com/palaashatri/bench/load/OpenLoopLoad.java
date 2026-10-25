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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;

/**
 * Open-loop HTTP load generator with coordinated-omission correction.
 *
 * <p>Requests are scheduled at a fixed rate and latency is measured from the intended send
 * time, so a slow server cannot hide its own queueing. The request mix comes from a contract
 * file (see {@link Mix}), so the generator carries no workload knowledge. Output is a load
 * envelope only: it makes no validity claim, and the Tier 2 runner decides that.
 */
public final class OpenLoopLoad {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]*)}");

    private OpenLoopLoad() { }

    public static void main(String[] arguments) throws Exception {
        Map<String, String> options = options(arguments);
        String baseUrl = options.getOrDefault("base-url", "http://127.0.0.1:8080");
        double targetRate = Double.parseDouble(options.getOrDefault("target-rate", "100"));
        int warmupSeconds = Integer.parseInt(options.getOrDefault("warmup-seconds", "3"));
        int measureSeconds = Integer.parseInt(options.getOrDefault("measure-seconds", "5"));
        int threads = Integer.parseInt(options.getOrDefault("threads", "16"));
        long seed = Long.parseLong(options.getOrDefault("seed", "424242"));
        String benchmark = options.getOrDefault("benchmark", "unspecified");
        Path output = Path.of(options.getOrDefault("out", "build/results.json"));
        Path histogramOutput = Path.of(options.getOrDefault("histogram-out", "build/latency.hgrm"));
        if (!options.containsKey("mix")) {
            throw new IllegalArgumentException("--mix is required: the request mix is part of the workload contract");
        }
        Mix mix = Mix.load(Path.of(options.get("mix")));

        if (!(targetRate > 0) || warmupSeconds < 0 || measureSeconds < 1 || threads < 1) {
            throw new IllegalArgumentException("invalid rate, phase duration, or thread count");
        }
        Result result = execute(baseUrl, targetRate, warmupSeconds, measureSeconds, threads, seed, mix, histogramOutput);
        Files.createDirectories(output.toAbsolutePath().getParent());
        String json = result.toJson(benchmark);
        Files.writeString(output, json + System.lineSeparator());
        System.out.println(json);
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
            Mix mix,
            Path histogramOutput) throws Exception {
        long intervalNs = Math.max(1L, Math.round(1_000_000_000.0 / targetRate));
        long warmupCount = Math.round(targetRate * warmupSeconds);
        long measureCount = Math.round(targetRate * measureSeconds);
        long totalCount = warmupCount + measureCount;
        if (totalCount > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("planned request count exceeds implementation limit");
        }

        // One bucket per phase second. Responses that arrive after the last second are
        // counted in the last bucket, so the series never ends on an artificial empty bucket.
        int seconds = warmupSeconds + measureSeconds;
        AtomicLongArray scheduledPerSecond = new AtomicLongArray(seconds);
        AtomicLongArray completedPerSecond = new AtomicLongArray(seconds);
        Histogram[] latencyPerSecond = new Histogram[seconds];
        for (int second = 0; second < seconds; second++) latencyPerSecond[second] = new Histogram(3);

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
            int second = Math.min(seconds - 1, (int) ((scheduled - epoch) / 1_000_000_000L));
            scheduledPerSecond.incrementAndGet(second);
            Mix.Request spec = mix.next(random);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + spec.path()))
                    .timeout(Duration.ofSeconds(10));
            if (spec.body() == null) {
                builder.method(spec.method(), HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/json")
                        .method(spec.method(), HttpRequest.BodyPublishers.ofString(spec.body(), StandardCharsets.UTF_8));
            }
            client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                    .whenComplete((response, failure) -> {
                        long latency = Math.max(1L, System.nanoTime() - scheduled);
                        Recorder recorder = measurement ? measureRecorder : warmupRecorder;
                        recorder.recordValueWithExpectedInterval(latency, intervalNs);
                        // Latency is attributed to the second the request was meant to be sent;
                        // delivered throughput is attributed to the second the response arrived.
                        synchronized (latencyPerSecond[second]) {
                            latencyPerSecond[second].recordValueWithExpectedInterval(latency, intervalNs);
                        }
                        int doneSecond = Math.max(0, Math.min(seconds - 1,
                                (int) ((System.nanoTime() - epoch) / 1_000_000_000L)));
                        completedPerSecond.incrementAndGet(doneSecond);
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
        List<Long> scheduledSeries = new ArrayList<>();
        List<Long> completedSeries = new ArrayList<>();
        List<Double> p50Series = new ArrayList<>();
        for (int second = 0; second < seconds; second++) {
            scheduledSeries.add(scheduledPerSecond.get(second));
            completedSeries.add(completedPerSecond.get(second));
            Histogram bucket = latencyPerSecond[second];
            p50Series.add(bucket.getTotalCount() == 0 ? null : bucket.getValueAtPercentile(50) / 1_000_000.0);
        }
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
                histogramOutput.toString(),
                scheduledSeries,
                completedSeries,
                p50Series,
                mix.describe());
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

    /**
     * Weighted request templates from a tab-separated contract file. Each non-blank line is
     * {@code weight<TAB>method<TAB>path<TAB>body}; the body column is optional. Placeholders:
     * {@code ${int:MIN:MAX}}, {@code ${float:MIN:MAX}}, {@code ${pick:A|B|C}},
     * {@code ${NAME=expr}} to capture a value for reuse in the same request, {@code ${NAME}} to
     * reuse it, and {@code ${distinct:MIN:MAX:NAME}} for an int in range that differs from NAME.
     */
    static final class Mix {
        private final List<Entry> entries;
        private final long totalWeight;

        private Mix(List<Entry> entries) {
            this.entries = entries;
            this.totalWeight = entries.stream().mapToLong(Entry::weight).sum();
        }

        static Mix load(Path file) throws Exception {
            List<Entry> entries = new ArrayList<>();
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] columns = line.split("\t", -1);
                if (columns.length < 3) {
                    throw new IllegalArgumentException(file + ":" + (index + 1) + ": expected weight, method, path[, body]");
                }
                int weight;
                try {
                    weight = Integer.parseInt(columns[0].trim());
                } catch (NumberFormatException exception) {
                    throw new IllegalArgumentException(file + ":" + (index + 1) + ": weight must be an integer");
                }
                if (weight <= 0) throw new IllegalArgumentException(file + ":" + (index + 1) + ": weight must be positive");
                String method = columns[1].trim();
                if (!method.equals("GET") && !method.equals("POST") && !method.equals("PUT") && !method.equals("DELETE")) {
                    throw new IllegalArgumentException(file + ":" + (index + 1) + ": unsupported method " + method);
                }
                String body = columns.length > 3 && !columns[3].isEmpty() ? columns[3] : null;
                Entry entry = new Entry(weight, method, columns[2], body);
                try {
                    entry.expand(new Random(0));
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException(file + ":" + (index + 1) + ": " + exception.getMessage());
                }
                entries.add(entry);
            }
            if (entries.isEmpty()) throw new IllegalArgumentException(file + ": no request templates");
            return new Mix(entries);
        }

        Request next(Random random) {
            long choice = random.nextLong(totalWeight);
            for (Entry entry : entries) {
                choice -= entry.weight();
                if (choice < 0) return entry.expand(random);
            }
            throw new IllegalStateException("unreachable: weights are positive");
        }

        String describe() {
            StringBuilder out = new StringBuilder("[");
            for (Entry entry : entries) {
                if (out.length() > 1) out.append(',');
                out.append("{\"weight\":").append(entry.weight())
                        .append(",\"method\":\"").append(entry.method()).append("\"")
                        .append(",\"path\":\"").append(escape(entry.path())).append("\"}");
            }
            return out.append(']').toString();
        }

        record Request(String method, String path, String body) { }

        record Entry(int weight, String method, String path, String body) {
            Request expand(Random random) {
                Map<String, String> captured = new HashMap<>();
                String expandedPath = expandTemplate(path, random, captured);
                String expandedBody = body == null ? null : expandTemplate(body, random, captured);
                return new Request(method, expandedPath, expandedBody);
            }
        }

        static String expandTemplate(String template, Random random, Map<String, String> captured) {
            Matcher matcher = PLACEHOLDER.matcher(template);
            StringBuilder out = new StringBuilder();
            while (matcher.find()) {
                String replacement = placeholder(matcher.group(1), random, captured);
                matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(out);
            return out.toString();
        }

        private static String placeholder(String token, Random random, Map<String, String> captured) {
            // A capture is NAME=expression, where NAME is everything before the first '='
            // and contains no ':'. A '=' that follows a ':' belongs to a pick choice.
            int equals = token.indexOf('=');
            int colon = token.indexOf(':');
            boolean capture = equals > 0 && (colon < 0 || colon > equals);
            if (capture) {
                String name = token.substring(0, equals);
                String value = expression(token.substring(equals + 1), random, captured);
                captured.put(name, value);
                return value;
            }
            if (captured.containsKey(token)) return captured.get(token);
            return expression(token, random, captured);
        }

        private static String expression(String expression, Random random, Map<String, String> captured) {
            String[] parts = expression.split(":");
            switch (parts[0]) {
                case "int": {
                    long low = parseLong(parts, 1, 3, expression);
                    long high = parseLong(parts, 2, 3, expression);
                    if (high < low) throw new IllegalArgumentException("empty range in ${" + expression + "}");
                    return Long.toString(low + random.nextLong(high - low + 1));
                }
                case "float": {
                    double low = Double.parseDouble(parts[1]);
                    double high = Double.parseDouble(parts[2]);
                    return String.format(Locale.ROOT, "%.6f", low + random.nextDouble() * (high - low));
                }
                case "pick": {
                    String[] choices = expression.substring("pick:".length()).split("\\|");
                    return choices[random.nextInt(choices.length)];
                }
                case "distinct": {
                    long low = parseLong(parts, 1, 4, expression);
                    long high = parseLong(parts, 2, 4, expression);
                    String other = parts[3];
                    if (!captured.containsKey(other)) {
                        throw new IllegalArgumentException("${" + expression + "} refers to uncaptured " + other);
                    }
                    long excluded = Long.parseLong(captured.get(other));
                    long value = low + random.nextLong(high - low);
                    if (value >= excluded) value++;
                    if (value > high) throw new IllegalArgumentException("range too small in ${" + expression + "}");
                    return Long.toString(value);
                }
                default:
                    throw new IllegalArgumentException("unknown placeholder ${" + expression + "}");
            }
        }

        private static long parseLong(String[] parts, int index, int expected, String expression) {
            if (parts.length != expected) {
                throw new IllegalArgumentException("malformed placeholder ${" + expression + "}");
            }
            try {
                return Long.parseLong(parts[index]);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("not an integer in ${" + expression + "}");
            }
        }
    }

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
        final List<Long> scheduledSeries;
        final List<Long> completedSeries;
        final List<Double> p50Series;
        final String mixDescription;

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
                String histogramPath,
                List<Long> scheduledSeries,
                List<Long> completedSeries,
                List<Double> p50Series,
                String mixDescription) {
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
            this.scheduledSeries = scheduledSeries;
            this.completedSeries = completedSeries;
            this.p50Series = p50Series;
            this.mixDescription = mixDescription;
        }

        String toJson(String benchmark) {
            return "{"
                    + "\"schema_version\":\"1.0.0\","
                    + "\"benchmark\":\"" + escape(benchmark) + "\","
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
                    + "\"mean_ms\":" + format(measurement.getMean() / 1_000_000.0) + "},"
                    + "\"series\":{"
                    + "\"bucket_seconds\":1,"
                    + "\"scheduled\":" + longList(scheduledSeries) + ','
                    + "\"scheduled_bucketed_by\":\"intended_send_time\","
                    + "\"completed\":" + longList(completedSeries) + ','
                    + "\"completed_bucketed_by\":\"response_time\","
                    + "\"p50_ms\":" + doubleList(p50Series) + ','
                    + "\"p50_bucketed_by\":\"intended_send_time\""
                    + "},"
                    + "\"mix\":" + mixDescription + ','
                    + "\"histogram_path\":\"" + escape(histogramPath) + "\""
                    + "}";
        }

        private static String millis(long nanos) {
            return format(nanos / 1_000_000.0);
        }

        private static String longList(List<Long> values) {
            StringBuilder out = new StringBuilder("[");
            for (Long value : values) {
                if (out.length() > 1) out.append(',');
                out.append(value);
            }
            return out.append(']').toString();
        }

        private static String doubleList(List<Double> values) {
            StringBuilder out = new StringBuilder("[");
            for (Double value : values) {
                if (out.length() > 1) out.append(',');
                out.append(value == null ? "null" : format(value));
            }
            return out.append(']').toString();
        }
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
