package com.palaashatri.bench.ctl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The Tier 2 measurement engine. A workload's {@code tier2.json} contract says how to build, check, launch and
 * load it; the engine owns the measurement: it starts the measured JVM, drives it with the open-loop load
 * generator, samples the process, dumps the flight recording, and applies the validity gates. A run is never
 * marked valid merely because it exits 0. Works on any operating system with a JDK.
 */
public final class Tier2 {
    static final String LOADGEN_MAIN = "com.palaashatri.bench.load.OpenLoopLoad";
    /** Pinned, so the classpath written for the load generator never depends on the latest plugin release. */
    static final String DEPENDENCY_PLUGIN = "3.6.1";
    /** Latency settles more slowly and more noisily than throughput; reported, not gated, until calibrated. */
    static final double LATENCY_PROVISIONAL_MAX_CV = 0.20;
    static final List<String> CRITICAL_ENVIRONMENT_FIELDS = List.of(
            "os", "os_release", "architecture", "cpu_model", "physical_cores", "logical_cpus", "memory_bytes",
            "cgroup_version", "cgroup_cpu_max", "cgroup_memory_max", "cpu_governors", "transparent_hugepages",
            "transparent_hugepage_mode", "smt_control", "numa_nodes", "online_cpus", "swap_devices",
            "injected_jvm_options");
    /** Metrics screened for outliers. They are reported with their repetition numbers, never removed. */
    static final List<Map.Entry<String, List<String>>> OUTLIER_METRICS = List.of(
            Map.entry("throughput", List.of("load", "kpis", "throughput")),
            Map.entry("p99_ms", List.of("load", "kpis", "p99_ms")),
            Map.entry("gc_pause_p99_ms", List.of("telemetry", "gc", "p99_ms")),
            Map.entry("rss_mb_max", List.of("telemetry", "process", "rss_bytes_max")));

    private final Repo repo;
    private final PrintStream out;

    public Tier2(Repo repo, PrintStream out) {
        this.repo = repo;
        this.out = out;
    }

    /**
     * A Tier 2 workload as declared by its benchmarks/<id>/tier2.json contract and its catalogue entry. The
     * {@code loadShape} is {@code http-mix} (the load generator drives the request mix in {@code loadMix}) or {@code jobs}
     * (the engine submits jobs on the schedule and times each to completion, described by {@code jobs}).
     */
    public record Workload(String id, Path directory, Path appDirectory, String mainClass, String tokenPrefix,
                           String identityPath, Path loadMix, List<String> correctness, List<String> build,
                           String classpathFile, List<String> classpathPrefix, List<String> jvmOptions,
                           long minJdk, long maxJdk, String tier, String telemetryScope, String loadShape,
                           JobDriver.Contract jobs, Map<String, Object> spec) {
    }

    public record Options(String javaHome, String loadgenJavaHome, String collector, double targetRate,
                          int warmupSeconds, int measureSeconds, int threads, int repetitions, long seed,
                          int heapMb, String runKind, String workloadId, Path output, boolean skipCorrectness,
                          List<String> extraJvmArguments) {
    }

    /**
     * How telemetry covers the workload. A {@code process} workload is one measured JVM. A {@code process-tree}
     * workload starts child JVMs (the gateway pattern of 02, 10 and 11) and samples every descendant of the
     * application process.
     */
    static String telemetryScope(Map<String, Object> spec) {
        String scope = spec.containsKey("telemetry_scope") ? string(spec, "telemetry_scope") : "process";
        if (!Set.of("process", "process-tree").contains(scope)) {
            throw new BenchError("telemetry_scope must be process or process-tree, not " + scope);
        }
        return scope;
    }

    public static Workload load(Repo repo, String workloadId) {
        Path directory = repo.workloadDirectory(workloadId);
        Path descriptor = directory.resolve("tier2.json");
        if (!Files.isRegularFile(descriptor)) {
            throw new BenchError(workloadId + " has no tier2.json measurement contract");
        }
        Map<String, Object> spec = Json.asMap(repo.readJson(descriptor));
        Map<String, Object> entry = repo.catalog().stream()
                .filter(item -> workloadId.equals(item.get("id"))).findFirst()
                .orElseThrow(() -> new BenchError(workloadId + " is not in benchmarks/catalog.json"));
        String loadShape = spec.containsKey("load_shape") ? string(spec, "load_shape") : "http-mix";
        if (!Set.of("http-mix", "jobs", "startup", "framed-tcp").contains(loadShape)) {
            throw new BenchError("load_shape must be http-mix, jobs, startup or framed-tcp, not " + loadShape);
        }
        Path loadMix = null;
        JobDriver.Contract jobs = null;
        if (loadShape.equals("jobs")) {
            jobs = jobContract(spec);
        } else if (loadShape.equals("http-mix")) {
            loadMix = directory.resolve(string(spec, "load_mix"));
        }
        return new Workload(
                workloadId,
                directory,
                directory.resolve(string(spec, "app_dir")),
                string(spec, "main_class"),
                string(spec, "token_prefix"),
                string(spec, "identity_path"),
                loadMix,
                strings(spec.get("correctness")),
                strings(spec.get("build")),
                string(spec, "classpath_file"),
                strings(spec.get("classpath_prefix")),
                strings(spec.get("jvm_options")),
                ((Number) entry.get("min_jdk")).longValue(),
                ((Number) entry.get("max_jdk")).longValue(),
                String.valueOf(entry.get("tier")), telemetryScope(spec), loadShape, jobs, spec);
    }

    /** The job endpoints a {@code jobs} load shape needs, declared under {@code "jobs"} in tier2.json. */
    private static JobDriver.Contract jobContract(Map<String, Object> spec) {
        Map<String, Object> jobs = Json.asMap(spec.get("jobs"));
        if (jobs.isEmpty()) {
            throw new BenchError("a jobs load_shape needs a jobs object in tier2.json");
        }
        return new JobDriver.Contract(string(jobs, "submit_path"), string(jobs, "status_path"),
                string(jobs, "job_id_field"), string(jobs, "state_field"), string(jobs, "completed_state"),
                string(jobs, "failed_state"));
    }

    /** Parses the command line. Flags mirror the engine's documented options; every value has a stated default. */
    public static Options parse(String[] arguments) {
        Map<String, String> values = new HashMap<>();
        List<String> jvmArguments = new ArrayList<>();
        boolean skipCorrectness = false;
        for (int index = 0; index < arguments.length; index++) {
            String flag = arguments[index];
            if (flag.equals("--skip-correctness")) {
                skipCorrectness = true;
                continue;
            }
            if (!flag.startsWith("--") || index + 1 >= arguments.length) {
                throw new BenchError("expected --option value, got: " + flag);
            }
            String value = arguments[++index];
            if (flag.equals("--jvm-arg")) {
                jvmArguments.add(value);
            } else {
                values.put(flag.substring(2), value);
            }
        }
        for (String required : List.of("java-home", "out")) {
            if (!values.containsKey(required)) {
                throw new BenchError("tier2 requires --" + required);
            }
        }
        String collector = values.getOrDefault("gc", "g1");
        if (!COLLECTOR_CHOICES.contains(collector)) {
            throw new BenchError("--gc must be one of " + COLLECTOR_CHOICES);
        }
        String runKind = values.getOrDefault("run-kind", "benchmark");
        if (!runKind.equals("calibration") && !runKind.equals("benchmark")) {
            throw new BenchError("--run-kind must be calibration or benchmark");
        }
        return new Options(
                values.get("java-home"),
                values.get("loadgen-java-home"),
                collector,
                Double.parseDouble(values.getOrDefault("target-rate", "100")),
                Integer.parseInt(values.getOrDefault("warmup-seconds", "30")),
                Integer.parseInt(values.getOrDefault("measure-seconds", "60")),
                Integer.parseInt(values.getOrDefault("threads", "32")),
                Integer.parseInt(values.getOrDefault("repetitions", "5")),
                Long.parseLong(values.getOrDefault("seed", "424242")),
                Integer.parseInt(values.getOrDefault("heap-mb", "512")),
                runKind,
                values.getOrDefault("workload", "01-fintech-ledger"),
                Path.of(values.get("out")),
                skipCorrectness,
                List.copyOf(jvmArguments));
    }

    static final Set<String> COLLECTOR_CHOICES = Set.of("default", "serial", "parallel", "g1", "zgc", "shenandoah", "epsilon");

