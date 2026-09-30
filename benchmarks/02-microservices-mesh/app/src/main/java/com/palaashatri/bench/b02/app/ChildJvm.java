package com.palaashatri.bench.b02.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Launches an independent HotSpot child for one mesh service role. */
final class ChildJvm {
    final String role;
    final Process process;
    final Path portFile;
    final int port;

    ChildJvm(String role, String token) throws IOException {
        this.role = role;
        this.portFile = Files.createTempFile("b02-" + role + "-", ".port");
        Files.writeString(portFile, "");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classPath = System.getProperty("java.class.path");
        List<String> command = new ArrayList<>();
        command.add(java);
        command.add("-cp");
        command.add(classPath);
        command.add(BenchmarkApp.class.getName());
        command.add("0");
        ProcessBuilder builder = new ProcessBuilder(command);
        Map<String, String> env = builder.environment();
        env.put("BENCH_SERVICE_ROLE", role);
        env.put("BENCH_RUN_TOKEN", token == null ? "" : token);
        env.put("BENCH_PORT_FILE", portFile.toString());
        builder.redirectErrorStream(true);
        builder.redirectOutput(portFile.resolveSibling(role + ".log").toFile());
        this.process = builder.start();
        this.port = waitForPort(portFile, process, Duration.ofSeconds(15));
        Runtime.getRuntime().addShutdownHook(new Thread(this::destroy));
    }

    boolean alive() {
        return process.isAlive();
    }

    void destroy() {
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static int waitForPort(Path portFile, Process process, Duration limit)
            throws IOException {
        long deadline = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IOException("child JVM exited before publishing a port");
            }
            String text = Files.readString(portFile).trim();
            if (!text.isEmpty()) {
                try {
                    return Integer.parseInt(text);
                } catch (NumberFormatException ignored) {
                }
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted waiting for child port");
            }
        }
        throw new IOException("timed out waiting for child JVM port file " + portFile);
    }
}
