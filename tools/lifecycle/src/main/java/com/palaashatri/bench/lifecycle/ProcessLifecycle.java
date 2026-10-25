package com.palaashatri.bench.lifecycle;

import com.palaashatri.bench.common.Json;
import com.palaashatri.bench.testkit.AppProcess;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Process-lifecycle checks shared by the workloads that own child JVMs. Children are found through the
 * {@code bench-child-*} directories that the shared child launcher creates, and each child's identity is read from
 * its own endpoint, so an observed child is always one this test's application started.
 */
public final class ProcessLifecycle {
    private static final Duration CHILD_EXIT = Duration.ofSeconds(5);

    private ProcessLifecycle() {
    }

    /**
     * Starts the application on a port that is already taken. It must fail to bind, and every child it started
     * before the failure must be reaped and its temporary directory removed.
     */
    public static void failedBindReapsChildren(String classpath, String mainClass, Path temporary) throws Exception {
        try (java.net.ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int port = occupied.getLocalPort();
            String token = "failed-bind-" + UUID.randomUUID();
            File log = Files.createFile(temporary.resolve("parent.log")).toFile();
            ProcessBuilder builder = new ProcessBuilder(javaExecutable(),
                    "-Djava.io.tmpdir=" + temporary, "-cp", classpath, mainClass, Integer.toString(port))
                    .redirectErrorStream(true).redirectOutput(log);
            builder.environment().put("BENCH_RUN_TOKEN", token);
            builder.environment().remove("BENCH_SERVICE_ROLE");
            builder.environment().remove("BENCH_PORT_FILE");
            builder.environment().remove("BENCH_PARENT_PID");
            Process process = builder.start();
            Set<Long> observed = new LinkedHashSet<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            try {
                while (process.isAlive() && System.nanoTime() < deadline) {
                    for (Path ready : childReadyFiles(temporary)) {
                        observeChild(ready, token, observed);
                    }
                    Thread.sleep(20);
                }
                assertTrue(!process.isAlive(), "startup failure did not terminate");
                assertTrue(process.exitValue() != 0, "the failed bind must exit non-zero");
                String output = new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8);
                assertTrue(output.contains("BindException"), "startup failed for an unexpected reason: " + output);
                assertTrue(!observed.isEmpty(), "no child was observed before the failed gateway bind: " + output);
                for (long pid : observed) {
                    Eventually.until(() -> !Liveness.alive(pid), CHILD_EXIT);
                }
                try (Stream<Path> left = Files.list(temporary)) {
                    assertTrue(left.noneMatch(path -> path.getFileName().toString().startsWith("bench-child-")),
                            "child directories were left behind");
                }
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
                process.waitFor(5, TimeUnit.SECONDS);
                for (long pid : observed) {
                    if (Liveness.alive(pid)) {
                        ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
                    }
                }
            }
        }
    }

    /** Kills the application outright and checks that every child it owned exits with it. */
    public static void forcedParentExitClosesChildren(AppProcess app, List<Long> children) {
        app.process().destroyForcibly();
        try {
            app.process().waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
        for (long pid : children) {
            Eventually.until(() -> !Liveness.alive(pid), Duration.ofSeconds(8));
        }
    }

    /** The identity and pid of each child a running application reports, read from one of its endpoints. */
    public static List<Long> pids(Map<String, Object> document, String listField, String pidField) {
        List<Long> pids = new ArrayList<>();
        for (Object item : (List<?>) document.get(listField)) {
            pids.add(((Number) Json.parseObject(Json.stringify(item)).get(pidField)).longValue());
        }
        return pids;
    }

    private static void observeChild(Path ready, String token, Set<Long> observed) {
        try {
            int port = Integer.parseInt(new String(Files.readAllBytes(ready), StandardCharsets.UTF_8).strip());
            Map<String, Object> identity = Json.parseObject(get("http://127.0.0.1:" + port + "/runtime"));
            if (String.valueOf(identity.get("run_token")).startsWith(token)) {
                observed.add(((Number) identity.get("pid")).longValue());
            }
        } catch (IOException | RuntimeException notReadyYet) {
            // The child is still starting; the next poll sees it.
        }
    }

    private static List<Path> childReadyFiles(Path temporary) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> directories = Files.list(temporary)) {
            for (Path directory : directories.filter(path -> path.getFileName().toString().startsWith("bench-child-"))
                    .toList()) {
                Path ready = directory.resolve("ready.port");
                if (Files.isRegularFile(ready)) {
                    files.add(ready);
                }
            }
        }
        return files;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(200);
        connection.setReadTimeout(1000);
        try (InputStream in = connection.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    private static String javaExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
        return Paths.get(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
