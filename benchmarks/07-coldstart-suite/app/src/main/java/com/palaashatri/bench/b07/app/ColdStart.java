package com.palaashatri.bench.b07.app;

import com.palaashatri.bench.common.ChildJvm;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * Process-to-health time of child JVMs under three class-sharing modes:
 *
 * <ul>
 *   <li>{@code default}: the JDK's own class-data archive, {@code -Xshare:auto};</li>
 *   <li>{@code no-cds}: class sharing disabled, {@code -Xshare:off};</li>
 *   <li>{@code appcds}: a dynamic archive of this application's classes, {@code -XX:SharedArchiveFile}
 *       with {@code -Xshare:on}, so an archive the child cannot use fails it rather than falling back.</li>
 * </ul>
 *
 * <p>Each mode is probed before it is measured. The AppCDS archive is written once by a training child
 * from a JAR (the JVM does not archive directory classpaths). Children are measured one at a time, so
 * they do not compete with each other for CPU. The flags reported for a measurement are read back from
 * the child's own {@code /runtime}.
 */
final class ColdStart {
    enum Mode {
        DEFAULT("default"), NO_CDS("no-cds"), APPCDS("appcds");

        final String id;

        Mode(String id) { this.id = id; }

        static Mode parse(String id) {
            for (Mode mode : values()) {
                if (mode.id.equals(id)) return mode;
            }
            throw new IllegalArgumentException("unknown mode: " + id);
        }
    }

    /** One child measurement: a time with the child's own runtime identity, or the reason it failed. */
    record Measurement(boolean ok, double timeToHealthMs, long childPid, String childRuntime, String message) {
        static Measurement failed(String message) {
            return new Measurement(false, Double.NaN, -1, null, message);
        }
    }

    static final String MAIN_CLASS = BenchmarkApp.class.getName();
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration ARCHIVE_GRACE = Duration.ofSeconds(60);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build();

    private final ReentrantLock gate = new ReentrantLock();
    private final Map<Mode, List<Double>> samples = new EnumMap<>(Mode.class);
    private final Map<Mode, Integer> failures = new EnumMap<>(Mode.class);
    private final Map<Mode, String> unavailable = new EnumMap<>(Mode.class);
    private Path archive;
    private Path archiveDirectory;
    private Long archiveBuildMs;
    private Long archiveBytes;

    /** Probes the default archive and builds the AppCDS archive. Runs once, in the top-level application. */
    void prepare() {
        gate.lock();
        try {
            probeDefaultArchive();
            buildAppCdsArchive();
        } finally {
            gate.unlock();
        }
    }

