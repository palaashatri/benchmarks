package com.palaashatri.bench.testkit;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * A workload application started as a child JVM for a functional test. The child runs on the JVM that runs the
 * test, with the classpath and JVM options the test names. The test owns the child: {@link #close()} stops it,
 * and {@link Builder#start()} returns only once the identity endpoint answers with this run's unique token.
 *
 * <p>Written against the Java 8 API, so workload 00 (Java 8 bytecode) can use it too.
 */
public final class AppProcess implements AutoCloseable {
    private static final long READY_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(30);

    private final Process process;
    private final int port;
    private final String token;

    private AppProcess(Process process, int port, String token) {
        this.process = process;
        this.port = port;
        this.token = token;
    }

    /** Starts an application with the test's own classpath and no extra JVM options. */
    public static Builder builder(String mainClass) {
        return new Builder(mainClass);
    }

    /** Configures how a child application is started. */
    public static final class Builder {
        private final String mainClass;
        private String classpath = System.getProperty("java.class.path");
        private String identityPath = "/runtime";
        private final List<String> jvmOptions = new ArrayList<String>();
        private final List<String> arguments = new ArrayList<String>();

        private Builder(String mainClass) {
            this.mainClass = mainClass;
        }

        /** The classpath for the child. Defaults to the test's own classpath. */
        public Builder classpath(String value) {
            this.classpath = value;
            return this;
        }

        /** The endpoint that reports the process identity. Defaults to /runtime. */
        public Builder identityPath(String value) {
            this.identityPath = value;
            return this;
        }

        public Builder jvmOptions(String... options) {
            jvmOptions.addAll(Arrays.asList(options));
            return this;
        }

        /** Arguments after the main class. The application's port is always passed first. */
        public Builder arguments(String... values) {
            arguments.addAll(Arrays.asList(values));
            return this;
        }

        public AppProcess start() {
            int port = freePort();
            String token = UUID.randomUUID().toString();
            List<String> command = new ArrayList<String>();
            command.add(javaExecutable());
            command.addAll(jvmOptions);
            command.add("-cp");
            command.add(classpath);
            command.add(mainClass);
            command.add(Integer.toString(port));
            command.addAll(arguments);
            File log = logFile(mainClass);
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log);
            builder.environment().put("BENCH_RUN_TOKEN", token);
            Process process;
            try {
                process = builder.start();
            } catch (IOException exception) {
                throw new IllegalStateException("cannot start " + mainClass + ": " + exception.getMessage(), exception);
            }
            AppProcess app = new AppProcess(process, port, token);
            try {
                app.awaitIdentity(identityPath, log);
            } catch (RuntimeException failure) {
                app.close();
                throw failure;
            }
            return app;
        }
    }

    public int port() {
        return port;
    }

    public String token() {
        return token;
    }

    /** The underlying process, for tests that need its liveness or exit status. */
    public Process process() {
        return process;
    }

    public String get(String path) {
        return request("GET", path, null);
    }

    /** Returns the body of a 2xx response; any other status fails the caller. */
    public String request(String method, String path, String body) {
        Response response = exchange(method, path, body);
        if (response.status() < 200 || response.status() > 299) {
            throw new IllegalStateException(method + " " + path + " returned HTTP " + response.status()
                    + ": " + response.body());
        }
        return response.body();
    }

    /** Returns the status and body whatever the status, for tests that expect an error response. */
    public Response exchange(String method, String path, String body) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path)
                    .openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(30_000);
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                connection.getOutputStream().write(bytes);
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String text = stream == null ? "" : read(stream);
            connection.disconnect();
            return new Response(status, text);
        } catch (IOException exception) {
            throw new IllegalStateException(method + " " + path + " failed: " + exception.getMessage(), exception);
        }
    }

    @Override
    public void close() {
        if (!process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private void awaitIdentity(String identityPath, File log) {
        long deadline = System.currentTimeMillis() + READY_TIMEOUT_MILLIS;
        String lastError = "not attempted";
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException("application exited before readiness; log: " + tail(log));
            }
            Response response;
            try {
                response = exchange("GET", identityPath, null);
            } catch (IllegalStateException exception) {
                lastError = exception.getMessage();
                sleep(100);
                continue;
            }
            if (response.status() == 200) {
                // Outside the retry: a body that is not this run's identity is a wrong process, not a slow start.
                if (!response.body().contains("\"run_token\":\"" + token + "\"")) {
                    throw new IllegalStateException("application identity mismatch on " + identityPath
                            + ": " + response.body());
                }
                return;
            }
            lastError = "HTTP " + response.status();
            sleep(100);
        }
        throw new IllegalStateException("application identity timeout: " + lastError + "; log: " + tail(log));
    }

    /** An HTTP status and body. */
    public static final class Response {
        private final int status;
        private final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }

        public int status() {
            return status;
        }

        public String body() {
            return body;
        }
    }

    /** The first free loopback port. A race with another process is possible but harmless: readiness is checked. */
    public static int freePort() {
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new IllegalStateException("cannot allocate a loopback port: " + exception.getMessage(), exception);
        }
    }

    private static String javaExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        return Paths.get(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
    }

    private static File logFile(String mainClass) {
        try {
            File directory = Paths.get(System.getProperty("java.io.tmpdir"), "bench-smoke").toFile();
            Files.createDirectories(directory.toPath());
            return File.createTempFile(mainClass.replaceAll("[^A-Za-z0-9]", "_") + "-", ".log", directory);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot create the application log: " + exception.getMessage(), exception);
        }
    }

    private static String tail(File log) {
        try {
            String text = new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8);
            return text.length() <= 1000 ? text : text.substring(text.length() - 1000);
        } catch (IOException exception) {
            return "(no log)";
        }
    }

    private static String read(InputStream stream) throws IOException {
        try (InputStream in = stream) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            while ((count = in.read(chunk)) != -1) {
                out.write(chunk, 0, count);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the application", exception);
        }
    }
}
