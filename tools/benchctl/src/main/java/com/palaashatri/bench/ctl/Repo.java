package com.palaashatri.bench.ctl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Locations inside the repository, the Maven wrapper for each platform, and the workload catalogue. */
public final class Repo {
    private static final String CATALOG = "benchmarks/catalog.json";

    private final Path root;

    private Repo(Path root) {
        this.root = root;
    }

    /** The repository that contains this controller: BENCH_ROOT if set, otherwise the nearest ancestor of the working directory. */
    public static Repo locate() {
        String override = System.getenv("BENCH_ROOT");
        if (override != null && !override.isBlank()) {
            return checked(Paths.get(override).toAbsolutePath().normalize());
        }
        Path directory = Paths.get("").toAbsolutePath();
        while (directory != null) {
            if (Files.isRegularFile(directory.resolve(CATALOG))) {
                return new Repo(directory);
            }
            directory = directory.getParent();
        }
        throw new BenchError("no benchmark repository found above " + Paths.get("").toAbsolutePath()
                + "; run from the repository or set BENCH_ROOT");
    }

    /** The repository, or null when none is found: doctor must still report on a host outside the repository. */
    public static Repo locateOrNull() {
        try {
            return locate();
        } catch (BenchError exception) {
            return null;
        }
    }

    private static Repo checked(Path root) {
        if (!Files.isRegularFile(root.resolve(CATALOG))) {
            throw new BenchError("BENCH_ROOT does not contain " + CATALOG + ": " + root);
        }
        return new Repo(root);
    }

    public Path root() {
        return root;
    }

    public Path stateDir() {
        return root.resolve(".benchctl");
    }

    public Path runtimesFile() {
        return stateDir().resolve("runtimes.json");
    }

    public Path workloadDirectory(String workloadId) {
        return root.resolve("benchmarks").resolve(workloadId);
    }

    /** Catalogue entries from benchmarks/catalog.json, in file order. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> catalog() {
        Map<String, Object> document = Json.asMap(readJson(root.resolve(CATALOG)));
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Object item : Json.asList(document.get("workloads"))) {
            entries.add((Map<String, Object>) item);
        }
        return entries;
    }

    public Object readJson(Path path) {
        return readJsonFile(path);
    }

    /** Experiment manifests and catalogue files are JSON (valid YAML), read with a message that says so. */
    public static Object readJsonFile(Path path) {
        try {
            return Json.parse(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new BenchError("cannot read " + path + ": " + exception.getMessage());
        } catch (IllegalArgumentException exception) {
            throw new BenchError(path + " must use JSON syntax (JSON is valid YAML): " + exception.getMessage());
        }
    }

    /** The Maven wrapper for a project directory: mvnw on POSIX systems, mvnw.cmd on Windows. */
    public static List<String> mavenCommand(Path projectDirectory) {
        return mavenCommand(projectDirectory, isWindows());
    }

    static List<String> mavenCommand(Path projectDirectory, boolean windows) {
        Path wrapper = projectDirectory.resolve(windows ? "mvnw.cmd" : "mvnw");
        if (!Files.isRegularFile(wrapper)) {
            throw new BenchError("no Maven wrapper in " + projectDirectory + " (expected " + wrapper.getFileName() + ")");
        }
        if (windows) {
            return List.of("cmd.exe", "/c", wrapper.toString());
        }
        return List.of(wrapper.toString());
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    public static boolean isMacOs() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");
    }

    public static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("linux");
    }

    /** The Java launcher inside a JDK home, with the .exe suffix on Windows. */
    public static Path javaExecutable(Path javaHome, String tool) {
        return javaHome.resolve("bin").resolve(isWindows() ? tool + ".exe" : tool);
    }
}
