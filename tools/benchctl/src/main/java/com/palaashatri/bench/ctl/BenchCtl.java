package com.palaashatri.bench.ctl;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Command-line entry point for the OpenJDK benchmark controller. The same commands run on Windows, macOS and
 * Linux: the only requirement is a JDK 17 or newer to run the controller and the Maven wrapper for the workloads.
 */
public final class BenchCtl {
    private final Repo repo;
    private final PrintStream out;
    private final PrintStream err;

    private BenchCtl(Repo repo, PrintStream out, PrintStream err) {
        this.repo = repo;
        this.out = out;
        this.err = err;
    }

    public static void main(String[] args) {
        int status = run(args, System.out, System.err);
        System.exit(status);
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0 || args[0].equals("help") || args[0].equals("--help")) {
            out.println(USAGE);
            return args.length == 0 ? 2 : 0;
        }
        try {
            Repo repo = args[0].equals("doctor") ? Repo.locateOrNull() : Repo.locate();
            BenchCtl ctl = new BenchCtl(repo, out, err);
            return ctl.dispatch(args[0], Arrays.copyOfRange(args, 1, args.length));
        } catch (BenchError exception) {
            err.println("ERROR: " + exception.getMessage());
            return 2;
        }
    }

    static final String USAGE = """
            usage: benchctl <command> [arguments]

              doctor                         check the controller, repository and tools on this host
              discover-runtimes              probe every JDK and record its capabilities
              list-runtimes                  list discovered runtimes
              inspect-runtime <id>           print one runtime record as JSON
              list-capabilities              list each runtime's enabled capabilities
              list-workloads                 list catalogued workloads, tiers and JDK bands
              validate <experiment>          check an experiment manifest
              plan <experiment>              print the planned and skipped combinations
              preflight [--json]             check that this host is quiet enough for Tier 2 runs
              hygiene                        repository hygiene and public-scope gate
              fetch-jdks --feature <n> ...   install verified OpenJDK builds under .benchctl/jdks
              run <experiment>               run an experiment plan (Tier 2 or smoke items)
              compare <left> <right>         statistical comparison of two benchmark results
              resume <run-id>                re-run the experiment stored under results/<run-id>
              report <path> ... [-o <file>]  render result envelopes as a readable HTML report
              verify-results <path>          check a result envelope's structure
              lane startup|jobs|chat ...     run the startup (07), jobs (08) or chat (06) lane with its gates
              tier2 --java-home <jdk> --out <dir> [options]
                                             measure one workload with the Tier 2 engine
            """;

    private int dispatch(String command, String[] arguments) {
        return switch (command) {
            case "doctor" -> doctor();
            case "discover-runtimes" -> discover();
            case "list-runtimes" -> listRuntimes();
            case "inspect-runtime" -> inspectRuntime(arguments);
            case "list-capabilities" -> listCapabilities();
            case "list-workloads" -> listWorkloads();
            case "validate" -> validate(arguments);
            case "plan" -> plan(arguments);
            case "preflight" -> preflight(arguments);
            case "hygiene" -> hygiene();
            case "tier2" -> tier2(arguments);
            case "run" -> run(arguments);
            case "resume" -> resume(arguments);
            case "compare" -> compare(arguments);
            case "report" -> report(arguments);
            case "verify-results" -> verifyResults(arguments);
            case "lane" -> lane(arguments);
            case "fetch-jdks" -> fetchJdks(arguments);
            default -> {
                err.println("ERROR: unknown command: " + command);
                err.println(USAGE);
                yield 2;
            }
        };
    }

    private int doctor() {
        Path javaBin = Repo.javaExecutable(Path.of(System.getProperty("java.home")), "java");
        List<Object[]> checks = new ArrayList<>();
        checks.add(new Object[] {"java", true, System.getProperty("java.version") + " " + javaBin, true});
        checks.add(new Object[] {"controller", Runtime.version().feature() >= 17,
                "needs JDK 17 or newer; running " + Runtime.version().feature(), true});
        Path root = repo == null ? null : repo.root();
        checks.add(new Object[] {"repository", root != null, root == null ? "not found" : root.toString(), true});
        Shell.Result git = Shell.run(List.of("git", "--version"), null, null, java.time.Duration.ofSeconds(20));
        checks.add(new Object[] {"git", git.succeeded(), git.succeeded() ? git.stdout().strip() : "not found", false});
        boolean mavenWrapper = root != null && Files.isRegularFile(root.resolve(Repo.isWindows() ? "mvnw.cmd" : "mvnw"));
        checks.add(new Object[] {"maven-wrapper", mavenWrapper, mavenWrapper ? "present" : "missing", true});
        boolean failed = false;
        for (Object[] check : checks) {
            boolean ok = (Boolean) check[1];
            boolean required = (Boolean) check[3];
            String status = ok ? "OK" : required ? "FAIL" : "WARN";
            out.printf("%-4s %-14s %s%n", status, check[0], check[2]);
            failed |= required && !ok;
        }
        return failed ? 1 : 0;
    }

    private int discover() {
        List<Map<String, Object>> runtimes = Runtimes.discover(repo);
        for (Map<String, Object> runtime : runtimes) {
            List<String> supported = new ArrayList<>();
            Map<String, Object> collectors = Json.asMap(runtime.get("collectors"));
            collectors.forEach((name, value) -> {
                if (Boolean.TRUE.equals(Json.asMap(value).get("supported"))) {
                    supported.add(name);
                }
            });
            out.printf("%s JDK %s %s collectors=%s%n", runtime.get("id"), runtime.get("feature_version"),
                    runtime.get("vm_name"), String.join(",", supported));
        }
        if (runtimes.isEmpty()) {
            err.println("no OpenJDK HotSpot runtime found; set JAVA_HOME or BENCH_JAVA_HOMES, or run fetch-jdks");
            return 1;
        }
        return 0;
    }

    private int listRuntimes() {
        for (Map<String, Object> runtime : Runtimes.load(repo)) {
            String identity = String.valueOf(runtime.get("identity"));
            out.printf("%s\tJDK %s\t%s\tsha256=%s%n", runtime.get("id"), runtime.get("feature_version"),
                    runtime.get("java_home"), identity.substring(0, Math.min(16, identity.length())));
        }
        return 0;
    }

    private int inspectRuntime(String[] arguments) {
        if (arguments.length != 1) {
            throw new BenchError("usage: benchctl inspect-runtime <runtime-id>");
        }
        for (Map<String, Object> runtime : Runtimes.load(repo)) {
            if (arguments[0].equals(runtime.get("id"))) {
                out.print(Json.pretty(runtime));
                return 0;
            }
        }
        throw new BenchError("unknown runtime: " + arguments[0]);
    }

    private int listCapabilities() {
        for (Map<String, Object> runtime : Runtimes.load(repo)) {
            List<String> enabled = new ArrayList<>();
            Map<String, Object> capabilities = Json.asMap(runtime.get("capabilities"));
            capabilities.forEach((name, value) -> {
                if (Boolean.TRUE.equals(value) || (value instanceof String text && !text.equals("unavailable"))) {
                    enabled.add(name);
                }
            });
            out.println(runtime.get("id") + ": " + String.join(", ", enabled));
        }
        return 0;
    }

    private int listWorkloads() {
        for (Map<String, Object> workload : repo.catalog()) {
            out.printf("%s\t%s\tJDK %s-%s\t%s%n", workload.get("id"), workload.getOrDefault("tier", "tier-0"),
                    workload.getOrDefault("min_jdk", 8), workload.getOrDefault("max_jdk", 25),
                    workload.getOrDefault("status", "prototype"));
        }
        return 0;
    }

    private int validate(String[] arguments) {
        Path experiment = singleExperiment(arguments);
        List<String> errors = Experiments.validate(Experiments.load(experiment));
        if (!errors.isEmpty()) {
            errors.forEach(error -> out.println("ERROR " + error));
            return 1;
        }
        out.println("valid: " + arguments[0]);
        return 0;
    }

    private int plan(String[] arguments) {
        Map<String, Object> plan = Experiments.plan(Experiments.load(singleExperiment(arguments)), repo);
        out.print(Json.pretty(plan));
        int items = Json.asList(plan.get("items")).size();
        int skipped = Json.asList(plan.get("skipped")).size();
        err.println("planned=" + items + " skipped=" + skipped);
        return items == 0 ? 2 : 0;
    }

    private int preflight(String[] arguments) {
        boolean json = Arrays.asList(arguments).contains("--json");
        Map<String, Object> report = HostEnvironment.preflight(HostEnvironment.fingerprint(repo));
        if (json) {
            out.print(Json.pretty(report));
        } else {
            for (Object item : Json.asList(report.get("checks"))) {
                Map<String, Object> check = Json.asMap(item);
                out.printf("%-5s %-22s %s%n", String.valueOf(check.get("status")).toUpperCase(java.util.Locale.ROOT),
                        check.get("check"), check.get("detail"));
            }
            out.println("quiet-machine preflight " + (Boolean.TRUE.equals(report.get("passed")) ? "passed" : "FAILED"));
        }
        return Boolean.TRUE.equals(report.get("passed")) ? 0 : 1;
    }

    private int tier2(String[] arguments) {
        Tier2.Options options = Tier2.parse(arguments);
        Map<String, Object> result = new Tier2(repo, out).run(options);
        boolean allPassed = Json.asList(result.get("repetitions")).stream()
                .allMatch(item -> "passed".equals(Json.asMap(item).get("status")));
        return allPassed ? 0 : 1;
    }

    private int run(String[] arguments) {
        Path experiment = singleExperiment(arguments);
        Map<String, Object> plan = Experiments.plan(Experiments.load(experiment), repo);
        if (Json.asList(plan.get("items")).isEmpty()) {
            throw new BenchError("experiment produced no runnable combinations");
        }
        out.println(Execution.run(repo, plan, experiment, out));
        return 0;
    }

    private int compare(String[] arguments) {
        if (arguments.length != 2) {
            throw new BenchError("usage: benchctl compare <left> <right>");
        }
        Map<String, Object> document = Compare.compare(resultAt(arguments[0]), resultAt(arguments[1]));
        out.print(Json.pretty(document));
        return "invalid".equals(document.get("outcome")) ? 2 : 0;
    }

    private int verifyResults(String[] arguments) {
        List<String> errors = ResultEnvelopes.validate(resultAt(arguments.length == 1 ? arguments[0] : "."));
        if (!errors.isEmpty()) {
            errors.forEach(error -> out.println("ERROR " + error));
            return 1;
        }
        out.println("valid result envelope");
        return 0;
    }

    private int lane(String[] arguments) {
        if (arguments.length < 1) {
            throw new BenchError("usage: benchctl lane <startup|jobs> --app-classpath <path> --out <file> [options]");
        }
        java.util.Map<String, String> flags = new java.util.HashMap<>();
        for (int index = 1; index + 1 < arguments.length; index += 2) {
            flags.put(arguments[index], arguments[index + 1]);
        }
        String classpath = flags.get("--app-classpath");
        if (classpath == null || !flags.containsKey("--out")) {
            throw new BenchError("a lane needs --app-classpath and --out");
        }
        String javaExecutable = flags.getOrDefault("--java", Path.of(System.getProperty("java.home"), "bin",
                Repo.isWindows() ? "java.exe" : "java").toString());
        Path temporary;
        try {
            temporary = Files.createTempDirectory("bench-lane-");
        } catch (IOException exception) {
            throw new BenchError("cannot create a lane directory: " + exception.getMessage(), exception);
        }
        String runKind = flags.getOrDefault("--run-kind", "calibration");
        if (!runKind.equals("calibration") && !runKind.equals("benchmark")) {
            throw new BenchError("--run-kind must be calibration or benchmark");
        }
        int repetitions = Integer.parseInt(flags.getOrDefault("--repetitions", "5"));
        Map<String, Object> result = switch (arguments[0]) {
            case "startup" -> Lanes.startup(javaExecutable, classpath,
                    Integer.parseInt(flags.getOrDefault("--samples", "10")), repetitions, runKind,
                    tierOf("07-coldstart-suite"), temporary, repo);
            case "jobs" -> Lanes.jobs(javaExecutable, classpath,
                    Integer.parseInt(flags.getOrDefault("--jobs", "10")),
                    Double.parseDouble(flags.getOrDefault("--rate", "1")),
                    Integer.parseInt(flags.getOrDefault("--concurrency", "1")),
                    Integer.parseInt(flags.getOrDefault("--job-timeout", "300")), repetitions, runKind,
                    tierOf("08-etl-batch"), temporary, repo);
            case "chat" -> {
                String driver = flags.get("--driver-classpath");
                if (driver == null) {
                    throw new BenchError("the chat lane needs --driver-classpath (the harness classes)");
                }
                yield Lanes.chat(javaExecutable, driver, classpath,
                        new Lanes.ChatSettings(Integer.parseInt(flags.getOrDefault("--subscribers", "200")),
                                Integer.parseInt(flags.getOrDefault("--rooms", "8")),
                                Integer.parseInt(flags.getOrDefault("--publishers", "8")),
                                Double.parseDouble(flags.getOrDefault("--rate", "500")),
                                Double.parseDouble(flags.getOrDefault("--warmup-seconds", "3")),
                                Double.parseDouble(flags.getOrDefault("--measure-seconds", "10"))),
                        repetitions, runKind, tierOf("06-massive-chat-loom"), temporary, repo);
            }
            default -> throw new BenchError("unknown lane: " + arguments[0]);
        };
        try {
            Path target = Path.of(flags.get("--out"));
            Files.createDirectories(target.toAbsolutePath().getParent());
            Files.writeString(target, Json.pretty(result), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new BenchError("cannot write the lane envelope: " + exception.getMessage(), exception);
        }
        out.println("wrote " + flags.get("--out"));
        return 0;
    }

    /** The catalogued tier of a workload. Lanes take it from the catalogue, never from their own code. */
    private String tierOf(String workload) {
        return repo.catalog().stream().filter(item -> workload.equals(item.get("id"))).findFirst()
                .map(item -> String.valueOf(item.get("tier")))
                .orElseThrow(() -> new BenchError(workload + " is not in benchmarks/catalog.json"));
    }

    private Map<String, Object> resultAt(String path) {
        return loadResult(Path.of(path));
    }

    /** A result envelope, or the {@code result.json} inside a run directory. */
    private Map<String, Object> loadResult(Path path) {
        Path target = Files.isDirectory(path) ? path.resolve("result.json") : path;
        return Json.asMap(repo == null ? Repo.readJsonFile(target) : repo.readJson(target));
    }

    private int resume(String[] arguments) {
        if (arguments.length != 1) {
            throw new BenchError("usage: benchctl resume <run-id>");
        }
        Path experiment = repo.root().resolve("results").resolve(arguments[0]).resolve("experiment.yaml");
        if (!Files.isRegularFile(experiment)) {
            throw new BenchError("cannot resume: " + experiment + " not found");
        }
        Map<String, Object> plan = Experiments.plan(Experiments.load(experiment), repo);
        out.println(Execution.run(repo, plan, experiment, out));
        return 0;
    }

    private int report(String[] arguments) {
        List<Path> paths = new ArrayList<>();
        Path output = null;
        for (int index = 0; index < arguments.length; index++) {
            if ((arguments[index].equals("-o") || arguments[index].equals("--output")) && index + 1 < arguments.length) {
                output = Path.of(arguments[++index]);
            } else {
                paths.add(Path.of(arguments[index]));
            }
        }
        if (paths.isEmpty()) {
            throw new BenchError("usage: benchctl report <path> [<path> ...] [-o <file.html>]");
        }
        if (output == null) {
            output = defaultReportPath(paths.get(0));
        }
        out.println(Report.write(paths, output, this::loadResult));
        return 0;
    }

    /** A run directory gets {@code report.html} inside it; a result file gets an HTML file beside it. */
    static Path defaultReportPath(Path first) {
        Path absolute = first.toAbsolutePath().normalize();
        if (Files.isDirectory(absolute)) {
            return absolute.resolve("report.html");
        }
        String name = String.valueOf(absolute.getFileName());
        int dot = name.lastIndexOf('.');
        return absolute.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + ".html");
    }

    private int fetchJdks(String[] arguments) {
        List<Integer> features = new java.util.ArrayList<>();
        for (int index = 0; index < arguments.length; index++) {
            if (arguments[index].equals("--feature") && index + 1 < arguments.length) {
                features.add(Integer.parseInt(arguments[++index]));
            } else {
                throw new BenchError("usage: benchctl fetch-jdks --feature <release> [--feature <release> ...]");
            }
        }
        if (features.isEmpty()) {
            throw new BenchError("fetch-jdks needs at least one --feature");
        }
        String api = System.getenv().getOrDefault("BENCH_ADOPTIUM_API", JdkFetch.DEFAULT_API);
        int failures = 0;
        for (int feature : features) {
            try {
                out.println(new JdkFetch(api).install(feature, repo.stateDir().resolve("jdks")));
            } catch (BenchError exception) {
                failures++;
                err.println("ERROR JDK " + feature + ": " + exception.getMessage());
            }
        }
        return failures == 0 ? 0 : 2;
    }

    private int hygiene() {
        try {
            int checked = Hygiene.check(repo);
            out.println("repository hygiene passed (" + checked + " tracked files checked)");
            return 0;
        } catch (BenchError failure) {
            err.println(failure.getMessage());
            return 1;
        }
    }

    private static Path singleExperiment(String[] arguments) {
        if (arguments.length != 1) {
            throw new BenchError("expected one experiment file");
        }
        return Path.of(arguments[0]);
    }

}