    public Map<String, Object> run(Options options) {
        if (options.repetitions() < 1) {
            throw new BenchError("repetitions must be positive");
        }
        if ("benchmark".equals(options.runKind()) && options.repetitions() < 5) {
            throw new BenchError("benchmark runs require at least five repetitions");
        }
        if (options.targetRate() <= 0 || options.warmupSeconds() < 0 || options.measureSeconds() < 1) {
            throw new BenchError("invalid load phase configuration");
        }
        Workload workload = load(repo, options.workloadId());
        Path output = options.output().toAbsolutePath().normalize();
        if (Files.isDirectory(output) && hasEntries(output)) {
            throw new BenchError("output directory is not empty: " + output);
        }
        try {
            Files.createDirectories(output);
        } catch (IOException exception) {
            throw new BenchError("cannot create " + output + ": " + exception.getMessage(), exception);
        }

        Map<String, Object> runtime = validateRuntime(Path.of(options.javaHome()).toAbsolutePath().normalize(),
                options.collector(), workload);
        Map<String, Object> preflightBefore = HostEnvironment.preflight(HostEnvironment.fingerprint(repo));
        Path javaHome = Path.of((String) runtime.get("java_home"));
        Map<String, String> environment = javaEnvironment(javaHome);
        writeJson(output.resolve("runtime.json"), runtime);
        writeJson(output.resolve("experiment.json"), Json.map(
                "schema_version", "1.0.0",
                "workload", workload.id(),
                "run_kind", options.runKind(),
                "runtime", runtime.get("id"),
                "gc", options.collector(),
                "target_rate", options.targetRate(),
                "warmup_seconds", options.warmupSeconds(),
                "measure_seconds", options.measureSeconds(),
                "threads", options.threads(),
                "repetitions", options.repetitions(),
                "seed", options.seed(),
                "heap_mb", options.heapMb(),
                "extra_jvm_arguments", options.extraJvmArguments()));

        if (!options.skipCorrectness()) {
            correctnessGate(workload, environment, output.resolve("correctness.log"));
        }
        String classpath = buildClasspath(workload, environment, output.resolve("build-workload.log"));
        if (workload.loadShape().equals("framed-tcp")) {
            buildDriver(workload, environment, output.resolve("build-driver.log"));
        }
        Path loadgenJava = options.loadgenJavaHome() != null ? Path.of(options.loadgenJavaHome())
                : defaultLoadgenJavaHome();
        Map<String, String> loadgenEnvironment = javaEnvironment(loadgenJava.toAbsolutePath().normalize());
        String loadgenClasspath = buildLoadgen(loadgenEnvironment, output.resolve("build-loadgen.log"));

        List<Map<String, Object>> repetitions = new ArrayList<>();
        for (int repetition = 1; repetition <= options.repetitions(); repetition++) {
            Map<String, Object> result = repetition(new RepetitionSpec(workload, repetition,
                    output.resolve("repetitions").resolve(String.format("%02d", repetition)), runtime, options,
                    classpath, environment, loadgenClasspath, loadgenEnvironment));
            repetitions.add(result);
            out.println("repetition " + repetition + "/" + options.repetitions() + ": " + result.get("status"));
        }

        Map<String, Object> preflightAfter = HostEnvironment.preflight(HostEnvironment.fingerprint(repo));
        Map<String, Object> preflight = combinePreflight(preflightBefore, preflightAfter);
        Validity validity = validity(repetitions, options.runKind(), preflight);
        Map<String, Object> aggregate = aggregate(repetitions, options.seed());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema_version", "1.0.0");
        result.put("benchmark", workload.id());
        result.put("run_kind", options.runKind());
        // The tier comes from the catalogue; validity is reported separately.
        result.put("implementation_tier", workload.tier());
        result.put("measurement_valid", validity.valid());
        result.put("invalid_reasons", validity.reasons());
        result.put("warnings", validity.warnings());
        result.put("runtime", runtime.get("id"));
        result.put("java_identity_sha256", runtime.get("identity"));
        result.put("requested_collector", options.collector());
        result.put("repetitions", repetitions);
        result.put("preflight", preflight);
        result.put("outliers", outlierReport(repetitions));
        result.put("aggregate", aggregate);
        writeJson(output.resolve("result.json"), result);
        writeJson(output.resolve("checksums.json"), Digest.tree(output));
        out.print(Json.pretty(Json.map(
                "output", output.toString(),
                "measurement_valid", validity.valid(),
                "invalid_reasons", validity.reasons())));
        return result;
    }

    private record RepetitionSpec(Workload workload, int repetition, Path directory, Map<String, Object> runtime,
                                  Options options, String classpath, Map<String, String> environment,
                                  String loadgenClasspath, Map<String, String> loadgenEnvironment) {
    }

    private Map<String, Object> repetition(RepetitionSpec spec) {
        Workload workload = spec.workload();
        Map<String, Object> runtime = spec.runtime();
        String collector = spec.options().collector();
        Path directory = spec.directory();
        try {
            Files.createDirectories(directory.resolve("telemetry"));
        } catch (IOException exception) {
            throw new BenchError("cannot create " + directory + ": " + exception.getMessage(), exception);
        }
        Path javaHome = Path.of((String) runtime.get("java_home"));
        Path java = Repo.javaExecutable(javaHome, "java");
        Path jcmd = Repo.javaExecutable(javaHome, "jcmd");
        Path jstat = Repo.javaExecutable(javaHome, "jstat");
        Path jfrTool = Repo.javaExecutable(javaHome, "jfr");
        String token = Digest.sha256(workload.tokenPrefix() + "-" + spec.repetition() + "-" + spec.options().seed()
                + "-" + System.nanoTime());
        Map<String, String> environment = new HashMap<>(spec.environment());
        environment.put("BENCH_RUN_TOKEN", token);
        boolean tree = "process-tree".equals(workload.telemetryScope());
        Path childLogs = directory.resolve("telemetry/children");
        if (tree) {
            try {
                Files.createDirectories(childLogs);
            } catch (IOException exception) {
                throw new BenchError("cannot create " + childLogs + ": " + exception.getMessage(), exception);
            }
            // Children inherit these options from the gateway. Each writes its own GC log, named by pid, runs native
            // memory tracking with a flight recording, and captures both into childLogs as it exits.
            environment.put("BENCH_CHILD_JVM_OPTIONS", Json.write(Map.of("options", List.of(
                    "-Xlog:gc*:file=" + childLogs.resolve("gc-%p.log") + ":time,uptime,level,tags",
                    "-XX:NativeMemoryTracking=summary",
                    "-XX:StartFlightRecording=name=bench,settings=profile,disk=true,maxsize=512m",
                    "-Dbench.child.telemetry=" + childLogs))));
        }
        Path gcLog = directory.resolve("gc.log");
        Path recording = directory.resolve("recording.jfr");
        List<String> jvmArguments = jvmArguments(spec, runtime, collector, gcLog, recording);
        int port = freePort();

        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.addAll(jvmArguments);
        command.add("-cp");
        command.add(spec.classpath());
        command.add(workload.mainClass());
        command.add(Integer.toString(port));

        Telemetry.ProcessSampling sampler = null;
        Map<String, Object> treeSummary = null;
        List<Map<String, Object>> reportedChildren = List.of();
        String status = "failed";
        String failure = null;
        Map<String, Object> loadResult = null;
        Map<String, Object> warmupCheck = null;
        Map<String, Object> latencyCheck = null;
        Map<String, Object> applicationIdentity = null;
        Map<String, Object> processSummary = Map.of();
        Map<String, Object> jcmdResults = Map.of();
        Map<String, Object> jstatResults = Map.of();
        Map<String, Object> environmentFingerprint = HostEnvironment.fingerprint(repo);
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(workload.appDirectory().toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(directory.resolve("app.log").toFile());
            builder.environment().putAll(environment);
            process = builder.start();
            applicationIdentity = waitForIdentity(port, process, token, workload.identityPath());
            sampler = tree
                    ? new Telemetry.TreeSampler(process.pid(), directory.resolve("process-tree-samples.jsonl"),
                            Duration.ofMillis(100))
                    : new Telemetry.Sampler(process.pid(), directory.resolve("process-samples.jsonl"),
                            Duration.ofMillis(100));
            sampler.start();
            loadResult = generateLoad(spec, port, directory, process.pid(), token);
            if (tree) {
                reportedChildren = childProcessesOf(port);
            }
            // A startup run has no steady state to warm up to: each start is a cold start. Its gates are per mode instead.
            if (!"startup".equals(spec.workload().loadShape())) {
                warmupCheck = Stats.warmupStability(doubles(at(loadResult, "series", "completed")),
                        spec.options().warmupSeconds());
                latencyCheck = Stats.warmupStability(doubles(at(loadResult, "series", "p50_ms")),
                        spec.options().warmupSeconds(), Stats.STABLE_WINDOW, LATENCY_PROVISIONAL_MAX_CV);
            }
            // The recording is dumped while the JVM is alive, which works on every operating system, then
            // the process is stopped.
            Telemetry.runTool(List.of(jcmd.toString(), Long.toString(process.pid()), "JFR.dump", "name=bench",
                    "filename=" + recording), directory.resolve("telemetry/jfr-dump.txt"));
            jcmdResults = collectJcmd(jcmd, process.pid(), directory.resolve("telemetry"));
            jstatResults = collectJstat(jstat, process.pid(), directory.resolve("telemetry"));
            status = "passed";
        } catch (IOException | RuntimeException exception) {
            failure = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        } finally {
            if (sampler != null) {
                processSummary = sampler.stop();
                if (sampler instanceof Telemetry.TreeSampler treeSampler) {
                    treeSummary = treeSampler.treeSummary();
                }
            }
            if (process != null) {
                terminate(process);
            }
            if (treeSummary != null) {
                awaitExit(pidsOf(treeSummary.get("observed_child_pids")));
            }
        }

        Path jfrJson = directory.resolve("jfr-events.json");
        Map<String, Object> jfrConversion = convertRecording(jfrTool, recording, jfrJson);
        // Each child wrote its own capture as it exited, so the children are read once the tree has stopped.
        Map<String, Object> childTelemetry = new LinkedHashMap<>();
        if (treeSummary != null) {
            for (long pid : pidsOf(treeSummary.get("observed_child_pids"))) {
                String id = Long.toString(pid);
                Path childJson = childLogs.resolve("jfr-" + id + ".json");
                childTelemetry.put(id, Json.map(
                        "nmt", Telemetry.parseNmt(read(childLogs.resolve("nmt-" + id + ".txt"))),
                        "capture_error", blankToNull(read(childLogs.resolve("error-" + id + ".txt"))),
                        "jfr_conversion", convertRecording(jfrTool, childLogs.resolve("jfr-" + id + ".jfr"), childJson),
                        "jfr", Telemetry.parseJfr(childJson)));
            }
        }
        String gcText = read(gcLog);
        Map<String, Object> childGc = new LinkedHashMap<>();
        List<Long> gcLogPids = new ArrayList<>();
        StringBuilder pooledGc = new StringBuilder(gcText);
        if (tree) {
            for (Map.Entry<Long, String> child : childGcLogs(childLogs).entrySet()) {
                childGc.put(Long.toString(child.getKey()), Telemetry.parseGcPauses(child.getValue()));
                gcLogPids.add(child.getKey());
                pooledGc.append('\n').append(child.getValue());
            }
        }
        String nmtText = read(directory.resolve("telemetry/jcmd-nmt.txt"));
        String codeCacheText = read(directory.resolve("telemetry/jcmd-code_cache.txt"));
        String compilerText = read(directory.resolve("telemetry/jstat-compiler.txt"));
        Object actual = actualCollector(gcText);

        Map<String, Object> telemetry = new LinkedHashMap<>();
        telemetry.put("process", processSummary);
        telemetry.put("gc", Telemetry.parseGcPauses(gcText));
        telemetry.put("requested_collector", collector);
        telemetry.put("actual_collector", actual);
        telemetry.put("nmt", Telemetry.parseNmt(nmtText));
        telemetry.put("code_cache", Telemetry.parseCodeCache(codeCacheText));
        telemetry.put("compiler", Telemetry.parseJstatCompiler(compilerText));
        telemetry.put("jfr_conversion", jfrConversion);
        telemetry.put("jfr", Telemetry.parseJfr(jfrJson));
        telemetry.put("raw_jcmd", jcmdResults);
        telemetry.put("raw_jstat", jstatResults);
        if (treeSummary != null) {
            // Every JVM in the tree, pooled: the gateway's pauses and each child's own pauses. Per-child GC is kept too.
            Map<String, Object> processTree = new LinkedHashMap<>(treeSummary);
            processTree.put("gc", Telemetry.parseGcPauses(pooledGc.toString()));
            processTree.put("child_gc", childGc);
            processTree.put("gc_log_pids", gcLogPids);
            processTree.put("child_telemetry", childTelemetry);
            telemetry.put("process_tree", processTree);
        }
        writeJson(directory.resolve("telemetry.json"), telemetry);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema_version", "1.0.0");
        result.put("repetition", spec.repetition());
        result.put("status", status);
        result.put("failure", failure);
        result.put("runtime", runtime.get("id"));
        result.put("java_identity_sha256", runtime.get("identity"));
        result.put("requested_collector", collector);
        result.put("actual_collector", actual);
        result.put("jvm_arguments", jvmArguments);
        result.put("application_identity", applicationIdentity);
        result.put("environment", environmentFingerprint);
        result.put("load", loadResult);
        result.put("warmup_stability", warmupCheck);
        result.put("load_shape", spec.workload().loadShape());
        result.put("latency_stability", latencyCheck);
        result.put("child_processes_reported", reportedChildren);
        result.put("telemetry", telemetry);
        writeJson(directory.resolve("result.json"), result);
        writeJson(directory.resolve("checksums.json"), Digest.tree(directory));
        return result;
    }

