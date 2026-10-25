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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Measurement lanes for workloads whose unit of work is not an HTTP request.
 *
 * <p>{@code startup}: 07-coldstart-suite, one sample is one child JVM started and checked by the application.
 * {@code jobs}: 08-etl-batch, one sample is one batch job, timed and digest-checked by the application.
 *
 * <p>Each lane starts the application, takes the samples it reports, computes the controller's statistics, and
 * writes a result envelope. Failed samples are counted, never dropped. Unknown values are null, never zero.
 * Envelopes are labelled calibration with measurement_valid false: the host is not controlled.
 */
public final class Lanes {
    static final List<String> MODES = List.of("default", "no-cds", "appcds");
    static final String UNCONTROLLED_HOST =
            "host is not controlled: no preflight, no pinned frequency, shared with the driver";
    static final String STARTUP_MAIN = "com.palaashatri.bench.b07.app.BenchmarkApp";
    static final String JOBS_MAIN = "com.palaashatri.bench.b08.app.BenchmarkApp";
    /** The application reports to the microsecond; the response carries three decimals. */
    static final double RECORDED_TOLERANCE_MS = 0.0006;

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
                    if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
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

    static Map<String, Object> envelope(String benchmark, String lane, Map<String, Object> identity,
                                        List<String> reasons, List<String> warnings, Map<String, Object> parameters,
                                        Map<String, Object> results) {
        List<String> invalid = new ArrayList<>();
        invalid.add(UNCONTROLLED_HOST);
        invalid.addAll(reasons);
        return Json.map(
                "schema_version", "1.0.0",
                "benchmark", benchmark,
                "lane", lane,
                "run_kind", "calibration",
                "implementation_tier", "tier-1",
                "measurement_valid", false,
                "invalid_reasons", invalid,
                "warnings", warnings,
                "application_runtime", identity,
                "parameters", parameters,
                "results", results);
    }

    /** The startup lane: every mode, several child starts each, cross-checked against the application's own record. */
    public static Map<String, Object> startup(String javaExecutable, String classpath, int samples, Path temporary) {
        try (Application app = Application.start(javaExecutable, classpath, STARTUP_MAIN, List.of("-Xms256m", "-Xmx256m"),
                temporary)) {
            Map<String, Object> probe = app.getJson("/api/v1/coldstart/modes", 60);
            Map<String, Object> perMode = new LinkedHashMap<>();
            Map<String, List<Double>> recorded = new HashMap<>();
            long seed = 1;
            for (String mode : MODES) {
                List<Double> times = new ArrayList<>();
                List<String> failures = new ArrayList<>();
                List<Object> jvmOptions = new ArrayList<>();
                for (int index = 0; index < samples; index++) {
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
                for (int index = 0; index < driverValues.size(); index++) {
                    if (Math.abs(applicationValues.get(index) - driverValues.get(index)) > RECORDED_TOLERANCE_MS) {
                        matches = false;
                    }
                }
            }
            List<String> reasons = List.of("startup lane has no per-sample repetition gates and no page-cache control");
            List<String> warnings = List.of(
                    "the page cache is warm; a cold page cache needs root, which this lane does not use",
                    "times are the application's own spawn-to-ready measurements, read per response");
            return envelope("07-coldstart-suite", "startup", app.identity, reasons, warnings,
                    Json.map("samples_per_mode", samples),
                    Json.map("modes", perMode, "mode_probe", probe,
                            "app_recorded_samples_match_driver", matches));
        }
    }

    /** The jobs lane: a fixed number of jobs at a fixed concurrency, each timed and digest-checked by the application. */
    public static Map<String, Object> jobs(String javaExecutable, String classpath, int jobs, int concurrency,
                                           int jobTimeoutSeconds, Path temporary) {
        try (Application app = Application.start(javaExecutable, classpath, JOBS_MAIN, List.of("-Xms256m", "-Xmx256m"),
                temporary)) {
            AtomicLong peakKib = new AtomicLong(-1);
            java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
            Thread sampler = new Thread(() -> {
                while (!done.get()) {
                    Long bytes = Telemetry.ProcessMemory.residentBytes(app.process.pid());
                    if (bytes != null) {
                        peakKib.accumulateAndGet(bytes / 1024, Math::max);
                    }
                    sleep(250);
                }
            }, "lane-resident-sampler");
            sampler.setDaemon(true);
            sampler.start();
            List<Map<String, Object>> statuses = new ArrayList<>();
            ExecutorService pool = Executors.newFixedThreadPool(concurrency);
            try {
                List<Future<Map<String, Object>>> futures = new ArrayList<>();
                for (int index = 0; index < jobs; index++) {
                    futures.add(pool.submit(() -> runJob(app, jobTimeoutSeconds)));
                }
                for (Future<Map<String, Object>> future : futures) {
                    statuses.add(future.get());
                }
            } catch (InterruptedException | java.util.concurrent.ExecutionException exception) {
                throw new LaneError("job lane interrupted: " + exception.getMessage());
            } finally {
                pool.shutdownNow();
                done.set(true);
            }
            List<Map<String, Object>> completed = new ArrayList<>();
            List<Map<String, Object>> failed = new ArrayList<>();
            for (Map<String, Object> status : statuses) {
                if ("completed".equals(status.get("status"))) {
                    completed.add(status);
                } else {
                    failed.add(status);
                }
            }
            Map<String, Object> metrics = new LinkedHashMap<>();
            String[] names = {"records_per_second", "io_throughput_mb_s", "elapsed_ms"};
            for (int index = 0; index < names.length; index++) {
                List<Double> values = new ArrayList<>();
                for (Map<String, Object> status : completed) {
                    Object value = status.get(names[index]);
                    values.add(value instanceof Number number ? number.doubleValue() : null);
                }
                metrics.put(names[index], summarize(values, index + 1L));
            }
            List<Map<String, Object>> jobFailures = new ArrayList<>();
            for (Map<String, Object> status : failed) {
                jobFailures.add(Json.map("job_id", status.get("job_id"), "status", status.get("status"),
                        "failure", status.get("failure")));
            }
            long digestMatches = completed.stream().filter(status -> Boolean.TRUE.equals(status.get("digest_match"))).count();
            List<String> reasons = List.of(
                    "jobs are submitted by the lane, not by a load schedule; there is no open-loop arrival model");
            List<String> warnings = List.of(
                    "elapsed time includes record generation, so io_throughput_mb_s is end-to-end, not a pure I/O rate");
            long peak = peakKib.get();
            return envelope("08-etl-batch", "jobs", app.identity, reasons, warnings,
                    Json.map("jobs", jobs, "concurrency", concurrency, "job_timeout_seconds", jobTimeoutSeconds),
                    Json.map(
                            "jobs_requested", jobs,
                            "jobs_completed", completed.size(),
                            "jobs_failed", jobFailures,
                            "digest_matches", digestMatches,
                            "metrics", metrics,
                            "resident_mib_max", peak < 0 ? null : peak / 1024.0,
                            "raw_job_statuses", statuses));
        }
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
}
