package com.palaashatri.bench.ctl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds OpenJDK HotSpot runtimes on any platform and records what each one can do. Every capability is probed by
 * starting the runtime, so a flag that is absent or rejected is recorded as unsupported, never assumed.
 */
public final class Runtimes {
    static final String SCHEMA_VERSION = "1.0.0";
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(20);

    /** Collector flags in the order the controller reports them. "default" uses the JVM's own choice. */
    static final Map<String, List<String>> COLLECTOR_FLAGS = new LinkedHashMap<>();

    static {
        COLLECTOR_FLAGS.put("serial", List.of("-XX:+UseSerialGC"));
        COLLECTOR_FLAGS.put("parallel", List.of("-XX:+UseParallelGC"));
        COLLECTOR_FLAGS.put("cms", List.of("-XX:+UseConcMarkSweepGC"));
        COLLECTOR_FLAGS.put("g1", List.of("-XX:+UseG1GC"));
        COLLECTOR_FLAGS.put("zgc", List.of("-XX:+UnlockExperimentalVMOptions", "-XX:+UseZGC"));
        COLLECTOR_FLAGS.put("shenandoah", List.of("-XX:+UseShenandoahGC"));
        COLLECTOR_FLAGS.put("epsilon", List.of("-XX:+UnlockExperimentalVMOptions", "-XX:+UseEpsilonGC"));
    }