    Measurement measure(Mode mode) {
        gate.lock();
        try {
            String reason = unavailable.get(mode);
            if (reason != null) {
                failures.merge(mode, 1, Integer::sum);
                return Measurement.failed(reason);
            }
            long started = System.nanoTime();
            try (ChildJvm child = ChildJvm.start(MAIN_CLASS, "child", "coldstart-" + mode.id, Map.of(),
                    optionsFor(mode), READY_TIMEOUT)) {
                double timeToHealthMs = (System.nanoTime() - started) / 1_000_000.0;
                String runtime = childRuntime(child);
                samples.computeIfAbsent(mode, key -> new ArrayList<>()).add(timeToHealthMs);
                return new Measurement(true, timeToHealthMs, child.pid(), runtime, null);
            } catch (IOException | RuntimeException failure) {
                failures.merge(mode, 1, Integer::sum);
                return Measurement.failed(failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                failures.merge(mode, 1, Integer::sum);
                return Measurement.failed("interrupted");
            }
        } finally {
            gate.unlock();
        }
    }

    /** Mode table for {@code GET /api/v1/coldstart/modes}; {@code available} is probed, never assumed. */
    String modesJson() {
        gate.lock();
        try {
            var modes = new ArrayList<String>();
            for (Mode mode : Mode.values()) {
                String reason = unavailable.get(mode);
                modes.add("{\"id\":\"" + mode.id + "\",\"jvm_options\":" + jsonArray(optionsFor(mode))
                        + ",\"available\":" + (reason == null)
                        + ",\"reason\":" + (reason == null ? "null" : "\"" + escape(reason) + "\"") + "}");
            }
            return "{\"modes\":[" + String.join(",", modes) + "],\"appcds_archive_build_ms\":"
                    + (archiveBuildMs == null ? "null" : archiveBuildMs)
                    + ",\"appcds_archive_bytes\":" + (archiveBytes == null ? "null" : archiveBytes) + "}";
        } finally {
            gate.unlock();
        }
    }

    /** Raw measured times and failure counts per mode, so the controller can summarise them later. */
    String samplesJson() {
        gate.lock();
        try {
            var entries = new ArrayList<String>();
            for (Mode mode : Mode.values()) {
                List<Double> values = samples.getOrDefault(mode, List.of());
                var numbers = new ArrayList<String>();
                for (double value : values) numbers.add(Double.toString(value));
                entries.add("\"" + mode.id + "\":{\"time_to_health_ms\":[" + String.join(",", numbers)
                        + "],\"failures\":" + failures.getOrDefault(mode, 0) + "}");
            }
            return "{\"samples\":{" + String.join(",", entries) + "}}";
        } finally {
            gate.unlock();
        }
    }

    private List<String> optionsFor(Mode mode) {
        return switch (mode) {
            case DEFAULT -> List.of("-Xshare:auto");
            case NO_CDS -> List.of("-Xshare:off");
            case APPCDS -> archive == null
                    ? List.of("-Xshare:on")
                    : List.of("-XX:SharedArchiveFile=" + archive, "-Xshare:on");
        };
    }

    private void probeDefaultArchive() {
        try {
            Process process = new ProcessBuilder(javaExecutable().toString(), "-Xshare:on", "-version")
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                unavailable.put(Mode.DEFAULT, "default class-data archive probe timed out");
            } else if (process.exitValue() != 0) {
                unavailable.put(Mode.DEFAULT, "default class-data archive is not usable (-Xshare:on exited "
                        + process.exitValue() + ")");
            }
        } catch (IOException failure) {
            unavailable.put(Mode.DEFAULT, "default class-data archive probe failed: " + failure.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            unavailable.put(Mode.DEFAULT, "default class-data archive probe interrupted");
        }
    }

    private void buildAppCdsArchive() {
        try {
            archiveDirectory = Files.createTempDirectory("coldstart-cds-");
            Path target = archiveDirectory.resolve("app.jsa");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteTree(archiveDirectory), "coldstart-cds-cleanup"));
            long started = System.nanoTime();
            try (ChildJvm training = ChildJvm.start(MAIN_CLASS, "child", "cds-training", Map.of(),
                    List.of("-XX:ArchiveClassesAtExit=" + target, "-Xshare:auto"), READY_TIMEOUT)) {
                training.stop(ARCHIVE_GRACE);
            }
            if (!Files.isRegularFile(target) || Files.size(target) == 0) {
                unavailable.put(Mode.APPCDS, "training run did not write an AppCDS archive");
                return;
            }
            archive = target;
            archiveBuildMs = (System.nanoTime() - started) / 1_000_000L;
            archiveBytes = Files.size(target);
        } catch (IOException | RuntimeException failure) {
            unavailable.put(Mode.APPCDS, "AppCDS archive could not be built: "
                    + (failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()));
        }
    }

    private static String childRuntime(ChildJvm child) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + child.port() + "/runtime"))
                .timeout(Duration.ofSeconds(2)).GET().build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    private static void deleteTree(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // Best effort at shutdown; the directory lives under the system temporary directory.
        }
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    static String jsonArray(List<String> values) {
        var quoted = new ArrayList<String>();
        for (String value : values) quoted.add("\"" + escape(value) + "\"");
        return "[" + String.join(",", quoted) + "]";
    }

    static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