    private Map<String, Object> generateLoad(RepetitionSpec spec, int port, Path directory, long pid, String token) {
        Map<String, Object> result = switch (spec.workload().loadShape()) {
            case "jobs" -> jobLoad(spec, port, directory);
            case "startup" -> startupLoad(spec, port, directory);
            case "framed-tcp" -> chatLoad(spec, port, pid, token, directory);
            default -> httpLoad(spec, port, directory);
        };
        Map<String, Object> errors = Json.asMap(result.get("errors"));
        if (!isZero(errors.get("measurement"))) {
            throw new BenchError("measurement phase contains request errors");
        }
        Map<String, Object> completed = Json.asMap(result.get("completed"));
        Map<String, Object> scheduled = Json.asMap(result.get("scheduled"));
        if (!Objects.equals(completed.get("measurement"), scheduled.get("measurement"))) {
            throw new BenchError("measurement phase did not complete every scheduled request");
        }
        return result;
    }

    /** The http-mix shape: the load generator drives the workload's request mix on the open-loop schedule. */
    private Map<String, Object> httpLoad(RepetitionSpec spec, int port, Path directory) {
        Options options = spec.options();
        Workload workload = spec.workload();
        Path loadOutput = directory.resolve("loadgen.json");
        Path histogram = directory.resolve("latency.hgrm");
        List<String> command = new ArrayList<>();
        command.add(Repo.javaExecutable(Path.of(spec.loadgenEnvironment().get("JAVA_HOME")), "java").toString());
        command.addAll(List.of("-cp", spec.loadgenClasspath(), LOADGEN_MAIN, "run",
                "--base-url", "http://127.0.0.1:" + port,
                "--target-rate", Double.toString(options.targetRate()),
                "--warmup-seconds", Integer.toString(options.warmupSeconds()),
                "--measure-seconds", Integer.toString(options.measureSeconds()),
                "--threads", Integer.toString(options.threads()),
                "--seed", Long.toString(options.seed() + spec.repetition()),
                "--mix", workload.loadMix().toString(),
                "--benchmark", workload.id(),
                "--run-kind", "benchmark",
                "--out", loadOutput.toString(),
                "--histogram-out", histogram.toString()));
        long timeout = options.warmupSeconds() + options.measureSeconds() + 120L;
        Shell.Result load = Shell.run(command, repo.root(), spec.loadgenEnvironment(), Duration.ofSeconds(timeout));
        writeText(directory.resolve("loadgen.log"), load.stdout() + "\n" + load.stderr());
        if (!load.succeeded() || !Files.isRegularFile(loadOutput)) {
            throw new BenchError("load generator failed (" + load.exitCode() + "): "
                    + tail(load.stdout() + load.stderr()));
        }
        return Json.asMap(repo.readJson(loadOutput));
    }

    /** Seconds a single job may take from its submission to completion before it is recorded as a timeout. */
    static final int JOB_TIMEOUT_SECONDS = 120;

