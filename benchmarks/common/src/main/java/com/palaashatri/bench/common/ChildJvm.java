package com.palaashatri.bench.common;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns a child from spawn through readiness, failure and termination. */
public final class ChildJvm implements AutoCloseable {
    private static final Set<ChildJvm> LIVE = ConcurrentHashMap.newKeySet();
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build();
    static { Runtime.getRuntime().addShutdownHook(new Thread(() -> LIVE.forEach(ChildJvm::close), "child-jvm-cleanup")); }
    private final String role;
    private final String token;
    private final Path directory;
    private final Process process;
    private final AtomicBoolean closed = new AtomicBoolean();
    private int port;

    private ChildJvm(String role, String token, Path directory, Process process) {
        this.role = role; this.token = token; this.directory = directory; this.process = process;
        LIVE.add(this);
    }

    /** Launch using this JVM's executable and a unique, validated readiness token. */
    public static ChildJvm start(String mainClass, String role, String runToken, Map<String, String> variables) throws IOException {
        return start(mainClass, role, runToken, variables, Duration.ofSeconds(15));
    }

    /** The timeout includes port discovery and the process-identity handshake. */
    public static ChildJvm start(String mainClass, String role, String runToken, Map<String, String> variables, Duration timeout) throws IOException {
        Path directory = Files.createTempDirectory("bench-child-");
        ChildJvm child = null;
        try {
            String token = runToken + "-" + UUID.randomUUID();
            var command = new ArrayList<String>();
            command.add(RuntimeInfo.javaExecutable().toString());
            command.add("-Xms16m"); command.add("-Xmx128m");
            command.add("-cp"); command.add(classPath(mainClass));
            command.add(mainClass); command.add("0");
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.environment().putAll(variables);
            builder.environment().put("PORT", "0");
            builder.environment().put("BENCH_SERVICE_ROLE", role);
            builder.environment().put("BENCH_RUN_TOKEN", token);
            builder.environment().put("BENCH_PORT_FILE", directory.resolve("ready.port").toString());
            builder.environment().put("BENCH_PARENT_PID", Long.toString(ProcessHandle.current().pid()));
            // Never inherit a fixed debug port or parent-owned JFR/GC log paths.
            builder.environment().remove("JAVA_TOOL_OPTIONS");
            builder.environment().remove("JDK_JAVA_OPTIONS");
            builder.environment().remove("_JAVA_OPTIONS");
            builder.redirectErrorStream(true).redirectOutput(directory.resolve("child.log").toFile());
            child = new ChildJvm(role, token, directory, builder.start());
            child.awaitReady(timeout);
            return child;
        } catch (Exception failure) {
            String logs = tail(directory.resolve("child.log"));
            if (child != null) child.close(); else delete(directory);
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IOException("child " + role + " readiness failed: " + failure.getMessage() + "\n" + logs, failure);
        }
    }

    private static String classPath(String mainClass) throws Exception {
        var entries = new LinkedHashSet<String>();
        entries.addAll(java.util.Arrays.asList(System.getProperty("java.class.path").split(java.io.File.pathSeparator)));
        Class<?> application = Class.forName(mainClass, false, Thread.currentThread().getContextClassLoader());
        for (Class<?> type : new Class<?>[]{application, ChildJvm.class}) {
            var source = type.getProtectionDomain().getCodeSource();
            if (source != null) entries.add(Path.of(source.getLocation().toURI()).toString());
        }
        return String.join(java.io.File.pathSeparator, entries);
    }

    private void awaitReady(Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        Path file = directory.resolve("ready.port");
        while (System.nanoTime() < deadline) {
            if (!alive()) throw new IOException("exited with code " + process.exitValue());
            if (Files.exists(file)) {
                String value = Files.readString(file).trim();
                if (!value.isEmpty()) {
                    int candidate = Integer.parseInt(value);
                    if (candidate < 1 || candidate > 65_535) throw new IOException("invalid published port");
                    try {
                        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + candidate + "/runtime"))
                                .timeout(Duration.ofMillis(400)).GET().build();
                        var response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                        var identity = Json.parseObject(response.body());
                        if (response.statusCode() == 200 && Json.number(identity, "pid", -1) == pid()
                                && token.equals(identity.get("run_token")) && role.equals(identity.get("role")) && alive()) {
                            port = candidate; return;
                        }
                    } catch (IOException unavailable) { /* Listener may still be starting. */ }
                }
            }
            Thread.sleep(20);
        }
        throw new IOException("timeout waiting for owned process");
    }

    public String role() { return role; }
    public String token() { return token; }
    public long pid() { return process.pid(); }
    public int port() { return port; }
    public boolean alive() { return process.isAlive(); }
    public Path directory() { return directory; }
    public Map<String, Object> identity() {
        return Map.of("role", role, "pid", pid(), "port", port, "alive", alive());
    }

    /** Idempotently terminate, forcibly terminate if needed, reap and remove files. */
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        boolean interrupted = Thread.interrupted();
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interruption) {
            interrupted = true;
            process.destroyForcibly();
            try { process.waitFor(2, TimeUnit.SECONDS); }
            catch (InterruptedException again) { interrupted = true; }
        } finally {
            LIVE.remove(this);
            delete(directory);
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static String tail(Path path) {
        try (var file = new RandomAccessFile(path.toFile(), "r")) {
            file.seek(Math.max(0, file.length() - 4_096));
            byte[] bytes = new byte[(int) Math.min(4_096, file.length())];
            file.readFully(bytes);
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException ignored) { return ""; }
    }
    private static void delete(Path directory) {
        // Close directory handles before removing their directories (Windows
        // and some mounted filesystems reject deletion while traversal is open).
        java.util.List<Path> entries;
        try (var paths = Files.walk(directory)) {
            entries = paths.sorted(java.util.Comparator.reverseOrder()).toList();
        } catch (IOException ignored) { return; }
        for (Path path : entries) {
            try { Files.deleteIfExists(path); }
            catch (IOException failure) { System.err.println("child cleanup failed for " + path + ": " + failure.getMessage()); }
        }
    }
}
