package com.palaashatri.bench.ctl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Application-process telemetry. Every value describes the measured process, never the load generator. CPU time
 * comes from {@link ProcessHandle} and resident memory from the platform's own process table, so the same
 * sampler runs on Linux, macOS and Windows. A platform that cannot report a value records null.
 */
public final class Telemetry {
    private static final Pattern GC_PAUSE = Pattern.compile("\\bPause\\b.*?([0-9]+(?:\\.[0-9]+)?)(ms|s)\\b");
    /** JDK 8 legacy GC logs end each collection with its duration in seconds, for example "0.0012345 secs]". */
    private static final Pattern LEGACY_GC_PAUSE = Pattern.compile("([0-9]+\\.[0-9]+) secs\\]");
    private static final Pattern NMT_TOTAL = Pattern.compile("Total:\\s+reserved=(\\d+)KB,\\s+committed=(\\d+)KB");
    private static final Pattern CODE_HEAP = Pattern.compile(
            "CodeHeap '([^']+)': size=(\\d+)Kb used=(\\d+)Kb max_used=(\\d+)Kb free=(\\d+)Kb");
    private static final Pattern CODE_CACHE = Pattern.compile(
            "CodeCache: size=(\\d+)Kb used=(\\d+)Kb max_used=(\\d+)Kb free=(\\d+)Kb");

    private Telemetry() {
    }

    /** One sample of a live process. Fields are null when the platform does not report them. */
    public record Sample(long epochNanos, Double cpuPercentOneCore, Double cpuPercentHost, Long rssBytes,
                         Long rssHighWaterBytes, Long threads) {
        Map<String, Object> toJson() {
            Map<String, Object> json = new java.util.LinkedHashMap<>();
            json.put("epoch_ns", epochNanos);
            json.put("process_cpu_pct_one_core", cpuPercentOneCore);
            json.put("process_cpu_pct_host", cpuPercentHost);
            json.put("rss_bytes", rssBytes);
            json.put("rss_high_water_bytes", rssHighWaterBytes);
            json.put("threads", threads);
            return json;
        }
    }

    /** Samples one process on its own thread until stopped. Sampling never terminates the measured process. */
    public static final class Sampler {
        private final long pid;
        private final Path output;
        private final Duration interval;
        private final List<Sample> samples = new CopyOnWriteArrayList<>();
        private final int logicalCpus = Math.max(1, Runtime.getRuntime().availableProcessors());
        private volatile boolean running = true;
        private Thread thread;
        private Duration lastCpu;
        private long lastNanos;

        public Sampler(long pid, Path output, Duration interval) {
            this.pid = pid;
            this.output = output;
            this.interval = interval;
        }

        public void start() {
            thread = new Thread(this::run, "process-sampler-" + pid);
            thread.setDaemon(true);
            thread.start();
        }