    /**
     * The jobs shape. Jobs are submitted on the load generator's open-loop schedule, and each is timed from its intended
     * submission to its completion. The result has the load generator's shape, so the same gates apply to it.
     */
    private Map<String, Object> jobLoad(RepetitionSpec spec, int port, Path directory) {
        Options options = spec.options();
        Workload workload = spec.workload();
        String base = "http://127.0.0.1:" + port;
        long interval = (long) (1e9 / options.targetRate());
        int count = (int) Math.round((options.warmupSeconds() + options.measureSeconds()) * options.targetRate());
        long start = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, options.threads()));
        try {
            List<Future<Map<String, Object>>> futures = new ArrayList<>();
            for (int job = 0; job < count; job++) {
                long intended = start + job * interval;
                futures.add(pool.submit(() -> {
                    Map<String, Object> record = JobDriver.scheduledJob(base, workload.jobs(), intended,
                            JOB_TIMEOUT_SECONDS);
                    record.put("intended_s", (intended - start) / 1e9);
                    return record;
                }));
            }
            List<Map<String, Object>> records = new ArrayList<>();
            for (Future<Map<String, Object>> future : futures) {
                records.add(future.get());
            }
            writeJson(directory.resolve("jobs.json"), records);
            Map<String, Object> result = jobSummary(workload.id(), records, options.targetRate(),
                    options.warmupSeconds(), options.measureSeconds());
            writeJson(directory.resolve("loadgen.json"), result);
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new BenchError("job load was interrupted");
        } catch (ExecutionException failure) {
            throw new BenchError("job load failed: " + failure.getMessage(), failure);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Summarises job records in the load generator's shape. Each record carries its {@code intended_s} offset from the
     * start of the run. Only measurement-phase completions feed the throughput and latency figures. A job that is not
     * completed, or whose output digest does not match, is an error in its phase.
     */
    static Map<String, Object> jobSummary(String benchmark, List<Map<String, Object>> records, double rate,
                                          int warmupSeconds, int measureSeconds) {
        int buckets = warmupSeconds + measureSeconds;
        long scheduledWarmup = 0;
        long scheduledMeasure = 0;
        long completedWarmup = 0;
        long completedMeasure = 0;
        long errorsWarmup = 0;
        long errorsMeasure = 0;
        long[] scheduled = new long[buckets];
        long[] completed = new long[buckets];
        List<List<Double>> latencyByBucket = new ArrayList<>();
        for (int bucket = 0; bucket < buckets; bucket++) {
            latencyByBucket.add(new ArrayList<>());
        }
        List<Double> measureLatencies = new ArrayList<>();
        for (Map<String, Object> record : records) {
            double intended = ((Number) record.get("intended_s")).doubleValue();
            boolean warmup = intended < warmupSeconds;
            int startBucket = Math.min(buckets - 1, (int) Math.floor(intended));
            scheduled[startBucket]++;
            if (warmup) {
                scheduledWarmup++;
            } else {
                scheduledMeasure++;
            }
            boolean succeeded = "completed".equals(record.get("status"))
                    && Boolean.TRUE.equals(record.get("digest_match"));
            if (!succeeded) {
                if (warmup) {
                    errorsWarmup++;
                } else {
                    errorsMeasure++;
                }
                continue;
            }
            double sojourn = ((Number) record.get("sojourn_ms")).doubleValue();
            int finishBucket = Math.min(buckets - 1, (int) Math.floor(intended + sojourn / 1000.0));
            completed[finishBucket]++;
            latencyByBucket.get(startBucket).add(sojourn);
            if (warmup) {
                completedWarmup++;
            } else {
                completedMeasure++;
                measureLatencies.add(sojourn);
            }
        }
        List<Object> p50Series = new ArrayList<>();
        List<Object> scheduledSeries = new ArrayList<>();
        List<Object> completedSeries = new ArrayList<>();
        for (int bucket = 0; bucket < buckets; bucket++) {
            scheduledSeries.add(scheduled[bucket]);
            completedSeries.add(completed[bucket]);
            p50Series.add(Telemetry.percentile(latencyByBucket.get(bucket), 50));
        }
        Map<String, Object> kpis = new LinkedHashMap<>();
        kpis.put("throughput", completedMeasure / (double) measureSeconds);
        kpis.put("p50_ms", Telemetry.percentile(measureLatencies, 50));
        kpis.put("p95_ms", Telemetry.percentile(measureLatencies, 95));
        kpis.put("p99_ms", Telemetry.percentile(measureLatencies, 99));
        kpis.put("p999_ms", Telemetry.percentile(measureLatencies, 99.9));
        kpis.put("p9999_ms", Telemetry.percentile(measureLatencies, 99.99));
        kpis.put("max_ms", measureLatencies.isEmpty() ? null : Collections.max(measureLatencies));
        kpis.put("mean_ms", Stats.mean(measureLatencies));
        Map<String, Object> series = new LinkedHashMap<>();
        series.put("bucket_seconds", 1);
        series.put("scheduled", scheduledSeries);
        series.put("scheduled_bucketed_by", "intended_send_time");
        series.put("completed", completedSeries);
        series.put("completed_bucketed_by", "completion_time");
        series.put("p50_ms", p50Series);
        series.put("p50_bucketed_by", "intended_send_time");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema_version", "1.0.0");
        result.put("benchmark", benchmark);
        result.put("load_model", "open-loop");
        result.put("coordinated_omission_corrected", true);
        result.put("target_rate", rate);
        result.put("phases", Json.map("warmup_s", warmupSeconds, "measure_s", measureSeconds));
        result.put("scheduled", Json.map("warmup", scheduledWarmup, "measurement", scheduledMeasure));
        result.put("completed", Json.map("warmup", completedWarmup, "measurement", completedMeasure));
        result.put("errors", Json.map("warmup", errorsWarmup, "measurement", errorsMeasure));
        result.put("kpis", kpis);
        result.put("series", series);
        result.put("mix", List.of());
        return result;
    }

    /**
     * The startup shape. The application starts a child JVM under each mode and reports how long each took to become
     * healthy. Every start is a cold start, so there is no warm-up; each mode's times are summarised on their own.
     */
    private Map<String, Object> startupLoad(RepetitionSpec spec, int port, Path directory) {
        Map<String, Object> contract = Json.asMap(spec.workload().spec().get("startup"));
        String url = "http://127.0.0.1:" + port + string(contract, "submit_path");
        String field = string(contract, "response_field");
        List<String> modes = strings(contract.get("modes"));
        int starts = ((Number) contract.get("starts_per_mode")).intValue();
        Map<String, Object> byMode = new LinkedHashMap<>();
        long scheduled = 0;
        long completed = 0;
        long errors = 0;
        long began = System.nanoTime();
        for (String mode : modes) {
            List<Double> samples = new ArrayList<>();
            long modeErrors = 0;
            for (int start = 0; start < starts; start++) {
                scheduled++;
                Double milliseconds = startOnce(url, mode, field);
                if (milliseconds == null) {
                    modeErrors++;
                    errors++;
                } else {
                    samples.add(milliseconds);
                    completed++;
                }
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("samples_ms", samples);
            summary.put("errors", modeErrors);
            summary.put("median_ms", Stats.median(samples));
            summary.put("cv", Stats.coefficientOfVariation(samples));
            byMode.put(mode, summary);
        }
        double elapsed = (System.nanoTime() - began) / 1e9;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema_version", "1.0.0");
        result.put("benchmark", spec.workload().id());
        result.put("load_model", "startup");
        result.put("phases", Json.map("warmup_s", 0, "measure_s", elapsed));
        result.put("scheduled", Json.map("warmup", 0, "measurement", scheduled));
        result.put("completed", Json.map("warmup", 0, "measurement", completed));
        result.put("errors", Json.map("warmup", 0, "measurement", errors));
        result.put("kpis", Json.map());
        result.put("startup", Json.map("field", field, "modes", byMode));
        result.put("series", Json.map("bucket_seconds", 1, "completed", List.of(), "p50_ms", List.of()));
        result.put("mix", List.of());
        writeJson(directory.resolve("loadgen.json"), result);
        return result;
    }

    /** One cold start: the application's time to health for the mode, or null when the start failed. */
    private static Double startOnce(String url, String mode, String field) {
        try {
            Map.Entry<Integer, String> response = postJson(url, Json.write(Map.of("mode", mode)), 120);
            if (response.getKey() != 200) {
                return null;
            }
            return Json.asMap(Json.parse(response.getValue())).get(field) instanceof Number milliseconds
                    ? milliseconds.doubleValue() : null;
        } catch (IOException failure) {
            return null;
        }
    }

    /** POSTs a JSON body. Returns the HTTP status and the response text; a transport failure throws IOException. */
    static Map.Entry<Integer, String> postJson(String url, String body, int timeoutSeconds) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setConnectTimeout(500);
        connection.setReadTimeout(timeoutSeconds * 1000);
        try {
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String text = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return Map.entry(status, text);
        } finally {
            connection.disconnect();
        }
    }

    /** The chat driver a framed-tcp workload declares under {@code "driver"} in tier2.json. */
    private static Map<String, Object> driverSpec(Workload workload) {
        Map<String, Object> driver = Json.asMap(workload.spec().get("driver"));
        if (driver.isEmpty()) {
            throw new BenchError(workload.id() + " is framed-tcp and declares no driver in tier2.json");
        }
        return driver;
    }

    /** Builds the driver a framed-tcp workload runs against the application, in the driver's own project. */
    private void buildDriver(Workload workload, Map<String, String> environment, Path log) {
        Map<String, Object> driver = driverSpec(workload);
        Path directory = workload.directory().resolve(string(driver, "directory"));
        List<String> contract = strings(driver.get("build"));
        List<String> command = new ArrayList<>(Repo.mavenCommand(directory));
        command.addAll(contract.subList(1, contract.size()));
        Shell.Result result = Shell.run(command, directory, environment, Duration.ofMinutes(15));
        writeText(log, result.stdout() + "\n" + result.stderr());
        if (!result.succeeded()) {
            throw new BenchError(workload.id() + " driver build failed (" + result.exitCode() + "): "
                    + tail(result.combined()));
        }
    }

    /** The driver's classpath: its compiled classes, then the dependency entries its build wrote. */
    private static String driverClasspath(Workload workload) {
        Map<String, Object> driver = driverSpec(workload);
        Path directory = workload.directory().resolve(string(driver, "directory"));
        List<String> entries = new ArrayList<>();
        for (String prefix : strings(driver.get("classpath_prefix"))) {
            entries.add(directory.resolve(prefix).toString());
        }
        entries.add(readRequired(directory.resolve(string(driver, "classpath_file"))));
        return String.join(java.io.File.pathSeparator, entries);
    }

    /**
     * The framed-tcp shape. The application the engine launched is the server under test; the chat driver connects to it
     * over its persistent TCP sessions and publishes on the schedule. The driver runs in its own JVM and does not sample
     * the application, whose telemetry the engine owns. Its envelope is mapped to the load generator's shape, so the same
     * gates apply.
     */
    private Map<String, Object> chatLoad(RepetitionSpec spec, int port, long pid, String token, Path directory) {
        Options options = spec.options();
        Workload workload = spec.workload();
        Map<String, Object> chat = Json.asMap(workload.spec().get("chat"));
        Path envelope = directory.resolve("chat-envelope.json");
        Path java = Repo.javaExecutable(Path.of(spec.loadgenEnvironment().get("JAVA_HOME")), "java");
        List<String> command = List.of(java.toString(), "-cp", driverClasspath(workload),
                string(driverSpec(workload), "main_class"),
                "--attach-base-url", "http://127.0.0.1:" + port,
                "--run-token", token,
                "--app-pid", Long.toString(pid),
                "--subscribers", Integer.toString(((Number) chat.get("subscribers")).intValue()),
                "--rooms", Integer.toString(((Number) chat.get("rooms")).intValue()),
                "--publishers", Integer.toString(((Number) chat.get("publishers")).intValue()),
                "--rate", Double.toString(options.targetRate()),
                "--warmup-seconds", Integer.toString(options.warmupSeconds()),
                "--measure-seconds", Integer.toString(options.measureSeconds()),
                "--out", envelope.toString());
        long timeout = options.warmupSeconds() + options.measureSeconds() + 120L;
        Shell.Result run = Shell.run(command, repo.root(), spec.loadgenEnvironment(), Duration.ofSeconds(timeout));
        writeText(directory.resolve("chat-driver.log"), run.stdout() + "\n" + run.stderr());
        if (!run.succeeded() || !Files.isRegularFile(envelope)) {
            throw new BenchError("chat driver failed (" + run.exitCode() + "): " + tail(run.stdout() + run.stderr()));
        }
        Map<String, Object> results = Json.asMap(Json.asMap(repo.readJson(envelope)).get("results"));
        return chatSummary(workload.id(), results, options);
    }

    /**
     * Maps the chat driver's results to the load generator's shape. The scheduled, completed and error counts are per
     * message in the measurement window: a message that was not sent or was not acknowledged is missing from completed,
     * and an error frame or a conservation failure is an error. Throughput is deliveries per measured second.
     */
    static Map<String, Object> chatSummary(String benchmark, Map<String, Object> results, Options options) {
        long scheduledWindow = Math.round(Json.number(results.get("messages_scheduled_in_window")));
        long scheduledWarmup = Math.round(options.targetRate() * options.warmupSeconds());
        long sentWindow = Math.round(Json.number(results.get("messages_sent_in_window")));
        long delivered = Math.round(Json.number(results.get("deliveries_in_window")));
        long errors = Math.round(Json.number(results.get("error_frames")))
                + (Boolean.TRUE.equals(results.get("conserved")) ? 0 : 1);
        Map<String, Object> latency = Json.asMap(results.get("delivery_latency_ms"));
        Map<String, Object> kpis = new LinkedHashMap<>();
        kpis.put("throughput", delivered / (double) options.measureSeconds());
        kpis.put("p50_ms", latency.get("p50"));
        kpis.put("p99_ms", latency.get("p99"));
        kpis.put("p999_ms", latency.get("p999"));
        kpis.put("max_ms", latency.get("max"));
        kpis.put("mean_ms", latency.get("mean"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema_version", "1.0.0");
        result.put("benchmark", benchmark);
        result.put("load_model", "framed-tcp-chat");
        result.put("coordinated_omission_corrected", true);
        result.put("target_rate", options.targetRate());
        result.put("phases", Json.map("warmup_s", options.warmupSeconds(), "measure_s", options.measureSeconds()));
        result.put("scheduled", Json.map("warmup", scheduledWarmup, "measurement", scheduledWindow));
        result.put("completed", Json.map("warmup", null, "measurement", sentWindow));
        result.put("errors", Json.map("warmup", null, "measurement", errors));
        result.put("kpis", kpis);
        result.put("series", results.get("series"));
        result.put("mix", List.of());
        return result;
    }

    private String buildLoadgen(Map<String, String> environment, Path log) {
        Path project = repo.root().resolve("tools/loadgen");
        List<String> command = new ArrayList<>(Repo.mavenCommand(repo.root()));
        command.addAll(List.of("-B", "-q", "-f", project.resolve("pom.xml").toString(), "package",
                "org.apache.maven.plugins:maven-dependency-plugin:" + DEPENDENCY_PLUGIN + ":build-classpath",
                "-Dmdep.outputFile=" + project.resolve("target/classpath.txt")));
        Shell.Result result = Shell.run(command, repo.root(), environment, Duration.ofMinutes(15));
        writeText(log, result.stdout() + "\n" + result.stderr());
        if (!result.succeeded()) {
            throw new BenchError("load generator build failed (" + result.exitCode() + "): " + tail(result.combined()));
        }
        Path classes = project.resolve("target/classes");
        String dependencies = readRequired(project.resolve("target/classpath.txt"));
        return classes + java.io.File.pathSeparator + dependencies;
    }

    private void correctnessGate(Workload workload, Map<String, String> environment, Path log) {
        List<String> command = resolveCommand(workload, workload.correctness());
        Shell.Result result = Shell.run(command, workload.appDirectory(), environment, Duration.ofMinutes(15));
        writeText(log, result.stdout() + "\n" + result.stderr());
        if (!result.succeeded()) {
            throw new BenchError(workload.id() + " correctness gate failed (" + result.exitCode() + "): "
                    + tail(result.combined()));
        }
    }

    private String buildClasspath(Workload workload, Map<String, String> environment, Path log) {
        List<String> command = resolveCommand(workload, workload.build());
        Shell.Result result = Shell.run(command, workload.appDirectory(), environment, Duration.ofMinutes(15));
        writeText(log, result.stdout() + "\n" + result.stderr());
        if (!result.succeeded()) {
            throw new BenchError(workload.id() + " build failed (" + result.exitCode() + "): " + tail(result.combined()));
        }
        Path entriesFile = workload.appDirectory().resolve(workload.classpathFile());
        if (!Files.isRegularFile(entriesFile)) {
            throw new BenchError(workload.id() + " build did not write " + workload.classpathFile());
        }
        List<String> entries = new ArrayList<>();
        for (String prefix : workload.classpathPrefix()) {
            entries.add(workload.appDirectory().resolve(prefix).toString());
        }
        String declared = readRequired(entriesFile);
        for (String entry : declared.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (!entry.isBlank()) {
                entries.add(entry);
            }
        }
        for (String entry : entries) {
            if (!Files.exists(Path.of(entry))) {
                throw new BenchError(workload.id() + " classpath entry is missing: " + entry);
            }
        }
        if (entries.isEmpty()) {
            throw new BenchError(workload.id() + " build produced an empty classpath");
        }
        return String.join(java.io.File.pathSeparator, entries);
    }

    /**
     * Expands the {@code maven} token to the platform's Maven wrapper in the workload's directory, so one contract
     * runs on Windows (mvnw.cmd) and on POSIX systems (mvnw).
     */
    List<String> resolveCommand(Workload workload, List<String> contract) {
        if (contract.isEmpty()) {
            throw new BenchError(workload.id() + " declares an empty command");
        }
        List<String> command = new ArrayList<>();
        if (contract.get(0).equals("maven")) {
            command.addAll(Repo.mavenCommand(workload.appDirectory()));
            command.addAll(contract.subList(1, contract.size()));
        } else {
            command.addAll(contract);
        }
        return command;
    }

    private Map<String, Object> validateRuntime(Path javaHome, String collector, Workload workload) {
        Path java = Repo.javaExecutable(javaHome, "java");
        if (!Files.isRegularFile(java)) {
            throw new BenchError("Java executable not found: " + java);
        }
        Map<String, Object> runtime = Runtimes.probe(java);
        long feature = ((Number) runtime.get("feature_version")).longValue();
        if (feature < workload.minJdk() || feature > workload.maxJdk()) {
            throw new BenchError(workload.id() + " supports JDK " + workload.minJdk() + "-" + workload.maxJdk()
                    + ", got JDK " + feature);
        }
        if (!Runtimes.isOpenJdkHotSpot(runtime)) {
            throw new BenchError("not an OpenJDK HotSpot runtime: " + runtime.get("vm_name"));
        }
        Map<String, Object> selected = Json.asMap(Json.asMap(runtime.get("collectors")).get(collector));
        if (!Boolean.TRUE.equals(selected.get("supported"))) {
            throw new BenchError("collector " + collector + " is unavailable: "
                    + selected.getOrDefault("reason", "not probed"));
        }
        Map<String, Object> tools = Json.asMap(runtime.get("tools"));
        for (String tool : List.of("javac", "jar", "jcmd", "jstat", "jfr")) {
            if (tools.get(tool) == null) {
                throw new BenchError("selected JDK lacks required tool: " + tool);
            }
        }
        Map<String, Object> capabilities = Json.asMap(runtime.get("capabilities"));
        if (!Boolean.TRUE.equals(capabilities.get("jfr"))) {
            throw new BenchError("selected runtime does not support JFR");
        }
        if (!Boolean.TRUE.equals(capabilities.get("nmt"))) {
            throw new BenchError("selected runtime does not support Native Memory Tracking");
        }
        if (!Boolean.TRUE.equals(capabilities.get("unified_logging"))
                && !Boolean.TRUE.equals(capabilities.get("legacy_gc_logging"))) {
            throw new BenchError("selected runtime supports neither unified nor legacy GC logging");
        }
        return runtime;
    }

    private List<String> jvmArguments(RepetitionSpec spec, Map<String, Object> runtime, String collector,
                                      Path gcLog, Path recording) {
        Map<String, Object> collectorInfo = Json.asMap(Json.asMap(runtime.get("collectors")).get(collector));
        List<String> arguments = new ArrayList<>(List.of(
                "-Xms" + spec.options().heapMb() + "m",
                "-Xmx" + spec.options().heapMb() + "m",
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+DebugNonSafepoints",
                "-XX:NativeMemoryTracking=summary"));
        arguments.addAll(strings(collectorInfo.get("flags")));
        arguments.addAll(spec.workload().jvmOptions());
        arguments.add("-XX:StartFlightRecording=name=bench,filename=" + recording
                + ",settings=profile,disk=true,maxsize=512m");
        // JDK 9+ unified logging; JDK 8 has only legacy GC logging, so safepoint and JIT logs are then absent.
        if (Boolean.TRUE.equals(Json.asMap(runtime.get("capabilities")).get("unified_logging"))) {
            arguments.add("-Xlog:gc*,safepoint,jit+compilation=info:file=" + gcLog + ":time,uptime,level,tags");
        } else {
            arguments.addAll(List.of("-verbose:gc", "-XX:+PrintGCDetails", "-XX:+PrintGCDateStamps",
                    "-Xloggc:" + gcLog));
        }
        arguments.addAll(spec.options().extraJvmArguments());
        return arguments;
    }

    private Map<String, Object> collectJcmd(Path jcmd, long pid, Path telemetry) {
        Map<String, Object> results = new LinkedHashMap<>();
        results.put("nmt", Telemetry.runTool(List.of(jcmd.toString(), Long.toString(pid), "VM.native_memory", "summary",
                "scale=KB"), telemetry.resolve("jcmd-nmt.txt")));
        results.put("code_cache", Telemetry.runTool(List.of(jcmd.toString(), Long.toString(pid), "Compiler.codecache"),
                telemetry.resolve("jcmd-code_cache.txt")));
        results.put("compiler_queue", Telemetry.runTool(List.of(jcmd.toString(), Long.toString(pid), "Compiler.queue"),
                telemetry.resolve("jcmd-compiler_queue.txt")));
        results.put("heap", Telemetry.runTool(List.of(jcmd.toString(), Long.toString(pid), "GC.heap_info"),
                telemetry.resolve("jcmd-heap.txt")));
        results.put("flags", Telemetry.runTool(List.of(jcmd.toString(), Long.toString(pid), "VM.flags", "-all"),
                telemetry.resolve("jcmd-flags.txt")));
        return results;
    }

    private Map<String, Object> collectJstat(Path jstat, long pid, Path telemetry) {
        return Json.map(
                "compiler", Telemetry.runTool(List.of(jstat.toString(), "-compiler", Long.toString(pid)),
                        telemetry.resolve("jstat-compiler.txt")),
                "gc", Telemetry.runTool(List.of(jstat.toString(), "-gc", Long.toString(pid)),
                        telemetry.resolve("jstat-gc.txt")));
    }

    private Map<String, Object> convertRecording(Path jfrTool, Path recording, Path output) {
        if (!Files.isRegularFile(recording) || sizeOf(recording) == 0) {
            writeText(output, "");
            return Json.map("returncode", 1, "error", "recording missing");
        }
        Shell.Result result = Shell.run(List.of(jfrTool.toString(), "print", "--json", "--events",
                "jdk.ObjectAllocationSample,jdk.Compilation,jdk.GarbageCollection", recording.toString()),
                null, null, Duration.ofMinutes(3));
        writeText(output, result.succeeded() ? result.stdout() : result.stdout() + "\n" + result.stderr());
        String error = result.succeeded() ? null : result.stderr().strip();
        return Json.map("returncode", result.exitCode(), "error", error == null || error.isEmpty() ? null : error);
    }

    /** True for a zero count whatever the numeric type it was read as: parsed JSON and in-memory values differ. */
    static boolean isZero(Object value) {
        return value instanceof Number number && number.longValue() == 0;
    }

    /**
     * Stops the measured gateway and, through it, its children. Only the gateway is signalled: its shutdown hooks close
     * each child through the child's stdin, and every child writes its own capture before it exits. Signalling the
     * children directly would end them first, before any capture.
     */
    static void terminate(Process process) {
        if (!process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private Map<String, Object> waitForIdentity(int port, Process process, String token, String identityPath) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        String lastError = "not attempted";
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new BenchError("application exited before readiness: " + process.exitValue());
            }
            try {
                Map<String, Object> identity = Json.asMap(Json.parse(get("http://127.0.0.1:" + port + identityPath)));
                // Outside the try: a wrong process must fail now, not be retried until the timeout.
                if (token.equals(identity.get("run_token")) && identity.get("pid") instanceof Number pid
                        && pid.longValue() == process.pid()) {
                    return identity;
                }
                throw new BenchError("application identity mismatch: expected pid=" + process.pid()
                        + ", token=" + token + "; got " + identity);
            } catch (IOException | IllegalArgumentException exception) {
                lastError = exception.getMessage();
                try {
                    Thread.sleep(100);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new BenchError("interrupted while waiting for the application");
                }
            }
        }
        throw new BenchError("application identity timeout: " + lastError);
    }

    static String get(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(500);
        connection.setReadTimeout(2000);
        try (InputStream in = connection.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    static int freePort() {
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new BenchError("cannot allocate a loopback port: " + exception.getMessage(), exception);
        }
    }

    static Map<String, String> javaEnvironment(Path javaHome) {
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.put("JAVA_HOME", javaHome.toString());
        String path = System.getenv("PATH");
        environment.put("PATH", javaHome.resolve("bin") + java.io.File.pathSeparator + (path == null ? "" : path));
        return environment;
    }

    /** The first discovered runtime at JDK 21 or newer; the load generator needs it. */
    Path defaultLoadgenJavaHome() {
        for (Map<String, Object> runtime : Runtimes.load(repo)) {
            if (((Number) runtime.get("feature_version")).longValue() >= 21) {
                return Path.of((String) runtime.get("java_home"));
            }
        }
        throw new BenchError("the load generator needs a JDK 21 or newer; pass --loadgen-java-home");
    }

    /** Actual collector from a GC log: unified "Using" lines first, then the legacy pause labels. */
    static String actualCollector(String gcLog) {
        String[][] unified = {
            {"Using Serial", "serial"}, {"Using Parallel", "parallel"}, {"Using G1", "g1"}, {"Using Z", "zgc"},
            {"Using Shenandoah", "shenandoah"}, {"Using Epsilon", "epsilon"}, {"Concurrent Mark Sweep", "cms"}};
        String lower = gcLog.toLowerCase(java.util.Locale.ROOT);
        for (String[] pattern : unified) {
            if (lower.contains(pattern[0].toLowerCase(java.util.Locale.ROOT))) {
                return pattern[1];
            }
        }
        // JDK 8 legacy GC logs have no "Using <collector>" line. Each collector names its own young-generation
        // space in every pause, so those labels identify the collector that the launched JVM actually ran.
        String[][] legacy = {
            {"G1 Evacuation Pause", "g1"}, {"[DefNew", "serial"}, {"[PSYoungGen", "parallel"}, {"[ParNew", "cms"}};
        for (String[] label : legacy) {
            if (gcLog.contains(label[0])) {
                return label[1];
            }
        }
        return null;
    }

    /** Verdict on a set of repetitions: valid only if every gate passes; the reasons say which did not. */
    record Validity(boolean valid, List<String> reasons, List<String> warnings) {
    }

    static Validity validity(List<Map<String, Object>> repetitions, String runKind, Map<String, Object> preflight) {
        List<String> reasons = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        reasons.addAll(sharedReasons(runKind, repetitions, preflight));
        if (repetitions.stream().anyMatch(item -> !(item.get("load") instanceof Map))) {
            reasons.add("one or more repetitions lack load-generator output");
        }
        for (Map<String, Object> repetition : repetitions) {
            Map<String, Object> load = Json.asMap(repetition.get("load"));
            if (!isZero(Json.asMap(load.get("errors")).get("measurement"))) {
                reasons.add("measurement request errors were observed");
                break;
            }
            if (!Objects.equals(Json.asMap(load.get("completed")).get("measurement"),
                    Json.asMap(load.get("scheduled")).get("measurement"))) {
                reasons.add("scheduled measurement requests were not all completed");
                break;
            }
        }
        if (repetitions.stream().filter(item -> !"startup".equals(item.get("load_shape"))).anyMatch(item -> !Boolean.TRUE.equals(
                Json.asMap(item.get("warmup_stability")).get("stable_before_measurement")))) {
            reasons.add("throughput did not stabilise within warm-up in one or more repetitions");
        }
        for (Map<String, Object> repetition : repetitions) {
            if (!"startup".equals(repetition.get("load_shape"))) {
                continue;
            }
            for (Map.Entry<String, Object> mode : Json.asMap(at(repetition, "load", "startup", "modes")).entrySet()) {
                Object cv = at(mode.getValue(), "cv");
                if (cv instanceof Number value && value.doubleValue() > LANE_MAX_CV) {
                    reasons.add(String.format(java.util.Locale.ROOT,
                            "startup times for mode %s vary by more than 10%%: coefficient of variation %.4f",
                            mode.getKey(), value.doubleValue()));
                }
            }
        }
        if (repetitions.stream().anyMatch(item -> item.get("actual_collector") == null)) {
            reasons.add("actual collector could not be established from JVM logs");
        }
        if (repetitions.stream().anyMatch(item -> !"default".equals(item.get("requested_collector"))
                && !item.get("requested_collector").equals(item.get("actual_collector")))) {
            reasons.add("actual collector differs from requested collector");
        }
        if (repetitions.stream().anyMatch(item -> count(at(item, "telemetry", "process", "sample_count")) < 10)) {
            reasons.add("insufficient application-process samples");
        }
        if (repetitions.stream().anyMatch(item -> at(item, "telemetry", "nmt", "committed_bytes") == null)) {
            reasons.add("Native Memory Tracking output is missing");
        }
        if (repetitions.stream().anyMatch(item -> at(item, "telemetry", "compiler", "compiled") == null)) {
            reasons.add("compiler statistics are missing");
        }
        if (repetitions.stream().anyMatch(item -> !isZero(at(item, "telemetry", "jfr_conversion", "returncode")))) {
            reasons.add("JFR conversion failed");
        }
        List<Double> throughputs = repetitions.stream().filter(item -> item.get("load") instanceof Map)
                .map(item -> Json.number(at(item, "load", "kpis", "throughput"))).filter(value -> value != null).toList();
        List<Double> p99 = repetitions.stream().filter(item -> item.get("load") instanceof Map)
                .map(item -> Json.number(at(item, "load", "kpis", "p99_ms"))).filter(value -> value != null).toList();
        if (throughputs.size() >= 2) {
            Double cv = Stats.coefficientOfVariation(throughputs);
            if (cv != null && cv > .10) {
                reasons.add(String.format(java.util.Locale.ROOT, "throughput coefficient of variation exceeds 10%%: %.4f", cv));
            }
        }
        if (p99.size() >= 2) {
            Double mean = Stats.mean(p99);
            Double cv = Stats.coefficientOfVariation(p99);
            if (mean != null && mean > 0 && cv != null && cv > .20) {
                reasons.add(String.format(java.util.Locale.ROOT, "p99 coefficient of variation exceeds 20%%: %.4f", cv));
            }
        }
        for (Map<String, Object> repetition : repetitions) {
            // Process-tree repetitions only. The children the gateway reports must be the ones the sampler saw, and every
            // child the sampler saw needs its GC log, native memory and a converted flight recording. Each child writes
            // the last two as it exits, so a child the gateway retired mid-run is held to the same standard.
            Map<String, Object> tree = Json.asMap(at(repetition, "telemetry", "process_tree"));
            if (tree.isEmpty()) {
                continue;
            }
            List<Long> observed = pidsOf(tree.get("observed_child_pids"));
            List<Long> logged = pidsOf(tree.get("gc_log_pids"));
            List<Long> reported = pidsOf(repetition.get("child_processes_reported"));
            if (observed.isEmpty()) {
                reasons.add("no child JVM process was observed for a process-tree workload");
            }
            for (long pid : reported) {
                if (!observed.contains(pid)) {
                    reasons.add("gateway-reported child pid " + pid + " was not observed by the process sampler");
                }
            }
            for (long pid : observed) {
                String id = Long.toString(pid);
                if (!logged.contains(pid)) {
                    reasons.add("child JVM pid " + pid + " has no GC log");
                }
                if (at(tree, "child_telemetry", id, "nmt", "committed_bytes") == null) {
                    reasons.add("child JVM pid " + pid + " has no native-memory summary");
                }
                if (!isZero(at(tree, "child_telemetry", id, "jfr_conversion", "returncode"))) {
                    reasons.add("child JVM pid " + pid + " has no usable flight recording");
                }
            }
        }
        if (repetitions.stream().anyMatch(item -> at(item, "telemetry", "jfr", "allocation_sample_weight_bytes") == null)) {
            warnings.add("JFR allocation samples were unavailable; allocation-rate KPI remains null");
        }
        return new Validity(reasons.isEmpty(), List.copyOf(new java.util.LinkedHashSet<>(reasons)),
                List.copyOf(new java.util.LinkedHashSet<>(warnings)));
    }

    /** Largest coefficient of variation a lane's primary metric may show across its repetitions. Provisional. */
    static final double LANE_MAX_CV = 0.10;

    /**
     * The gates every measurement shares, whether the Tier 2 engine or a lane produced it: the run kind, the host
     * preflight, the repetition count and failures, and identical critical host state across repetitions.
     */
    static List<String> sharedReasons(String runKind, List<Map<String, Object>> repetitions, Map<String, Object> preflight) {
        List<String> reasons = new ArrayList<>();
        if (!"benchmark".equals(runKind)) {
            reasons.add("run_kind is not benchmark");
        }
        if (preflight == null) {
            reasons.add("host preflight was not recorded");
        } else if (!Boolean.TRUE.equals(preflight.get("passed"))) {
            for (Object failure : Json.asList(preflight.get("failures"))) {
                reasons.add("host failed preflight: " + failure);
            }
        }
        if (repetitions.size() < 5) {
            reasons.add("at least five repetitions are required");
        }
        if (repetitions.stream().anyMatch(item -> !"passed".equals(item.get("status")))) {
            reasons.add("one or more repetitions failed");
        }
        List<Map<String, Object>> environments = repetitions.stream()
                .map(item -> criticalEnvironment(Json.asMap(item.get("environment")))).toList();
        if (!environments.isEmpty() && environments.stream().anyMatch(item -> !item.equals(environments.get(0)))) {
            reasons.add("critical environment fields changed between repetitions");
        }
        if (repetitions.isEmpty()) {
            reasons.add("no repetitions were produced");
        }
        return reasons;
    }

    /** A reason when a lane's primary metric varies too much across repetitions to report as stable, else null. */
    static String spreadReason(String metric, List<Double> perRepetition) {
        // A failed repetition contributes no value. It is reported by the repetition gate, never zeroed here.
        List<Double> known = perRepetition.stream().filter(value -> value != null).toList();
        Double cv = known.size() < 2 ? null : Stats.coefficientOfVariation(known);
        if (cv == null || cv <= LANE_MAX_CV) {
            return null;
        }
        return String.format(java.util.Locale.ROOT,
                "%s coefficient of variation across repetitions exceeds 10%%: %.4f", metric, cv);
    }

    static Map<String, Object> criticalEnvironment(Map<String, Object> environment) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (String field : CRITICAL_ENVIRONMENT_FIELDS) {
            fields.put(field, environment.get(field));
        }
        return fields;
    }

    static Map<String, Object> aggregate(List<Map<String, Object>> repetitions, long seed) {
        Map<String, Object> aggregate = new LinkedHashMap<>();
        aggregate.put("throughput", metric(values(repetitions, List.of("load", "kpis", "throughput")), seed));
        aggregate.put("p50_ms", metric(values(repetitions, List.of("load", "kpis", "p50_ms")), seed + 1));
        aggregate.put("p99_ms", metric(values(repetitions, List.of("load", "kpis", "p99_ms")), seed + 2));
        aggregate.put("p999_ms", metric(values(repetitions, List.of("load", "kpis", "p999_ms")), seed + 3));
        aggregate.put("gc_pause_p99_ms", metric(values(repetitions, List.of("telemetry", "gc", "p99_ms")), seed + 4));
        aggregate.put("rss_mb_max", metric(scale(values(repetitions, List.of("telemetry", "process", "rss_bytes_max"))),
                seed + 5));
        aggregate.put("process_cpu_pct_one_core_mean", metric(
                values(repetitions, List.of("telemetry", "process", "process_cpu_pct_one_core_mean")), seed + 6));
        aggregate.put("native_memory_committed_mb", metric(
                scale(values(repetitions, List.of("telemetry", "nmt", "committed_bytes"))), seed + 7));
        aggregate.put("code_cache_used_mb", metric(
                scale(values(repetitions, List.of("telemetry", "code_cache", "used_bytes"))), seed + 8));
        aggregate.put("compiled_methods", metric(values(repetitions, List.of("telemetry", "compiler", "compiled")), seed + 9));
        aggregate.put("compiler_time_seconds", metric(
                values(repetitions, List.of("telemetry", "compiler", "time_seconds")), seed + 10));
        // Process-tree totals describe every JVM the application started. They are null for single-process workloads.
        aggregate.put("process_tree_rss_mb_max", metric(
                scale(values(repetitions, List.of("telemetry", "process_tree", "rss_bytes_max_total"))), seed + 12));
        aggregate.put("process_tree_cpu_pct_one_core_mean", metric(
                values(repetitions, List.of("telemetry", "process_tree", "cpu_one_core_mean_total")), seed + 13));
        aggregate.put("process_tree_gc_pause_p99_ms", metric(
                values(repetitions, List.of("telemetry", "process_tree", "gc", "p99_ms")), seed + 14));
        aggregate.put("process_tree_process_count_max", metric(
                values(repetitions, List.of("telemetry", "process_tree", "process_count_max")), seed + 15));
        List<Double> treeNativeMemory = new ArrayList<>();
        List<Double> treeAllocationRates = new ArrayList<>();
        for (Map<String, Object> repetition : repetitions) {
            Double committed = treeNativeMemoryBytes(repetition);
            if (committed != null) {
                treeNativeMemory.add(committed / 1024 / 1024);
            }
            Double rate = treeAllocationRate(repetition);
            if (rate != null) {
                treeAllocationRates.add(rate);
            }
        }
        aggregate.put("process_tree_native_memory_committed_mb", metric(treeNativeMemory, seed + 16));
        aggregate.put("process_tree_jfr_allocation_sample_rate_mb_s", metric(treeAllocationRates, seed + 17));
        // Startup workloads: the median time to health of each mode's cold starts, per repetition.
        aggregate.put("startup_default_median_ms", metric(
                values(repetitions, List.of("load", "startup", "modes", "default", "median_ms")), seed + 18));
        aggregate.put("startup_no_cds_median_ms", metric(
                values(repetitions, List.of("load", "startup", "modes", "no-cds", "median_ms")), seed + 19));
        aggregate.put("startup_appcds_median_ms", metric(
                values(repetitions, List.of("load", "startup", "modes", "appcds", "median_ms")), seed + 20));
        List<Double> allocationRates = new ArrayList<>();
        for (Map<String, Object> repetition : repetitions) {
            Object weight = at(repetition, "telemetry", "jfr", "allocation_sample_weight_bytes");
            Object duration = at(repetition, "load", "phases", "measure_s");
            if (weight instanceof Number w && duration instanceof Number d && d.doubleValue() > 0) {
                allocationRates.add(w.doubleValue() / d.doubleValue() / 1024 / 1024);
            }
        }
        aggregate.put("jfr_allocation_sample_rate_mb_s", metric(allocationRates, seed + 11));
        return aggregate;
    }

    static Map<String, Object> metric(List<Double> values, long seed) {
        if (values.isEmpty()) {
            return Json.map("count", 0, "mean", null, "median", null, "standard_deviation", null,
                    "median_absolute_deviation", null, "coefficient_of_variation", null,
                    "bootstrap_mean_ci", Json.map("low", null, "high", null, "confidence", 0.95), "values", List.of());
        }
        Double mean = Stats.mean(values);
        Double deviation = Stats.standardDeviation(values);
        return Json.map(
                "count", values.size(),
                "mean", mean,
                "median", Stats.median(values),
                "standard_deviation", deviation,
                "median_absolute_deviation", Stats.medianAbsoluteDeviation(values),
                "coefficient_of_variation", deviation != null && mean != null && mean != 0 ? deviation / mean : null,
                "bootstrap_mean_ci", Stats.bootstrapInterval(values, seed),
                "values", values);
    }

    static List<Double> values(List<Map<String, Object>> repetitions, List<String> path) {
        List<Double> values = new ArrayList<>();
        for (Map<String, Object> repetition : repetitions) {
            Object value = at(repetition, path.toArray(String[]::new));
            if (value instanceof Number number) {
                values.add(number.doubleValue());
            }
        }
        return values;
    }

    private static List<Double> scale(List<Double> bytes) {
        List<Double> scaled = new ArrayList<>();
        for (double value : bytes) {
            scaled.add(value / 1024 / 1024);
        }
        return scaled;
    }

    static List<Map<String, Object>> outlierReport(List<Map<String, Object>> repetitions) {
        List<Map<String, Object>> report = new ArrayList<>();
        for (Map.Entry<String, List<String>> metric : OUTLIER_METRICS) {
            List<Double> numbers = new ArrayList<>();
            List<Long> labels = new ArrayList<>();
            for (Map<String, Object> repetition : repetitions) {
                Object value = at(repetition, metric.getValue().toArray(String[]::new));
                if (value instanceof Number number) {
                    double scaled = metric.getKey().equals("rss_mb_max") ? number.doubleValue() / (1024 * 1024)
                            : number.doubleValue();
                    numbers.add(scaled);
                    labels.add(((Number) repetition.get("repetition")).longValue());
                }
            }
            report.add(Stats.describeOutliers(metric.getKey(), numbers, labels));
        }
        return report;
    }

    static Map<String, Object> combinePreflight(Map<String, Object> before, Map<String, Object> after) {
        List<Object> failures = new ArrayList<>(Json.asList(before.get("failures")));
        for (Object failure : Json.asList(after.get("failures"))) {
            failures.add("after run: " + failure);
        }
        return Json.map(
                "passed", Boolean.TRUE.equals(before.get("passed")) && Boolean.TRUE.equals(after.get("passed")),
                "before", before,
                "after", after,
                "checks", before.get("checks"),
                "failures", failures);
    }

    /** The value at a nested key path, or null when any step is missing. */
    static Object at(Object value, String... keys) {
        Object current = value;
        for (String key : keys) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(key);
        }
        return current;
    }

    private static final Pattern CHILD_GC_LOG = Pattern.compile("gc-(\\d+)\\.log");

    /** Committed native memory summed over the gateway and every observed child, or null unless every JVM reported it. */
    static Double treeNativeMemoryBytes(Map<String, Object> repetition) {
        if (!(at(repetition, "telemetry", "process_tree") instanceof Map<?, ?>)) {
            return null;
        }
        if (!(at(repetition, "telemetry", "nmt", "committed_bytes") instanceof Number gateway)) {
            return null;
        }
        double total = gateway.doubleValue();
        for (long pid : pidsOf(at(repetition, "telemetry", "process_tree", "observed_child_pids"))) {
            if (!(at(repetition, "telemetry", "process_tree", "child_telemetry", Long.toString(pid), "nmt", "committed_bytes")
                    instanceof Number child)) {
                return null;
            }
            total += child.doubleValue();
        }
        return total;
    }

    /** Allocation-sample weight per measured second, summed over every JVM in the tree, or null unless every JVM has one. */
    static Double treeAllocationRate(Map<String, Object> repetition) {
        if (!(at(repetition, "telemetry", "process_tree") instanceof Map<?, ?>)) {
            return null;
        }
        if (!(at(repetition, "load", "phases", "measure_s") instanceof Number seconds) || seconds.doubleValue() <= 0) {
            return null;
        }
        if (!(at(repetition, "telemetry", "jfr", "allocation_sample_weight_bytes") instanceof Number gateway)) {
            return null;
        }
        double weight = gateway.doubleValue();
        for (long pid : pidsOf(at(repetition, "telemetry", "process_tree", "observed_child_pids"))) {
            if (!(at(repetition, "telemetry", "process_tree", "child_telemetry", Long.toString(pid), "jfr",
                    "allocation_sample_weight_bytes") instanceof Number child)) {
                return null;
            }
            weight += child.doubleValue();
        }
        return weight / seconds.doubleValue() / 1024 / 1024;
    }

    /** The gateway's own list of live child processes, read from its /runtime identity. */
    static List<Map<String, Object>> childProcessesOf(int port) {
        try {
            Map<String, Object> identity = Json.asMap(Json.parse(get("http://127.0.0.1:" + port + "/runtime")));
            List<Map<String, Object>> children = new ArrayList<>();
            for (Object item : Json.asList(identity.get("child_processes"))) {
                children.add(Json.asMap(item));
            }
            return children;
        } catch (IOException exception) {
            throw new BenchError("cannot read the gateway's child processes: " + exception.getMessage(), exception);
        }
    }

    /** Pids from a list of numbers or of maps with a numeric {@code pid}. Anything else is ignored. */
    static List<Long> pidsOf(Object value) {
        List<Long> pids = new ArrayList<>();
        for (Object item : Json.asList(value)) {
            Object pid = item instanceof Map<?, ?> map ? map.get("pid") : item;
            if (pid instanceof Number number) {
                pids.add(number.longValue());
            }
        }
        return pids;
    }

    /**
     * Children exit when the gateway stops, after writing their captures, so their files are complete once this returns.
     * A child still running after the wait is stopped; the gates then report its missing telemetry.
     */
    static void awaitExit(List<Long> pids) {
        for (long pid : pids) {
            Optional<ProcessHandle> handle = ProcessHandle.of(pid);
            if (handle.isEmpty()) {
                continue;
            }
            try {
                handle.get().onExit().get(10, TimeUnit.SECONDS);
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException | TimeoutException ignored) {
                handle.get().destroyForcibly();
            }
        }
    }

    /** GC logs that children wrote, keyed by the child's pid taken from the file name. */
    static Map<Long, String> childGcLogs(Path directory) {
        Map<Long, String> logs = new TreeMap<>();
        if (!Files.isDirectory(directory)) {
            return logs;
        }
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.toList()) {
                Matcher name = CHILD_GC_LOG.matcher(String.valueOf(file.getFileName()));
                if (name.matches()) {
                    logs.put(Long.parseLong(name.group(1)), read(file));
                }
            }
        } catch (IOException exception) {
            throw new BenchError("cannot read " + directory + ": " + exception.getMessage(), exception);
        }
        return logs;
    }

    private static long count(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private static List<Double> doubles(Object value) {
        List<Double> values = new ArrayList<>();
        for (Object item : Json.asList(value)) {
            values.add(item instanceof Number number ? number.doubleValue() : null);
        }
        return values;
    }

    private static List<String> strings(Object value) {
        List<String> values = new ArrayList<>();
        for (Object item : Json.asList(value)) {
            values.add(String.valueOf(item));
        }
        return values;
    }

    private static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new BenchError("tier2.json is missing " + key);
        }
        return String.valueOf(value);
    }

    private static boolean hasEntries(Path directory) {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.findAny().isPresent();
        } catch (IOException exception) {
            throw new BenchError("cannot list " + directory, exception);
        }
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException exception) {
            return 0;
        }
    }

    private static String read(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
        } catch (IOException exception) {
            return "";
        }
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }

    private static String readRequired(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8).strip();
        } catch (IOException exception) {
            throw new BenchError("cannot read " + path + ": " + exception.getMessage(), exception);
        }
    }

    private static void writeText(Path path, String text) {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            Files.writeString(path, text, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new BenchError("cannot write " + path + ": " + exception.getMessage(), exception);
        }
    }

    private static void writeJson(Path path, Object value) {
        writeText(path, Json.pretty(value));
    }

    private static String tail(String text) {
        return text.length() <= 500 ? text : text.substring(text.length() - 500);
    }
}
