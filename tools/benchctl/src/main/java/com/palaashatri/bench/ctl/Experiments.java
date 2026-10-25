package com.palaashatri.bench.ctl;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Validation of experiment manifests, and the plan each one expands to. */
public final class Experiments {
    private static final Set<String> RUN_KINDS = Set.of("smoke", "calibration", "benchmark");

    private Experiments() {
    }

    public static Map<String, Object> load(Path path) {
        Object document = Repo.readJsonFile(path);
        if (!(document instanceof Map)) {
            throw new BenchError(path + " must contain an object");
        }
        return Json.asMap(document);
    }

    public static List<String> validate(Map<String, Object> data) {
        List<String> errors = new ArrayList<>();
        if (!"1.0.0".equals(data.get("schema_version"))) {
            errors.add("schema_version must be 1.0.0");
        }
        if (!RUN_KINDS.contains(data.get("run_kind"))) {
            errors.add("run_kind must be smoke, calibration, or benchmark");
        }
        for (String key : List.of("workloads", "runtimes", "gcs")) {
            Object value = data.get(key);
            if (!(value instanceof List<?> items) || items.isEmpty()) {
                errors.add(key + " must be a non-empty array");
            }
        }
        long repetitions = integer(data, "repetitions", 1);
        if (repetitions < 1) {
            errors.add("repetitions must be a positive integer");
        }
        if ("benchmark".equals(data.get("run_kind")) && repetitions < 5) {
            errors.add("benchmark runs require at least 5 repetitions");
        }
        for (String key : List.of("requests", "threads")) {
            if (integer(data, key, 1) < 1) {
                errors.add(key + " must be a positive integer");
            }
        }
        return errors;
    }

    /**
     * The workload x runtime x collector combinations an experiment asks for. Combinations outside a workload's
     * JDK band, outside a runtime's probed collectors, or outside the measurement tiers are skipped with a reason.
     */
    public static Map<String, Object> plan(Map<String, Object> data, Repo repo) {
        List<String> errors = validate(data);
        if (!errors.isEmpty()) {
            throw new BenchError("invalid experiment:\n- " + String.join("\n- ", errors));
        }
        List<Map<String, Object>> runtimes = selectRuntimes(strings(data.get("runtimes")), Runtimes.load(repo));
        List<Map<String, Object>> workloads = selectWorkloads(repo, strings(data.get("workloads")));
        String runKind = (String) data.get("run_kind");
        List<Map<String, Object>> items = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();

        for (Map<String, Object> workload : workloads) {
            long minimum = integer(workload, "min_jdk", 8);
            long maximum = integer(workload, "max_jdk", 25);
            for (Map<String, Object> runtime : runtimes) {
                long feature = ((Number) runtime.get("feature_version")).longValue();
                for (String gc : strings(data.get("gcs"))) {
                    if (feature < minimum || feature > maximum) {
                        skipped.add(skip(workload, runtime, gc,
                                "workload supports JDK " + minimum + "-" + maximum + "; runtime is JDK " + feature));
                        continue;
                    }
                    Map<String, Object> collector = Json.asMap(Json.asMap(runtime.get("collectors")).get(gc));
                    if (collector.isEmpty() || !Boolean.TRUE.equals(collector.get("supported"))) {
                        skipped.add(skip(workload, runtime, gc,
                                String.valueOf(collector.getOrDefault("reason", "collector not discovered"))));
                        continue;
                    }
                    String tier = String.valueOf(workload.getOrDefault("tier", "tier-0"));
                    if ("benchmark".equals(runKind) && !Set.of("tier-2", "tier-3").contains(tier)) {
                        skipped.add(skip(workload, runtime, gc, tier + " is not measurement-ready"));
                        continue;
                    }
                    items.add(Json.map(
                            "workload", workload.get("id"),
                            "workload_path", workload.get("path"),
                            "tier", tier,
                            "runtime", runtime.get("id"),
                            "java_home", runtime.get("java_home"),
                            "java", runtime.get("java"),
                            "feature_version", feature,
                            "gc", gc,
                            "gc_flags", collector.get("flags"),
                            "repetitions", integer(data, "repetitions", 1),
                            "requests", integer(data, "requests", 25),
                            "threads", integer(data, "threads", 4)));
                }
            }
        }
        return Json.map(
                "schema_version", "1.0.0",
                "run_kind", runKind,
                "generated_at_epoch_ms", Instant.now().toEpochMilli(),
                "items", items,
                "skipped", skipped);
    }

    static List<Map<String, Object>> selectRuntimes(List<String> selectors, List<Map<String, Object>> runtimes) {
        java.util.LinkedHashMap<String, Map<String, Object>> selected = new java.util.LinkedHashMap<>();
        for (String selector : selectors) {
            if ("all".equals(selector)) {
                runtimes.forEach(runtime -> selected.put((String) runtime.get("id"), runtime));
            } else if (selector.chars().allMatch(Character::isDigit)) {
                long feature = Long.parseLong(selector);
                runtimes.stream().filter(runtime -> ((Number) runtime.get("feature_version")).longValue() == feature)
                        .forEach(runtime -> selected.put((String) runtime.get("id"), runtime));
            } else {
                runtimes.stream().filter(runtime -> selector.equals(runtime.get("id")))
                        .forEach(runtime -> selected.put((String) runtime.get("id"), runtime));
            }
        }
        return selected.values().stream()
                .sorted(java.util.Comparator.comparingLong((Map<String, Object> runtime) ->
                        ((Number) runtime.get("feature_version")).longValue()).thenComparing(runtime -> (String) runtime.get("id")))
                .toList();
    }

    static List<Map<String, Object>> selectWorkloads(Repo repo, List<String> selectors) {
        List<Map<String, Object>> available = repo.catalog();
        if (selectors.contains("all")) {
            return available;
        }
        Set<String> known = new TreeSet<>();
        available.forEach(workload -> known.add((String) workload.get("id")));
        List<String> missing = selectors.stream().filter(selector -> !known.contains(selector)).toList();
        if (!missing.isEmpty()) {
            throw new BenchError("unknown workloads: " + String.join(", ", missing));
        }
        return selectors.stream()
                .map(selector -> available.stream().filter(item -> selector.equals(item.get("id"))).findFirst().orElseThrow())
                .toList();
    }

    static List<String> strings(Object value) {
        List<String> values = new ArrayList<>();
        for (Object item : Json.asList(value)) {
            values.add(String.valueOf(item));
        }
        return values;
    }

    static long integer(Map<String, Object> map, String key, long fallback) {
        Object value = map.get(key);
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private static Map<String, Object> skip(Map<String, Object> workload, Map<String, Object> runtime, String gc,
                                            String reason) {
        return Json.map(
                "workload", workload.get("id"),
                "runtime", runtime.get("id"),
                "gc", gc,
                "reason", reason);
    }
}
