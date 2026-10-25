package com.palaashatri.bench.testkit;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

/** Packages a compiled classes directory as a JAR, for tests that need a JAR classpath (AppCDS requires one). */
public final class Jars {
    private Jars() {
    }

    public static Path fromDirectory(Path classes, Path jar) {
        try {
            Files.createDirectories(jar.toAbsolutePath().getParent());
            try (OutputStream file = Files.newOutputStream(jar);
                 JarOutputStream out = new JarOutputStream(file);
                 Stream<Path> paths = Files.walk(classes)) {
                for (Path path : paths.filter(Files::isRegularFile).sorted(Comparator.naturalOrder()).toArray(Path[]::new)) {
                    String name = classes.relativize(path).toString().replace('\\', '/');
                    out.putNextEntry(new JarEntry(name));
                    Files.copy(path, out);
                    out.closeEntry();
                }
            }
            return jar;
        } catch (IOException exception) {
            throw new IllegalStateException("cannot package " + classes + " as " + jar + ": " + exception.getMessage(),
                    exception);
        }
    }
}