    private static final Pattern VERSION_QUOTED = Pattern.compile("version\\s+\"([^\"]+)\"");
    private static final Pattern VERSION_OPENJDK = Pattern.compile("openjdk\\s+([0-9][^\\s]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROPERTY = Pattern.compile("^\\s*([\\w.]+)\\s*=\\s*(.*?)\\s*$");

    private Runtimes() {
    }

    /** Probes every candidate, writes .benchctl/runtimes.json and returns the accepted runtimes, oldest JDK first. */
    public static List<Map<String, Object>> discover(Repo repo) {
        List<Map<String, Object>> runtimes = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (Path java : candidateExecutables(repo)) {
            try {
                Map<String, Object> runtime = probe(java);
                String vm = (String) runtime.get("vm_name");
                if (!isOpenJdkHotSpot(runtime)) {
                    errors.add("skipped non-OpenJDK-HotSpot runtime: " + java + " (" + vm + ")");
                    continue;
                }
                long feature = ((Number) runtime.get("feature_version")).longValue();
                if (feature < 8 || feature > 25) {
                    errors.add("skipped JDK " + feature + " outside configured range: " + java);
                    continue;
                }
                // The same installation can be reachable through several paths; one record per identity.
                boolean seen = runtimes.stream().anyMatch(item -> item.get("id").equals(runtime.get("id")));
                if (seen) {
                    errors.add("duplicate of an earlier runtime skipped: " + java);
                } else {
                    runtimes.add(runtime);
                }
            } catch (BenchError exception) {
                errors.add(java + ": " + exception.getMessage());
            }
        }
        runtimes.sort(Comparator.comparingLong((Map<String, Object> item) -> ((Number) item.get("feature_version")).longValue())
                .thenComparing(item -> (String) item.get("id")));
        try {
            Files.createDirectories(repo.stateDir());
            Files.writeString(repo.runtimesFile(), Json.pretty(Json.map(
                    "schema_version", SCHEMA_VERSION, "runtimes", runtimes, "errors", errors)));
        } catch (IOException exception) {
            throw new BenchError("cannot write " + repo.runtimesFile() + ": " + exception.getMessage(), exception);
        }
        return runtimes;
    }

    /** Runtimes from the last discovery, discovering first when there is no record yet. */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> load(Repo repo) {
        if (!Files.isRegularFile(repo.runtimesFile())) {
            return discover(repo);
        }
        List<Map<String, Object>> runtimes = new ArrayList<>();
        for (Object item : Json.asList(Json.asMap(repo.readJson(repo.runtimesFile())).get("runtimes"))) {
            runtimes.add((Map<String, Object>) item);
        }
        return runtimes;
    }

    /** Starts one candidate and records its version, properties, tools, collectors and capabilities. */
    public static Map<String, Object> probe(Path java) {
        Shell.Result version = Shell.run(List.of(java.toString(), "-version"), null, null, PROBE_TIMEOUT);
        if (!version.succeeded()) {
            throw new BenchError("is not runnable: " + version.combined());
        }
        String versionText = version.combined();
        long feature = parseFeatureVersion(versionText);
        Shell.Result settings = Shell.run(
                List.of(java.toString(), "-XshowSettings:properties", "-version"), null, null, PROBE_TIMEOUT);
        Map<String, String> properties = parseProperties(settings.combined());
        Path javaHome = Paths.get(properties.getOrDefault("java.home", java.toAbsolutePath().getParent().getParent().toString()))
                .toAbsolutePath().normalize();
        // JDK 8 reports java.home as its jre directory; its tools live in the parent's bin.
        if (javaHome.getFileName() != null && javaHome.getFileName().toString().equals("jre")
                && Files.isDirectory(javaHome.getParent().resolve("bin"))) {
            javaHome = javaHome.getParent();
        }
        Path executable = realPath(java);
        String identity = Digest.sha256(executable);

        Map<String, Object> collectors = new LinkedHashMap<>();
        collectors.put("default", Json.map("supported", true, "flags", List.of(), "reason", "runtime default"));
        for (Map.Entry<String, List<String>> collector : COLLECTOR_FLAGS.entrySet()) {
            Probe probe = probeFlags(java, collector.getValue());
            collectors.put(collector.getKey(), Json.map(
                    "supported", probe.supported(), "flags", collector.getValue(), "reason", probe.reason()));
        }

        Map<String, Object> tools = new LinkedHashMap<>();
        for (String tool : List.of("javac", "jar", "jcmd", "jstat", "jmap", "jstack", "jfr")) {
            Path candidate = Repo.javaExecutable(javaHome, tool);
            tools.put(tool, Files.isRegularFile(candidate) ? candidate.toString() : null);
        }

        boolean unified = probeFlags(java, List.of("-Xlog:help")).supported();
        boolean legacyGc = probeFlags(java, List.of("-XX:+PrintGCDetails")).supported();
        boolean jfr = probeFlags(java, List.of("-XX:+FlightRecorder")).supported();
        boolean nmt = probeFlags(java, List.of("-XX:NativeMemoryTracking=summary")).supported();
        boolean cds = probeFlags(java, List.of("-Xshare:auto")).supported();
        boolean containers = probeFlags(java, List.of("-XX:+UseContainerSupport")).supported();
        boolean vectorApi = feature >= 9 && probeFlags(java, List.of("--add-modules", "jdk.incubator.vector")).supported();
        boolean generationalZgc = probeFlags(java,
                List.of("-XX:+UnlockExperimentalVMOptions", "-XX:+UseZGC", "-XX:+ZGenerational")).supported();
        boolean compactHeaders = probeFlags(java,
                List.of("-XX:+UnlockExperimentalVMOptions", "-XX:+UseCompactObjectHeaders")).supported();
        boolean printFlags = probeFlags(java, List.of("-XX:+PrintFlagsFinal")).supported();

        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("print_flags_final", printFlags);
        capabilities.put("unified_logging", unified);
        capabilities.put("legacy_gc_logging", legacyGc);
        capabilities.put("jfr", jfr);
        capabilities.put("nmt", nmt);
        capabilities.put("cds", cds);
        capabilities.put("appcds", feature >= 10);
        capabilities.put("dynamic_cds", feature >= 13);
        capabilities.put("container_awareness", containers);
        capabilities.put("modules", feature >= 9);
        capabilities.put("records", feature >= 16);
        capabilities.put("sealed_classes", feature >= 17);
        capabilities.put("virtual_threads", feature >= 21);
        capabilities.put("structured_concurrency_api", feature >= 21);
        capabilities.put("vector_api", vectorApi);
        capabilities.put("ffm_api", feature >= 19);
        capabilities.put("ffm_api_stage", feature >= 22 ? "final" : feature >= 19 ? "preview" : "unavailable");
        capabilities.put("generational_zgc", generationalZgc);
        capabilities.put("compact_object_headers", compactHeaders);
        capabilities.put("preview_features", feature >= 12);
        capabilities.put("code_cache_diagnostics", tools.get("jcmd") != null);

        String architecture = properties.getOrDefault("os.arch", System.getProperty("os.arch", "unknown"));
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("schema_version", SCHEMA_VERSION);
        runtime.put("id", "openjdk-" + feature + "-" + identity.substring(0, 12));
        runtime.put("java_home", javaHome.toString());
        runtime.put("java", executable.toString());
        runtime.put("feature_version", feature);
        runtime.put("vendor", properties.getOrDefault("java.vendor", "unknown"));
        runtime.put("runtime_name", properties.getOrDefault("java.runtime.name", "unknown"));
        runtime.put("runtime_version", properties.getOrDefault("java.runtime.version", "unknown"));
        runtime.put("vm_name", properties.getOrDefault("java.vm.name", "unknown"));
        runtime.put("vm_version", properties.getOrDefault("java.vm.version", "unknown"));
        runtime.put("vm_info", properties.getOrDefault("java.vm.info", "unknown"));
        runtime.put("architecture", architecture);
        runtime.put("version_output", versionText);
        runtime.put("identity", identity);
        runtime.put("tools", tools);
        runtime.put("collectors", collectors);
        runtime.put("capabilities", capabilities);
        return runtime;
    }

    /** The executable with symbolic links resolved, or the absolute path when the file system cannot resolve it. */
    static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException exception) {
            return path.toAbsolutePath().normalize();
        }
    }

    /** A JDK whose name carries OpenJDK and is not one of the excluded distributions. */
    public static boolean isOpenJdkHotSpot(Map<String, Object> runtime) {
        String label = (runtime.get("runtime_name") + " " + runtime.get("vm_name")).toLowerCase(java.util.Locale.ROOT);
        String excludedOne = "open" + "j9";
        String excludedTwo = "graal" + "vm";
        return label.contains("openjdk") && !label.contains(excludedOne) && !label.contains(excludedTwo);
    }

    static long parseFeatureVersion(String text) {
        Matcher quoted = VERSION_QUOTED.matcher(text);
        String version;
        if (quoted.find()) {
            version = quoted.group(1);
        } else {
            Matcher openjdk = VERSION_OPENJDK.matcher(text);
            if (!openjdk.find()) {
                throw new BenchError("cannot parse Java version from: " + text.substring(0, Math.min(160, text.length())));
            }
            version = openjdk.group(1);
        }
        if (version.startsWith("1.")) {
            return Long.parseLong(version.split("\\.")[1]);
        }
        Matcher feature = Pattern.compile("^\\d+").matcher(version);
        if (!feature.find()) {
            throw new BenchError("cannot parse Java feature version from: " + version);
        }
        return Long.parseLong(feature.group());
    }

    static Map<String, String> parseProperties(String text) {
        Map<String, String> properties = new LinkedHashMap<>();
        for (String line : text.split("\\R")) {
            Matcher match = PROPERTY.matcher(line);
            if (match.matches()) {
                properties.put(match.group(1), match.group(2));
            }
        }
        return properties;
    }

    private record Probe(boolean supported, String reason) {
    }

    private static Probe probeFlags(Path java, List<String> flags) {
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.addAll(flags);
        command.add("-version");
        Shell.Result result = Shell.run(command, null, null, PROBE_TIMEOUT);
        if (result.succeeded()) {
            return new Probe(true, "supported");
        }
        List<String> lines = result.combined().lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
        String reason = lines.isEmpty() ? "exit " + result.exitCode() : lines.get(lines.size() - 1);
        return new Probe(false, reason.length() > 300 ? reason.substring(0, 300) : reason);
    }

    /** Candidate JDK executables in discovery order: explicit settings first, then installation locations. */
    static List<Path> candidateExecutables(Repo repo) {
        List<Path> candidates = new ArrayList<>();
        String executable = Repo.isWindows() ? "java.exe" : "java";
        String explicit = System.getenv("BENCH_JAVA_HOMES");
        if (explicit != null) {
            for (String raw : explicit.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                addHome(candidates, raw, executable);
            }
        }
        addHome(candidates, System.getenv("JAVA_HOME"), executable);
        String path = System.getenv("PATH");
        if (path != null) {
            for (String entry : path.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                if (!entry.isBlank()) {
                    addExecutable(candidates, Paths.get(entry).resolve(executable));
                }
            }
        }
        if (Repo.isMacOs()) {
            childDirectories(Paths.get("/Library/Java/JavaVirtualMachines"))
                    .forEach(home -> addHome(candidates, home.resolve("Contents/Home").toString(), executable));
        }
        if (Repo.isWindows()) {
            String programFiles = System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files");
            for (String vendor : List.of("Java", "Eclipse Adoptium", "Microsoft", "Zulu")) {
                childDirectories(Paths.get(programFiles, vendor))
                        .forEach(home -> addHome(candidates, home.toString(), executable));
            }
        } else {
            childDirectories(Paths.get("/usr/lib/jvm")).forEach(home -> addHome(candidates, home.toString(), executable));
            childDirectories(Paths.get("/opt/jdks")).forEach(home -> addHome(candidates, home.toString(), executable));
        }
        Path home = Paths.get(System.getProperty("user.home"));
        childDirectories(home.resolve(".sdkman/candidates/java"))
                .forEach(dir -> addHome(candidates, dir.toString(), executable));
        childDirectories(home.resolve(".jabba/jdk"))
                .forEach(dir -> addHome(candidates, dir.toString(), executable));
        childDirectories(repo.stateDir().resolve("jdks"))
                .forEach(dir -> addHome(candidates, dir.toString(), executable));

        LinkedHashMap<Path, Boolean> unique = new LinkedHashMap<>();
        for (Path candidate : candidates) {
            unique.put(candidate, Boolean.TRUE);
        }
        return new ArrayList<>(unique.keySet());
    }

    private static void addHome(List<Path> candidates, String raw, String executable) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        Path path = Paths.get(raw.strip());
        if (path.getFileName() != null && path.getFileName().toString().equalsIgnoreCase(executable)) {
            addExecutable(candidates, path);
        } else {
            addExecutable(candidates, path.resolve("bin").resolve(executable));
        }
    }

    private static void addExecutable(List<Path> candidates, Path candidate) {
        if (Files.isRegularFile(candidate)) {
            try {
                candidates.add(candidate.toRealPath());
            } catch (IOException exception) {
                candidates.add(candidate.toAbsolutePath().normalize());
            }
        }
    }

    private static List<Path> childDirectories(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(directory)) {
            return children.filter(Files::isDirectory).sorted().toList();
        } catch (IOException exception) {
            return List.of();
        }
    }

}
