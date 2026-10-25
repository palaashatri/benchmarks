package com.palaashatri.bench.ctl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Measurement lanes for workloads whose unit of work is not an HTTP request on the Tier 2 engine.
 *
 * <p>{@code startup}: 07-coldstart-suite. One sample is one child JVM started and checked by the application.
 * {@code jobs}: 08-etl-batch. One sample is one batch job, submitted on an open-loop schedule and timed from its
 * intended submission time, so a late submission is charged to the job. {@code chat}: 06-massive-chat-loom. One
 * repetition is one run of the 06 driver against a fresh application.
 *
 * <p>Every lane takes the same gates as the Tier 2 engine ({@link Tier2#sharedReasons}): host preflight before and
 * after, five repetitions, and identical critical host state. Each lane adds its own correctness gates and a
 * spread gate on its primary metric. A lane's workload is Tier 1 in the catalogue, so its envelope stays invalid
 * even when every gate passes: benchmark results are permitted only for Tier 2 or Tier 3 workloads.
 * Failed samples are counted, never dropped. Unknown values are null, never zero.
 */
public final class Lanes {
    static final List<String> MODES = List.of("default", "no-cds", "appcds");
    static final String STARTUP_MAIN = "com.palaashatri.bench.b07.app.BenchmarkApp";
    static final String JOBS_MAIN = "com.palaashatri.bench.b08.app.BenchmarkApp";
    static final String CHAT_DRIVER_MAIN = "com.palaashatri.bench.b06.harness.ChatLoad";
    /** The application reports to the microsecond; the response carries three decimals. */
    static final double RECORDED_TOLERANCE_MS = 0.0006;
    static final String TIER_ONE_POLICY =
            "workload is Tier 1: benchmark results are permitted only for Tier 2 or Tier 3 workloads";

    private Lanes() {
    }

    /** A lane that could not run. The run is abandoned rather than reported with partial data. */
    public static final class LaneError extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public LaneError(String message) {
            super(message);
        }
    }

    /** One application process: started, identity-checked, used, and stopped by the lane. */
    static final class Application implements AutoCloseable {
        final Process process;
        final String base;
        final Map<String, Object> identity;

        private Application(Process process, String base, Map<String, Object> identity) {
            this.process = process;
            this.base = base;
            this.identity = identity;
        }

        static Application start(String javaExecutable, String classpath, String mainClass, List<String> jvmOptions,
                                 Path temporary) {
            String token = "lane-" + ProcessHandle.current().pid() + "-" + UUID.randomUUID();
            Path portFile = temporary.resolve("ready.port");
            List<String> command = new ArrayList<>();
            command.add(javaExecutable);
            command.addAll(jvmOptions);
            command.add("-cp");
            command.add(classpath);
            command.add(mainClass);
            command.add("0");
            ProcessBuilder builder = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(temporary.resolve("app.log").toFile());
            Map<String, String> environment = builder.environment();
            for (String removed : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) {
                environment.remove(removed);
            }
            environment.put("BENCH_RUN_TOKEN", token);
            environment.put("BENCH_PORT_FILE", portFile.toString());
            Process process;
            try {
                process = builder.start();
            } catch (IOException exception) {
                throw new LaneError("cannot start the application: " + exception.getMessage());
            }
            long deadline = System.nanoTime() + Duration.ofSeconds(180).toNanos();
            String base;
            while (true) {
                if (!process.isAlive()) {
                    throw new LaneError("application exited with " + process.exitValue() + " before readiness");
                }
                String port = read(portFile);
                if (port != null && !port.isEmpty()) {
                    base = "http://127.0.0.1:" + Integer.parseInt(port);
                    break;
                }
                if (System.nanoTime() > deadline) {
                    process.destroyForcibly();
                    throw new LaneError("application did not publish a port");
                }
                sleep(50);
            }
            Application application = new Application(process, base, Map.of());
            Map<String, Object> identity = application.getJson("/runtime", 60);
            if (!token.equals(identity.get("run_token")) || !(identity.get("pid") instanceof Number pid)
                    || pid.longValue() != process.pid()) {
                application.close();
                throw new LaneError("application identity does not match the process this lane started");
            }
            return new Application(process, base, identity);
        }

        Map<String, Object> getJson(String path, int timeoutSeconds) {
            Response response = exchange("GET", path, null, timeoutSeconds);
            if (response.status() != 200) {
                throw new LaneError("GET " + path + " returned HTTP " + response.status());
            }
            return Json.asMap(Json.parse(response.body()));
        }

        Response post(String path, Object body, int timeoutSeconds) {
            return exchange("POST", path, Json.write(body), timeoutSeconds);
        }

        Response exchange(String method, String path, String body, int timeoutSeconds) {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(base + path).openConnection();
                connection.setRequestMethod(method);
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(timeoutSeconds * 1000);
                if (body != null) {
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/json");
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    connection.setFixedLengthStreamingMode(bytes.length);
                    try (OutputStream out = connection.getOutputStream()) {
                        out.write(bytes);
                    }
                }
                int status = connection.getResponseCode();
                InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                String text = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                connection.disconnect();
                return new Response(status, text);
            } catch (IOException exception) {
                throw new LaneError(method + " " + path + " failed: " + exception.getMessage());
            }
        }

        @Override
        public void close() {
            if (process.isAlive()) {
                process.destroy();
                try {
                    if (!process.waitFor(30, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        process.waitFor(30, TimeUnit.SECONDS);
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }
        }
    }

    record Response(int status, String body) {
    }

    /** Statistics over the known values. Missing values are excluded from the statistics and never zeroed. */
    static Map<String, Object> summarize(List<Double> values, long seed) {
        List<Double> known = new ArrayList<>();
        for (Double value : values) {
            if (value != null) {
                known.add(value);
            }
        }
        if (known.isEmpty()) {
            return Json.map("count", 0, "median", null, "median_absolute_deviation", null,
                    "coefficient_of_variation", null,
                    "bootstrap_mean_ci", Json.map("low", null, "high", null, "confidence", 0.95),
                    "minimum", null, "maximum", null, "outliers", List.of());
        }
        return Json.map(
                "count", known.size(),
                "median", Stats.median(known),
                "median_absolute_deviation", Stats.medianAbsoluteDeviation(known),
                "coefficient_of_variation", Stats.coefficientOfVariation(known),
                "bootstrap_mean_ci", Stats.bootstrapInterval(known, seed),
                "minimum", known.stream().mapToDouble(Double::doubleValue).min().orElseThrow(),
                "maximum", known.stream().mapToDouble(Double::doubleValue).max().orElseThrow(),
                "outliers", Stats.describeOutliers("sample", known, sampleLabels(known.size())).get("outliers"));
    }

    /** Sample numbers 1..count, for outlier reports that name the sample an outlier came from. */
    static List<Long> sampleLabels(int count) {
        List<Long> labels = new ArrayList<>();
        for (long index = 1; index <= count; index++) {
            labels.add(index);
        }
        return labels;
    }

    /**
     * A lane's envelope. The shared gates decide the host and repetition verdict; the lane adds its own reasons.
     * Benchmark results are refused for Tier 1 workloads, whatever the gates say.
     */
    static Map<String, Object> envelope(String benchmark, String lane, String tier, String runKind,
                                        List<Map<String, Object>> repetitions, Map<String, Object> before,
                                        Map<String, Object> after, List<String> laneReasons, List<String> warnings,
                                        Map<String, Object> parameters, Map<String, Object> aggregate) {
        Map<String, Object> preflight = Tier2.combinePreflight(before, after);
        List<String> reasons = new ArrayList<>(Tier2.sharedReasons(runKind, repetitions, preflight));
        if ("benchmark".equals(runKind) && !List.of("tier-2", "tier-3").contains(tier)) {
            reasons.add(TIER_ONE_POLICY);
        }
        reasons.addAll(laneReasons);
        List<String> invalid = List.copyOf(new LinkedHashSet<>(reasons));
        Object identity = repetitions.isEmpty() ? null : repetitions.get(0).get("application_runtime");
        return Json.map(
                "schema_version", "1.0.0",
                "benchmark", benchmark,
                "lane", lane,
                "run_kind", runKind,
                "implementation_tier", tier,
                "measurement_valid", invalid.isEmpty(),
                "invalid_reasons", invalid,
                "warnings", warnings,
                "application_runtime", identity,
                "preflight", Json.map("before", before, "after", after, "combined", preflight),
                "parameters", parameters,
                "repetitions", repetitions,
                "aggregate", aggregate);
    }

    private static Map<String, Object> preflightNow(Repo repo) {
        return HostEnvironment.preflight(HostEnvironment.fingerprint(repo));
    }

    /** One value per repetition from a nested path, or null where the path is missing. */
    static List<Double> valuesAt(List<Map<String, Object>> repetitions, String... path) {
        List<Double> values = new ArrayList<>();
        for (Map<String, Object> repetition : repetitions) {
            Object value = repetition;
            for (String key : path) {
                value = value instanceof Map<?, ?> map ? map.get(key) : null;
            }
            values.add(value instanceof Number number ? number.doubleValue() : null);
        }
        return values;
    }

    /** The startup lane: every mode, several child starts each, cross-checked against the application's own record. */
    public static Map<String, Object> startup(String javaExecutable, String classpath, int samples, int repetitions,
                                              String runKind, String tier, Path temporary, Repo repo) {
        Map<String, Object> before = preflightNow(repo);
        List<Map<String, Object>> runs = new ArrayList<>();
        for (int index = 1; index <= repetitions; index++) {
            runs.add(startupRepetition(index, javaExecutable, classpath, samples,
                    temporary.resolve("repetition-" + index), repo));
        }
        Map<String, Object> after = preflightNow(repo);
        List<String> reasons = new ArrayList<>();
        Map<String, Object> aggregate = new LinkedHashMap<>();
        long seed = 1;
        for (String mode : MODES) {
            List<Double> medians = valuesAt(runs, "modes", mode, "time_to_health_ms", "median");
            aggregate.put(mode, Json.map("median_per_repetition", summarize(medians, seed++)));
            String spread = Tier2.spreadReason(mode + " time to health (median per repetition)", medians);
            if (spread != null) {
                reasons.add(spread);
            }
        }
        for (Map<String, Object> run : runs) {
            if (!Boolean.TRUE.equals(run.get("app_recorded_samples_match_driver"))) {
                reasons.add("application-recorded startup times disagree with the driver in repetition "
                        + run.get("repetition"));
            }
        }
        List<String> warnings = List.of(
                "the page cache is warm; a cold page cache needs root, which this lane does not use",
                "times are the application's own spawn-to-ready measurements, read per response",
                "CPU and resident memory of the child JVMs are not sampled by this lane");
        return envelope("07-coldstart-suite", "startup", tier, runKind, runs, before, after, reasons, warnings,
                Json.map("samples_per_mode", samples, "repetitions", repetitions), aggregate);
    }

    private static Map<String, Object> startupRepetition(int index, String javaExecutable, String classpath, int samples,
                                                          Path directory, Repo repo) {
        Map<String, Object> repetition = new LinkedHashMap<>();
        repetition.put("repetition", index);
        repetition.put("environment", HostEnvironment.fingerprint(repo));
        try {
            Files.createDirectories(directory);
            try (Application app = Application.start(javaExecutable, classpath, STARTUP_MAIN,
                    List.of("-Xms256m", "-Xmx256m"), directory)) {
                Map<String, Object> perMode = new LinkedHashMap<>();
                Map<String, List<Double>> recorded = new LinkedHashMap<>();
                boolean anyFailure = false;
                long seed = 1;
                for (String mode : MODES) {
                    List<Double> times = new ArrayList<>();
                    List<String> failures = new ArrayList<>();
                    List<Object> jvmOptions = new ArrayList<>();
                    for (int sample = 0; sample < samples; sample++) {
                        Response response = app.post("/api/v1/coldstart/measure", Json.map("mode", mode), 180);
                        Map<String, Object> body = Json.asMap(Json.parse(response.body()));
                        if (response.status() == 200) {
                            times.add(((Number) body.get("time_to_health_ms")).doubleValue());
                            if (jvmOptions.isEmpty()) {
                                jvmOptions.addAll(Json.asList(Json.asMap(body.get("child_runtime")).get("jvm_args")));
                            }
                        } else {
                            times.add(null);
                            failures.add(String.valueOf(body.getOrDefault("message", body)));
                        }
                    }
                    anyFailure |= !failures.isEmpty();
                    recorded.put(mode, times.stream().filter(value -> value != null).toList());
                    perMode.put(mode, Json.map(
                            "time_to_health_ms", summarize(times, seed++),
                            "requested", samples,
                            "failures", failures,
                            "child_jvm_options", jvmOptions));
                }
                Map<String, Object> appSide = Json.asMap(app.getJson("/api/v1/coldstart/samples", 60).get("samples"));
                boolean matches = true;
                for (String mode : MODES) {
                    List<Double> applicationValues = numbers(Json.asMap(appSide.get(mode)).get("time_to_health_ms"));
                    List<Double> driverValues = recorded.get(mode);
                    if (applicationValues.size() != driverValues.size()) {
                        matches = false;
                        break;
                    }
                    for (int index2 = 0; index2 < driverValues.size(); index2++) {
                        if (Math.abs(applicationValues.get(index2) - driverValues.get(index2)) > RECORDED_TOLERANCE_MS) {
                            matches = false;
                        }
                    }
                }
                repetition.put("application_runtime", app.identity);
                repetition.put("modes", perMode);
                repetition.put("app_recorded_samples_match_driver", matches);
                repetition.put("status", anyFailure || !matches ? "failed" : "passed");
            }
        } catch (LaneError | IOException failure) {
            repetition.put("status", "failed");
            repetition.put("failure", failure.getMessage());
            repetition.put("modes", Map.of());
            repetition.put("app_recorded_samples_match_driver", false);
        }
        return repetition;
    }

    /**
     * The jobs lane. Jobs are submitted on a fixed schedule, an open-loop arrival model: a job that starts late
     * because the executor is busy still counts its full wait from the moment it was due. A schedule that falls
     * behind is reported as a lane reason.
     */
    public static Map<String, Object> jobs(String javaExecutable, String classpath, int jobs, double rate, int concurrency,
                                           int jobTimeoutSeconds, int repetitions, String runKind, String tier,
                                           Path temporary, Repo repo) {
        if (rate <= 0) {
            throw new LaneError("the job arrival rate must be positive");
        }
        Map<String, Object> before = preflightNow(repo);
        List<Map<String, Object>> runs = new ArrayList<>();
        for (int index = 1; index <= repetitions; index++) {
            runs.add(jobsRepetition(index, javaExecutable, classpath, jobs, rate, concurrency, jobTimeoutSeconds,
                    temporary.resolve("repetition-" + index), repo));
        }
        Map<String, Object> after = preflightNow(repo);
        List<String> reasons = new ArrayList<>();
        Map<String, Object> aggregate = new LinkedHashMap<>();
        List<Double> records = valuesAt(runs, "metrics", "records_per_second", "median");
        List<Double> sojourn = valuesAt(runs, "metrics", "sojourn_ms", "median");
        aggregate.put("records_per_second_median", summarize(records, 1));
        aggregate.put("sojourn_ms_median", summarize(sojourn, 2));
        String spread = Tier2.spreadReason("records per second (median per repetition)", records);
        if (spread != null) {
            reasons.add(spread);
        }
        String latencySpread = Tier2.spreadReason("job latency (median per repetition)", sojourn);
        if (latencySpread != null) {
            reasons.add(latencySpread);
        }
        for (Map<String, Object> run : runs) {
            Object lag = run.get("max_submission_lag_ms");
            Object interval = run.get("schedule_interval_ms");
            if (lag instanceof Number maximum && interval instanceof Number step && maximum.doubleValue() > step.doubleValue()) {
                reasons.add("job submissions fell behind the schedule in repetition " + run.get("repetition"));
            }
        }
        List<String> warnings = List.of(
                "elapsed time includes record generation, so io_throughput_mb_s is end-to-end, not a pure I/O rate",
                "job completion is polled every 100 ms, so each latency has that much resolution",
                "CPU and resident memory are sampled from the application process only");
        return envelope("08-etl-batch", "jobs", tier, runKind, runs, before, after, reasons, warnings,
                Json.map("jobs", jobs, "rate_per_second", rate, "concurrency", concurrency,
                        "job_timeout_seconds", jobTimeoutSeconds, "repetitions", repetitions),
                aggregate);
    }

    private static Map<String, Object> jobsRepetition(int index, String javaExecutable, String classpath, int jobs,
                                                      double rate, int concurrency, int jobTimeoutSeconds,
                                                      Path directory, Repo repo) {
        Map<String, Object> repetition = new LinkedHashMap<>();
        repetition.put("repetition", index);
        repetition.put("environment", HostEnvironment.fingerprint(repo));
        long intervalNanos = (long) (1e9 / rate);
        repetition.put("schedule_interval_ms", intervalNanos / 1e6);
        try {
            Files.createDirectories(directory);
            try (Application app = Application.start(javaExecutable, classpath, JOBS_MAIN,
                    List.of("-Xms256m", "-Xmx256m"), directory)) {
                Telemetry.Sampler sampler = new Telemetry.Sampler(app.process.pid(),
                        directory.resolve("process-samples.jsonl"), Duration.ofMillis(100));
                sampler.start();
                long start = System.nanoTime();
                List<Map<String, Object>> records = new ArrayList<>();
                ExecutorService pool = Executors.newFixedThreadPool(concurrency);
                try {
                    List<Future<Map<String, Object>>> futures = new ArrayList<>();
                    for (int job = 0; job < jobs; job++) {
                        long intended = start + job * intervalNanos;
                        futures.add(pool.submit(() -> scheduledJob(app, intended, jobTimeoutSeconds)));
                    }
                    for (Future<Map<String, Object>> future : futures) {
                        records.add(future.get());
                    }
                } catch (InterruptedException interruption) {
                    Thread.currentThread().interrupt();
                    throw new LaneError("job lane interrupted");
                } catch (ExecutionException failure) {
                    throw new LaneError("job lane failed: " + failure.getMessage());
                } finally {
                    pool.shutdownNow();
                }
                Map<String, Object> process = sampler.stop();
                List<Map<String, Object>> completed = new ArrayList<>();
                List<Map<String, Object>> failed = new ArrayList<>();
                for (Map<String, Object> record : records) {
                    if ("completed".equals(record.get("status"))) {
                        completed.add(record);
                    } else {
                        failed.add(record);
                    }
                }
                long digestMatches = completed.stream().filter(record -> Boolean.TRUE.equals(record.get("digest_match")))
                        .count();
                Map<String, Object> metrics = new LinkedHashMap<>();
                String[] names = {"records_per_second", "io_throughput_mb_s", "elapsed_ms", "sojourn_ms"};
                for (int metric = 0; metric < names.length; metric++) {
                    List<Double> values = new ArrayList<>();
                    for (Map<String, Object> record : completed) {
                        Object value = record.get(names[metric]);
                        values.add(value instanceof Number number ? number.doubleValue() : null);
                    }
                    metrics.put(names[metric], summarize(values, metric + 1L));
                }
                double maxLag = records.stream().map(record -> record.get("submission_lag_ms"))
                        .filter(value -> value instanceof Number).mapToDouble(value -> ((Number) value).doubleValue())
                        .max().orElse(0);
                boolean passed = failed.isEmpty() && digestMatches == completed.size();
                repetition.put("status", passed ? "passed" : "failed");
                repetition.put("failure", failed.isEmpty() ? null : failed.size() + " job(s) did not complete");
                repetition.put("application_runtime", app.identity);
                repetition.put("process", process);
                repetition.put("jobs_requested", jobs);
                repetition.put("jobs_completed", completed.size());
                repetition.put("digest_matches", digestMatches);
                repetition.put("max_submission_lag_ms", maxLag);
                repetition.put("metrics", metrics);
                repetition.put("raw_job_records", records);
            }
        } catch (LaneError | IOException failure) {
            repetition.put("status", "failed");
            repetition.put("failure", failure.getMessage());
            repetition.put("metrics", Map.of());
        }
        return repetition;
    }

    /** One job: started at its intended time (or as soon as a worker is free), then timed to completion. */
    private static Map<String, Object> scheduledJob(Application app, long intended, int timeoutSeconds) {
        sleepUntil(intended);
        long submitted = System.nanoTime();
        Map<String, Object> status = runJob(app, timeoutSeconds);
        long finished = System.nanoTime();
        Map<String, Object> record = new LinkedHashMap<>(status);
        record.put("submission_lag_ms", (submitted - intended) / 1e6);
        record.put("sojourn_ms", (finished - intended) / 1e6);
        return record;
    }

    private static Map<String, Object> runJob(Application app, int timeoutSeconds) {
        Response response = app.post("/api/v1/etl/run", Json.map(), 180);
        if (response.status() != 200) {
            return Json.map("status", "rejected", "failure", response.body());
        }
        Object job = Json.asMap(Json.parse(response.body())).get("job_id");
        long deadline = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();
        while (true) {
            Map<String, Object> snapshot = app.getJson("/api/v1/etl/" + job + "/status", 60);
            if ("completed".equals(snapshot.get("status")) || "failed".equals(snapshot.get("status"))) {
                return snapshot;
            }
            if (System.nanoTime() > deadline) {
                return Json.map("job_id", job, "status", "timeout", "failure", "job did not finish in time");
            }
            sleep(100);
        }
    }

    /** Chat-lane settings, taken from the 06 driver's own command line. */
    public record ChatSettings(int subscribers, int rooms, int publishers, double rate, double warmupSeconds,
                               double measureSeconds) {
    }

    /**
     * The chat lane. Each repetition runs the 06 driver, which starts a fresh application and drives persistent
     * connections. The driver writes its envelope; the lane takes its measurements and applies the shared gates.
     */
    public static Map<String, Object> chat(String javaExecutable, String driverClasspath, String appClasspath,
                                           ChatSettings settings, int repetitions, String runKind, String tier,
                                           Path temporary, Repo repo) {
        Map<String, Object> before = preflightNow(repo);
        List<Map<String, Object>> runs = new ArrayList<>();
        for (int index = 1; index <= repetitions; index++) {
            runs.add(chatRepetition(index, javaExecutable, driverClasspath, appClasspath, settings,
                    temporary.resolve("repetition-" + index), repo));
        }
        Map<String, Object> after = preflightNow(repo);
        List<String> reasons = new ArrayList<>();
        List<Double> p99 = valuesAt(runs, "delivery_latency_ms", "p99");
        List<Double> rate = valuesAt(runs, "delivery_rate_per_second");
        String p99Spread = Tier2.spreadReason("delivery latency p99 (per repetition)", p99);
        if (p99Spread != null) {
            reasons.add(p99Spread);
        }
        String rateSpread = Tier2.spreadReason("delivery rate (per repetition)", rate);
        if (rateSpread != null) {
            reasons.add(rateSpread);
        }
        for (Map<String, Object> run : runs) {
            if (!Boolean.TRUE.equals(run.get("conserved"))) {
                reasons.add("frames were not conserved in repetition " + run.get("repetition"));
            }
            if (!Boolean.TRUE.equals(run.get("all_messages_sent"))) {
                reasons.add("scheduled messages were not all sent in repetition " + run.get("repetition"));
            }
        }
        Map<String, Object> aggregate = Json.map(
                "delivery_latency_p99_ms", summarize(p99, 1),
                "delivery_rate_per_second", summarize(rate, 2));
        List<String> warnings = List.of(
                "the driver and the application share a host that is not controlled beyond the preflight",
                "delivery latency is measured by the driver from the intended send time, not by the application",
                "the driver starts the application itself, so application CPU and memory are its sample window");
        return envelope("06-massive-chat-loom", "chat", tier, runKind, runs, before, after, reasons, warnings,
                Json.map("subscribers", settings.subscribers(), "rooms", settings.rooms(),
                        "publishers", settings.publishers(), "rate", settings.rate(),
                        "warmup_seconds", settings.warmupSeconds(), "measure_seconds", settings.measureSeconds(),
                        "repetitions", repetitions),
                aggregate);
    }

    private static Map<String, Object> chatRepetition(int index, String javaExecutable, String driverClasspath,
                                                      String appClasspath, ChatSettings settings, Path directory,
                                                      Repo repo) {
        Map<String, Object> repetition = new LinkedHashMap<>();
        repetition.put("repetition", index);
        repetition.put("environment", HostEnvironment.fingerprint(repo));
        try {
            Files.createDirectories(directory);
            Path envelopePath = directory.resolve("driver-envelope.json");
            List<String> command = new ArrayList<>(List.of(javaExecutable, "-cp", driverClasspath, CHAT_DRIVER_MAIN,
                    "--app-classpath", appClasspath,
                    "--subscribers", Integer.toString(settings.subscribers()),
                    "--rooms", Integer.toString(settings.rooms()),
                    "--publishers", Integer.toString(settings.publishers()),
                    "--rate", Double.toString(settings.rate()),
                    "--warmup-seconds", Double.toString(settings.warmupSeconds()),
                    "--measure-seconds", Double.toString(settings.measureSeconds()),
                    "--out", envelopePath.toString()));
            ProcessBuilder builder = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(directory.resolve("driver.log").toFile());
            for (String removed : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) {
                builder.environment().remove(removed);
            }
            Process driver = builder.start();
            if (!driver.waitFor(10, TimeUnit.MINUTES)) {
                driver.destroyForcibly();
                throw new LaneError("the chat driver did not finish in ten minutes");
            }
            if (driver.exitValue() != 0) {
                throw new LaneError("the chat driver exited with " + driver.exitValue());
            }
            Map<String, Object> envelope = Json.asMap(Json.parse(Files.readString(envelopePath)));
            Map<String, Object> results = Json.asMap(envelope.get("results"));
            Map<String, Object> latency = Json.asMap(results.get("delivery_latency_ms"));
            boolean conserved = Boolean.TRUE.equals(results.get("conserved"));
            double scheduled = Json.number(results.get("messages_scheduled"));
            boolean allSent = Json.number(results.get("messages_sent")) == scheduled;
            long errors = Math.round(Json.number(results.get("error_frames")));
            repetition.put("status", conserved && errors == 0 ? "passed" : "failed");
            repetition.put("failure", conserved && errors == 0 ? null : "frames not conserved or error frames seen");
            repetition.put("application_runtime", envelope.get("application_runtime"));
            repetition.put("conserved", conserved);
            repetition.put("all_messages_sent", allSent);
            repetition.put("error_frames", errors);
            repetition.put("messages_scheduled", scheduled);
            repetition.put("messages_sent", results.get("messages_sent"));
            repetition.put("delivery_rate_per_second", results.get("delivery_rate_per_second"));
            repetition.put("delivery_latency_ms", latency);
            repetition.put("application", results.get("application"));
        } catch (LaneError | IOException failure) {
            repetition.put("status", "failed");
            repetition.put("failure", failure.getMessage());
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            repetition.put("status", "failed");
            repetition.put("failure", "interrupted");
        }
        return repetition;
    }

    private static List<Double> numbers(Object value) {
        List<Double> values = new ArrayList<>();
        for (Object item : Json.asList(value)) {
            values.add(item instanceof Number number ? number.doubleValue() : null);
        }
        return values.stream().filter(item -> item != null).toList();
    }

    private static String read(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.readString(path, StandardCharsets.UTF_8).strip() : null;
        } catch (IOException exception) {
            return null;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LaneError("interrupted");
        }
    }

    /** Waits until the given System.nanoTime() instant, without busy-waiting. */
    private static void sleepUntil(long target) {
        long remaining = target - System.nanoTime();
        if (remaining <= 0) {
            return;
        }
        try {
            Thread.sleep(remaining / 1_000_000, (int) (remaining % 1_000_000));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LaneError("interrupted");
        }
    }
}