        /** Stops sampling, writes the per-sample file and returns the summary. */
        public Map<String, Object> stop() {
            running = false;
            if (thread != null) {
                try {
                    thread.join(Math.max(5_000, interval.toMillis() * 10));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            StringBuilder lines = new StringBuilder();
            for (Sample sample : samples) {
                lines.append(Json.write(sample.toJson())).append('\n');
            }
            try {
                Files.createDirectories(output.getParent());
                Files.writeString(output, lines.toString(), StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new BenchError("cannot write " + output + ": " + exception.getMessage(), exception);
            }
            return summarize(samples, logicalCpus);
        }

        private void run() {
            while (running) {
                Optional<Sample> sample = sample();
                if (sample.isEmpty()) {
                    return;
                }
                samples.add(sample.get());
                try {
                    Thread.sleep(interval.toMillis());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        private Optional<Sample> sample() {
            Optional<ProcessHandle> handle = ProcessHandle.of(pid);
            if (handle.isEmpty() || !handle.get().isAlive()) {
                return Optional.empty();
            }
            long now = System.nanoTime();
            Duration cpu = handle.get().info().totalCpuDuration().orElse(null);
            Double onePercent = null;
            Double hostPercent = null;
            if (cpu != null && lastCpu != null && now > lastNanos) {
                double elapsed = (now - lastNanos) / 1e9;
                onePercent = Math.max(0.0, (cpu.toNanos() - lastCpu.toNanos()) / 1e9 / elapsed * 100.0);
                hostPercent = onePercent / logicalCpus;
            }
            lastCpu = cpu;
            lastNanos = now;
            Map<String, Long> status = LinuxProcessStatus.read(pid);
            Long rss = status.containsKey("rss") ? status.get("rss") : ProcessMemory.residentBytes(pid);
            return Optional.of(new Sample(System.currentTimeMillis() * 1_000_000L, onePercent, hostPercent, rss,
                    status.get("rss_high_water"), status.get("threads")));
        }
    }

    /** Linux-only details from /proc/<pid>/status, read when present. Other platforms return an empty map. */
    static final class LinuxProcessStatus {
        private LinuxProcessStatus() {
        }

        static Map<String, Long> read(long pid) {
            Map<String, Long> values = new java.util.HashMap<>();
            if (!Repo.isLinux()) {
                return values;
            }
            try {
                for (String line : Files.readAllLines(Path.of("/proc/" + pid + "/status"), StandardCharsets.UTF_8)) {
                    int separator = line.indexOf(':');
                    if (separator < 0) {
                        continue;
                    }
                    String key = line.substring(0, separator);
                    String value = line.substring(separator + 1).strip();
                    if (key.equals("VmRSS")) {
                        values.put("rss", kibibytes(value));
                    } else if (key.equals("VmHWM")) {
                        values.put("rss_high_water", kibibytes(value));
                    } else if (key.equals("Threads")) {
                        values.put("threads", Long.parseLong(value.split("\\s+")[0]));
                    }
                }
            } catch (IOException | RuntimeException exception) {
                // The process exited between samples; the sampler stops on the next pass.
            }
            return values;
        }

        private static Long kibibytes(String value) {
            Matcher match = Pattern.compile("(\\d+)\\s+kB").matcher(value);
            return match.find() ? Long.parseLong(match.group(1)) * 1024 : null;
        }
    }

    /** Resident memory on macOS and Windows, from the platform's process tools. Null where neither works. */
    static final class ProcessMemory {
        private ProcessMemory() {
        }

        static Long residentBytes(long pid) {
            if (Repo.isLinux()) {
                return LinuxProcessStatus.read(pid).get("rss");
            }
            if (Repo.isMacOs()) {
                Shell.Result result = Shell.run(List.of("ps", "-o", "rss=", "-p", Long.toString(pid)), null, null,
                        Duration.ofSeconds(5));
                String text = result.stdout().strip();
                return result.succeeded() && text.matches("\\d+") ? Long.parseLong(text) * 1024 : null;
            }
            if (Repo.isWindows()) {
                Shell.Result result = Shell.run(
                        List.of("tasklist", "/FI", "PID eq " + pid, "/FO", "CSV", "/NH"), null, null, Duration.ofSeconds(5));
                if (!result.succeeded()) {
                    return null;
                }
                Matcher match = Pattern.compile("\"([0-9,.]+)\\s*K\"").matcher(result.stdout());
                return match.find() ? Long.parseLong(match.group(1).replaceAll("[^0-9]", "")) * 1024 : null;
            }
            return null;
        }
    }

    public static Map<String, Object> summarize(List<Sample> samples, int logicalCpus) {
        List<Double> cpuOne = new ArrayList<>();
        List<Double> cpuHost = new ArrayList<>();
        List<Double> rss = new ArrayList<>();
        List<Double> threads = new ArrayList<>();
        for (Sample sample : samples) {
            if (sample.cpuPercentOneCore() != null) {
                cpuOne.add(sample.cpuPercentOneCore());
            }
            if (sample.cpuPercentHost() != null) {
                cpuHost.add(sample.cpuPercentHost());
            }
            if (sample.rssBytes() != null) {
                rss.add((double) sample.rssBytes());
            }
            if (sample.threads() != null) {
                threads.add((double) sample.threads());
            }
        }
        return Json.map(
                "sample_count", samples.size(),
                "process_cpu_pct_one_core_mean", Stats.mean(cpuOne),
                "process_cpu_pct_one_core_p95", percentile(cpuOne, 95),
                "process_cpu_pct_host_mean", Stats.mean(cpuHost),
                "rss_bytes_mean", Stats.mean(rss),
                "rss_bytes_p95", percentile(rss, 95),
                "rss_bytes_max", rss.isEmpty() ? null : (long) rss.stream().mapToDouble(Double::doubleValue).max().orElse(0),
                "threads_max", threads.isEmpty() ? null : (long) threads.stream().mapToDouble(Double::doubleValue).max()
                        .orElse(0));
    }

    /** Nearest-rank percentile, as the controller has always reported it. */
    public static Double percentile(List<Double> values, double percentile) {
        if (values.isEmpty()) {
            return null;
        }
        List<Double> ordered = values.stream().sorted().toList();
        int index = Math.min(ordered.size() - 1, Math.max(0,
                (int) Math.ceil(percentile / 100.0 * ordered.size()) - 1));
        return ordered.get(index);
    }

    /** GC pause statistics from unified (JDK 9+) or legacy (JDK 8) GC log text. */
    public static Map<String, Object> parseGcPauses(String text) {
        List<Double> values = new ArrayList<>();
        for (String line : text.lines().toList()) {
            Matcher pause = GC_PAUSE.matcher(line);
            if (pause.find()) {
                double value = Double.parseDouble(pause.group(1));
                values.add("s".equals(pause.group(2)) ? value * 1000.0 : value);
                continue;
            }
            Matcher legacy = LEGACY_GC_PAUSE.matcher(line);
            if (legacy.find()) {
                values.add(Double.parseDouble(legacy.group(1)) * 1000.0);
            }
        }
        double total = values.stream().mapToDouble(Double::doubleValue).sum();
        return Json.map(
                "count", values.size(),
                "total_ms", total,
                "p50_ms", percentile(values, 50),
                "p95_ms", percentile(values, 95),
                "p99_ms", percentile(values, 99),
                "max_ms", values.isEmpty() ? null : values.stream().mapToDouble(Double::doubleValue).max().getAsDouble());
    }

    public static Map<String, Object> parseNmt(String text) {
        Matcher match = NMT_TOTAL.matcher(text);
        if (!match.find()) {
            return Json.map("reserved_bytes", null, "committed_bytes", null);
        }
        return Json.map(
                "reserved_bytes", Long.parseLong(match.group(1)) * 1024,
                "committed_bytes", Long.parseLong(match.group(2)) * 1024);
    }

    public static Map<String, Object> parseCodeCache(String text) {
        List<Map<String, Object>> segments = new ArrayList<>();
        for (String line : text.lines().toList()) {
            Matcher heap = CODE_HEAP.matcher(line);
            if (heap.find()) {
                segments.add(segment(heap.group(1), heap));
            }
        }
        if (segments.isEmpty()) {
            Matcher single = CODE_CACHE.matcher(text);
            if (single.find()) {
                segments.add(Json.map(
                        "name", "CodeCache",
                        "size_bytes", Long.parseLong(single.group(1)) * 1024,
                        "used_bytes", Long.parseLong(single.group(2)) * 1024,
                        "max_used_bytes", Long.parseLong(single.group(3)) * 1024,
                        "free_bytes", Long.parseLong(single.group(4)) * 1024));
            }
        }
        long size = segments.stream().mapToLong(item -> (Long) item.get("size_bytes")).sum();
        long used = segments.stream().mapToLong(item -> (Long) item.get("used_bytes")).sum();
        long maxUsed = segments.stream().mapToLong(item -> (Long) item.get("max_used_bytes")).sum();
        return Json.map(
                "segments", segments,
                "size_bytes", size == 0 ? null : size,
                "used_bytes", used == 0 ? null : used,
                "max_used_bytes", maxUsed == 0 ? null : maxUsed);
    }

    private static Map<String, Object> segment(String name, Matcher match) {
        return Json.map(
                "name", name,
                "size_bytes", Long.parseLong(match.group(2)) * 1024,
                "used_bytes", Long.parseLong(match.group(3)) * 1024,
                "max_used_bytes", Long.parseLong(match.group(4)) * 1024,
                "free_bytes", Long.parseLong(match.group(5)) * 1024);
    }

    /** Compiler counters from {@code jstat -compiler}, located by column name rather than by position. */
    public static Map<String, Object> parseJstatCompiler(String text) {
        List<String[]> lines = new ArrayList<>();
        for (String line : text.lines().toList()) {
            // JAVA_TOOL_OPTIONS adds a banner that is not part of the table.
            if (!line.isBlank() && !line.startsWith("Picked up ")) {
                lines.add(line.strip().split("\\s+"));
            }
        }
        int header = -1;
        for (int index = 0; index < lines.size(); index++) {
            if (java.util.Arrays.asList(lines.get(index)).contains("Compiled")) {
                header = index;
                break;
            }
        }
        if (header < 0 || header + 1 >= lines.size()) {
            return Json.map("compiled", null, "failed", null, "invalid", null, "time_seconds", null);
        }
        Map<String, String> columns = new java.util.HashMap<>();
        String[] names = lines.get(header);
        String[] values = lines.get(header + 1);
        for (int index = 0; index < Math.min(names.length, values.length); index++) {
            columns.put(names[index], values[index]);
        }
        return Json.map(
                "compiled", columns.containsKey("Compiled") ? Long.parseLong(columns.get("Compiled")) : null,
                "failed", columns.containsKey("Failed") ? Long.parseLong(columns.get("Failed")) : null,
                "invalid", columns.containsKey("Invalid") ? Long.parseLong(columns.get("Invalid")) : null,
                "time_seconds", columns.containsKey("Time") ? Double.parseDouble(columns.get("Time")) : null);
    }

    /** Allocation-sample weight, compilation and GC counts from a {@code jfr print --json} document. */
    public static Map<String, Object> parseJfr(Path json) {
        Map<String, Object> empty = Json.map(
                "allocation_sample_weight_bytes", null,
                "compilation_events", null,
                "compilation_duration_ms", null,
                "gc_events", null);
        if (!Files.isRegularFile(json)) {
            return empty;
        }
        String text;
        try {
            text = Files.readString(json, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return empty;
        }
        if (text.isBlank()) {
            return empty;
        }
        Map<String, Object> document = Json.asMap(Json.parse(text));
        List<Object> events = Json.asList(Json.asMap(document.get("recording")).get("events"));
        long allocationWeight = 0;
        boolean allocationSeen = false;
        long compilationEvents = 0;
        double compilationMs = 0;
        long gcEvents = 0;
        for (Object item : events) {
            Map<String, Object> event = Json.asMap(item);
            Map<String, Object> values = Json.asMap(event.get("values"));
            String type = String.valueOf(event.get("type"));
            if (type.equals("jdk.ObjectAllocationSample") && values.get("weight") instanceof Number weight) {
                allocationWeight += weight.longValue();
                allocationSeen = true;
            } else if (type.equals("jdk.Compilation")) {
                compilationEvents++;
                compilationMs += durationMilliseconds(values.get("duration")).orElse(0.0);
            } else if (type.equals("jdk.GarbageCollection")) {
                gcEvents++;
            }
        }
        return Json.map(
                "allocation_sample_weight_bytes", allocationSeen ? allocationWeight : null,
                "compilation_events", compilationEvents,
                "compilation_duration_ms", compilationMs,
                "gc_events", gcEvents);
    }

    static Optional<Double> durationMilliseconds(Object value) {
        if (value instanceof Number number) {
            // JFR JSON numeric durations are nanoseconds.
            return Optional.of(number.doubleValue() / 1_000_000.0);
        }
        if (value instanceof String text) {
            Matcher match = Pattern.compile("PT([0-9]+(?:\\.[0-9]+)?)S").matcher(text);
            if (match.matches()) {
                return Optional.of(Double.parseDouble(match.group(1)) * 1000.0);
            }
        }
        return Optional.empty();
    }

    /** Runs a diagnostic tool and records its output; a tool that cannot run records its error and exit code 127. */
    public static Map<String, Object> runTool(List<String> command, Path output) {
        Shell.Result result = Shell.run(command, null, null, Duration.ofSeconds(30));
        try {
            Files.createDirectories(output.getParent());
            Files.writeString(output, result.combined() + "\n", StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new BenchError("cannot write " + output + ": " + exception.getMessage(), exception);
        }
        return Json.map("returncode", result.exitCode(), "output", result.combined());
    }

    static String lower(String text) {
        return text.toLowerCase(Locale.ROOT);
    }
}
