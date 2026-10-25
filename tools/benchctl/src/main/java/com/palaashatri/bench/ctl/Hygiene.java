package com.palaashatri.bench.ctl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Repository hygiene and public-scope gate: no generated output tracked, no out-of-scope terms, valid manifests. */
public final class Hygiene {
    private static final Set<String> FORBIDDEN_PATH_PARTS = Set.of("build", "target", ".gradle", "results", ".benchctl");
    private static final Set<String> FORBIDDEN_SUFFIXES = Set.of(".class", ".jar", ".log", ".pid", ".jfr", ".hprof");
    private static final Set<String> TEXT_SUFFIXES = Set.of(
            ".md", ".java", ".kt", ".kts", ".py", ".sh", ".yml", ".yaml", ".json", ".xml", ".proto", ".cmd", ".properties");
    /** Concatenated so that this source file does not itself contain the terms it forbids. */
    private static final List<String> FORBIDDEN_PUBLIC_TERMS = List.of(
            "Azul Platform" + " Prime",
            "Optimizer" + " Hub",
            "Cloud Native" + " Compiler",
            "Compilation" + " Streaming",
            "Graal" + "VM",
            "Open" + "J9",
            "C" + "RaC");

    private Hygiene() {
    }

    /** Returns the number of tracked files checked, or throws listing every failure. */
    public static int check(Repo repo) {
        List<String> failures = new ArrayList<>();
        List<String> files = trackedFiles(repo);
        for (String rel : files) {
            Path path = Path.of(rel);
            for (Path part : path) {
                if (FORBIDDEN_PATH_PARTS.contains(part.toString())) {
                    failures.add("generated artifact is tracked: " + rel);
                    break;
                }
            }
            String name = path.getFileName().toString();
            if (FORBIDDEN_SUFFIXES.contains(suffix(name)) || name.equals(".DS_Store")) {
                failures.add("generated artifact is tracked: " + rel);
            }
            if (name.equals("CLAUDE.md") || name.equals("STATUS.md")) {
                failures.add("duplicate truth document is tracked: " + rel);
            }
            Path absolute = repo.root().resolve(rel);
            if (TEXT_SUFFIXES.contains(suffix(name)) && Files.isRegularFile(absolute)) {
                String text = read(absolute).toLowerCase(Locale.ROOT);
                for (String term : FORBIDDEN_PUBLIC_TERMS) {
                    if (text.contains(term.toLowerCase(Locale.ROOT))) {
                        failures.add("out-of-scope public term '" + term + "' found in " + rel);
                    }
                }
            }
        }
        for (Path manifest : manifests(repo)) {
            try {
                Map<String, Object> data = Json.asMap(repo.readJson(manifest));
                if (!"1.0.0".equals(data.get("schema_version"))) {
                    failures.add("invalid schema version in " + repo.root().relativize(manifest));
                }
            } catch (BenchError exception) {
                failures.add("invalid experiment " + repo.root().relativize(manifest) + ": " + exception.getMessage());
            }
        }
        if (!failures.isEmpty()) {
            throw new BenchError(String.join("\n", failures.stream().map(item -> "ERROR " + item).toList()));
        }
        return files.size();
    }

    private static List<Path> manifests(Repo repo) {
        List<Path> manifests = new ArrayList<>();
        try (var stream = Files.list(repo.root().resolve("experiments"))) {
            stream.filter(path -> path.getFileName().toString().endsWith(".yaml")).sorted().forEach(manifests::add);
        } catch (IOException exception) {
            throw new BenchError("cannot list experiments: " + exception.getMessage(), exception);
        }
        return manifests;
    }

    /** Files Git tracks, as repository-relative paths with forward slashes. */
    static List<String> trackedFiles(Repo repo) {
        Shell.Result result = Shell.run(List.of("git", "ls-files", "-z"), repo.root(), null, java.time.Duration.ofSeconds(60));
        if (!result.succeeded()) {
            throw new BenchError("git ls-files failed: " + result.combined());
        }
        List<String> files = new ArrayList<>();
        for (String item : result.stdout().split("\0")) {
            if (!item.isEmpty()) {
                files.add(item);
            }
        }
        return files;
    }

    private static String suffix(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return Files.isRegularFile(path) ? new String(bytesOrEmpty(path), StandardCharsets.ISO_8859_1) : "";
        }
    }

    private static byte[] bytesOrEmpty(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            return new byte[0];
        }
    }
}
