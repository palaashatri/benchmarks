package com.palaashatri.bench.testkit;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The classpath of a workload application, read from its own build output: its compiled classes and the dependency
 * list the build wrote to {@code target/classpath.txt}. A harness test uses it to start the application it measures.
 */
public final class AppClasspath {
    private AppClasspath() {
    }

    private static Path packagedJar(Path appDirectory) {
        Path target = appDirectory.resolve("target");
        if (!Files.isDirectory(target)) {
            return null;
        }
        try (java.util.stream.Stream<Path> files = Files.list(target)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .filter(path -> !path.getFileName().toString().startsWith("original-"))
                    .sorted()
                    .findFirst()
                    .map(Path::toAbsolutePath)
                    .orElse(null);
        } catch (IOException exception) {
            return null;
        }
    }

    /**
     * The application's packaged JAR when the build produced one, otherwise its compiled classes. A JAR is preferred
     * because it is the artifact a deployment runs, and some behaviour (AppCDS) exists only for JAR classpaths.
     */
    public static String of(Path appDirectory) {
        Path classes = packagedJar(appDirectory);
        if (classes == null) {
            classes = appDirectory.resolve("target").resolve("classes");
        }
        Path dependencies = appDirectory.resolve("target").resolve("classpath.txt");
        if (!Files.isRegularFile(dependencies)) {
            throw new IllegalStateException("build the application first (./mvnw package in " + appDirectory
                    + "); " + dependencies + " is missing");
        }
        try {
            String list = new String(Files.readAllBytes(dependencies), StandardCharsets.UTF_8).trim();
            return classes.toAbsolutePath() + File.pathSeparator + list;
        } catch (IOException exception) {
            throw new IllegalStateException("cannot read " + dependencies + ": " + exception.getMessage(), exception);
        }
    }
}
