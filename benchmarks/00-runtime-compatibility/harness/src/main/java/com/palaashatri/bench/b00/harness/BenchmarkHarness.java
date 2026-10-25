package com.palaashatri.bench.b00.harness;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Functional client for the compatibility workload. It runs on every JDK under test, so it uses only Java 8
 * APIs. It writes the shared smoke envelope shape: kpis are null, and the application identity is embedded as
 * the application reported it. Load and telemetry come from the Tier 2 engine.
 */
public final class BenchmarkHarness {
    private static final Pattern PID = Pattern.compile("\"pid\":(\\d+)");
    private static final Pattern JAVA_VERSION = Pattern.compile("\"java_version\":\"([^\"]*)\"");
    private static final Pattern JVM_ARGS = Pattern.compile("\"jvm_args\":(\\[[^\\]]*\\])");
    private static final Pattern COLLECTORS = Pattern.compile("\"collectors\":(\\[[^\\]]*\\])");

    private BenchmarkHarness() { }

    public static void main(String[] args) throws Exception {
        String base = option(args, "base-url", "http://127.0.0.1:8080");
        int requests = Integer.parseInt(option(args, "requests", "25"));
        int threads = Integer.parseInt(option(args, "threads", "4"));
        int runs = Integer.parseInt(option(args, "runs", "1"));
        String output = option(args, "out", "results/results.json");
        if (requests < 1 || threads < 1 || runs < 1) {
            throw new IllegalArgumentException("requests, threads and runs must be positive");
        }

        String identity = get(base + "/runtime");
        if (!identity.contains("\"run_token\"") || !JVM_ARGS.matcher(identity).find()
                || !COLLECTORS.matcher(identity).find()) {
            throw new IllegalStateException("incomplete application runtime identity");
        }
        final String target = base + "/work";
        int total = requests * runs;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<String>> futures = new ArrayList<Future<String>>();
        for (int index = 0; index < total; index++) {
            final String body = "{\"seed\":" + (424242L + index) + ",\"size\":4096}";
            futures.add(pool.submit(new Callable<String>() {
                @Override public String call() throws Exception {
                    return post(target, body);
                }
            }));
        }
        int ok = 0;
        List<String> errors = new ArrayList<String>();
        for (Future<String> future : futures) {
            String error = future.get();
            if (error == null) {
                ok++;
            } else if (errors.size() < 10) {
                errors.add(error);
            }
        }
        pool.shutdown();

        get(base + "/health");
        String after = get(base + "/runtime");
        if (!samePid(identity, after)) {
            throw new IllegalStateException("application identity changed during the smoke run");
        }

        String json = envelope(identity, requests * runs, ok, errors);
        Path path = Paths.get(output);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.write(path, (json + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
        System.out.println(json);
        if (ok != total) {
            throw new IllegalStateException("only " + ok + " of " + total + " smoke requests succeeded");
        }
    }

    /** Returns null on success, otherwise a description of the failure. */
    private static String post(String target, String body) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(target).openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(10000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            OutputStream output = connection.getOutputStream();
            output.write(body.getBytes(StandardCharsets.UTF_8));
            output.close();
            int status = connection.getResponseCode();
            String response = read(connection.getInputStream());
            if (status != 200 || !response.contains("\"checksum\"")) {
                return "HTTP " + status + " " + response;
            }
            return null;
        } catch (Exception failure) {
            return failure.getClass().getSimpleName() + ": " + failure.getMessage();
        }
    }

    private static String get(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(10000);
        int status = connection.getResponseCode();
        String body = read(connection.getInputStream());
        if (status != 200) {
            throw new IllegalStateException("HTTP " + status + " from " + url);
        }
        return body;
    }

    private static String read(java.io.InputStream stream) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        StringBuilder text = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            text.append(line);
        }
        reader.close();
        return text.toString();
    }

    private static boolean samePid(String before, String after) {
        Matcher left = PID.matcher(before);
        Matcher right = PID.matcher(after);
        return left.find() && right.find() && left.group(1).equals(right.group(1));
    }

    private static String envelope(String identity, int requests, int ok, List<String> errors) {
        Matcher pid = PID.matcher(identity);
        Matcher version = JAVA_VERSION.matcher(identity);
        Matcher jvmArgs = JVM_ARGS.matcher(identity);
        Matcher collectors = COLLECTORS.matcher(identity);
        pid.find();
        version.find();
        jvmArgs.find();
        collectors.find();
        StringBuilder errorArray = new StringBuilder("[");
        for (int index = 0; index < errors.size(); index++) {
            if (index > 0) {
                errorArray.append(',');
            }
            errorArray.append('"').append(escape(errors.get(index))).append('"');
        }
        errorArray.append(']');
        return "{\"schema_version\":\"1.0.0\",\"benchmark\":\"00-runtime-compatibility\",\"run_kind\":\"smoke\","
                + "\"implementation_tier\":\"tier-2\",\"measurement_valid\":false,"
                + "\"invalid_reasons\":[\"functional_smoke_only\"],"
                + "\"warnings\":[\"Functional smoke only; load and application telemetry come from the Tier 2 engine\"],"
                + "\"kpis\":null,\"requests\":" + requests + ",\"ok\":" + ok + ",\"errors\":" + errorArray
                + ",\"profile\":\"functional-smoke\",\"application_runtime\":" + identity
                + ",\"application_pid\":" + pid.group(1)
                + ",\"runtime\":\"" + version.group(1) + "\",\"gc\":" + collectors.group(1)
                + ",\"jvm_flags\":" + jvmArgs.group(1) + "}";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String option(String[] args, String name, String fallback) {
        String key = "--" + name;
        for (int index = 0; index + 1 < args.length; index++) {
            if (key.equals(args[index])) {
                return args[index + 1];
            }
        }
        return fallback;
    }
}
