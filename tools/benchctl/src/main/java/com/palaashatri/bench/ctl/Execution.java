package com.palaashatri.bench.ctl;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Runs an experiment plan. Benchmark and calibration items run the Tier 2 engine; smoke items run the workload's own
 * correctness gate (its Maven tests). Every item's output is kept under the run directory, and the run is labelled
 * with the strictest validity of its items.
 */
public final class Execution {
    private Execution() {
    }

    public static Path run(Repo repo, Map<String, Object> plan, Path experiment, PrintStream out) {
        String runKind = String.valueOf(plan.get("run_kind"));
        String runId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                + "-" + HexFormat.of().toHexDigits(System.nanoTime()).substring(8);
        Path runDirectory = repo.root().resolve("results").resolve(runId);
        try {
            Files.createDirectories(runDirectory);
            Files.copy(experiment, runDirectory.resolve("experiment.json"));
            Files.writeString(runDirectory.resolve("plan.json"), Json.pretty(plan), StandardCharsets.UTF_8);
            Files.writeString(runDirectory.resolve("environment.json"),
                    Json.pretty(HostEnvironment.fingerprint(repo)), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new BenchError("cannot create " + runDirectory + ": " + exception.getMessage(), exception);
        }
        Map<String, Object> document = Json.asMap(repo.readJson(experiment));
        List<Object> outputs = new ArrayList<>();
        List<Object> items = Json.asList(plan.get("items"));
        boolean allValid = !items.isEmpty();
        int index = 0;
        for (Object raw : items) {
            index++;
            Map<String, Object> item = Json.asMap(raw);
            String name = String.format("%03d-%s-%s-%s", index, item.get("workload"), item.get("runtime"), item.get("gc"));
            Path itemDirectory = runDirectory.resolve(name);
            Map<String, Object> result;
            if ("smoke".equals(runKind)) {
                result = smoke(repo, item, itemDirectory, out);
            } else {
                if (!"tier-2".equals(item.get("tier"))) {
                    throw new BenchError("benchmark execution requires a Tier 2 workload: " + item.get("workload"));
                }
                result = tier2(repo, document, item, runKind, itemDirectory, out);
            }
            allValid &= Boolean.TRUE.equals(result.get("measurement_valid"));
            outputs.add(result);
        }
        Map<String, Object> runDocument = Json.map(
                "schema_version", "1.0.0",
                "run_kind", runKind,
                "measurement_valid", allValid,
                "results", outputs);
        try {
            Files.writeString(runDirectory.resolve("result.json"), Json.pretty(runDocument), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new BenchError("cannot write the run result: " + exception.getMessage(), exception);
        }
        Path html = Report.writeDocument(runDirectory.resolve("report.html"),
                String.valueOf(runDirectory.getFileName()), runDocument);
        out.print(Report.runSummary(runDirectory, runDocument, html));
        return runDirectory;
    }

    private static Map<String, Object> tier2(Repo repo, Map<String, Object> experiment, Map<String, Object> item,
                                             String runKind, Path itemDirectory, PrintStream out) {
        Tier2.Options options = new Tier2.Options(
                String.valueOf(item.get("java_home")),
                null,
                String.valueOf(item.get("gc")),
                Json.number(experiment.getOrDefault("target_rate", 80)),
                (int) Experiments.integer(experiment, "warmup_seconds", 8),
                (int) Experiments.integer(experiment, "measure_seconds", 12),
                (int) Experiments.integer(item, "threads", 16),
                (int) Experiments.integer(item, "repetitions", 5),
                Experiments.integer(experiment, "seed", 424242),
                (int) Experiments.integer(experiment, "heap_mb", 512),
                runKind,
                String.valueOf(item.get("workload")),
                itemDirectory,
                false,
                List.of());
        return new Tier2(repo, out).run(options);
    }

    /** A smoke item: the workload's Maven tests on the item's runtime. Its envelope never claims a measurement. */
    private static Map<String, Object> smoke(Repo repo, Map<String, Object> item, Path itemDirectory, PrintStream out) {
        Path application = repo.workloadDirectory(String.valueOf(item.get("workload"))).resolve("app");
        Map<String, String> environment = Tier2.javaEnvironment(Path.of(String.valueOf(item.get("java_home"))));
        List<String> command = new ArrayList<>(Repo.mavenCommand(application));
        command.addAll(List.of("-B", "-q", "test"));
        long started = System.nanoTime();
        Shell.Result result = Shell.run(command, application, environment, Duration.ofMinutes(30));
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
        try {
            Files.createDirectories(itemDirectory);
            Files.writeString(itemDirectory.resolve("maven-test.log"), result.stdout() + "\n" + result.stderr(),
                    StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new BenchError("cannot write " + itemDirectory + ": " + exception.getMessage(), exception);
        }
        boolean passed = result.succeeded();
        out.println(item.get("workload") + " " + item.get("runtime") + ": " + (passed ? "smoke passed" : "smoke FAILED"));
        return Json.map(
                "schema_version", "1.0.0",
                "benchmark", item.get("workload"),
                "run_kind", "smoke",
                "implementation_tier", item.get("tier"),
                "measurement_valid", false,
                "invalid_reasons", List.of("functional smoke only: the correctness gate is not a benchmark"),
                "warnings", List.of(),
                "runtime", item.get("runtime"),
                "java_home", item.get("java_home"),
                "gc", item.get("gc"),
                "status", passed ? "passed" : "failed",
                "elapsed_ms", elapsedMillis,
                "kpis", null);
    }
}
