package com.palaashatri.bench.ctl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs external commands on any operating system, capturing both output streams. */
public final class Shell {
    /** Exit status 127 is used for commands that could not start or timed out, as in POSIX shells. */
    public static final int NOT_RUN = 127;

    private Shell() {
    }

    public record Result(int exitCode, String stdout, String stderr) {
        public String combined() {
            return (stdout + "\n" + stderr).strip();
        }

        public boolean succeeded() {
            return exitCode == 0;
        }
    }

    public static Result run(List<String> command, Path directory, Map<String, String> environment,
                             Duration timeout) {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (directory != null) {
            builder.directory(directory.toFile());
        }
        if (environment != null) {
            builder.environment().putAll(environment);
        }
        Process process;
        try {
            process = builder.start();
        } catch (IOException exception) {
            return new Result(NOT_RUN, "", exception.toString());
        }
        try {
            process.getOutputStream().close();
        } catch (IOException exception) {
            // The child has already closed its input; nothing is lost by ignoring this.
        }
        StreamCollector stdout = new StreamCollector(process.getInputStream());
        StreamCollector stderr = new StreamCollector(process.getErrorStream());
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                return new Result(NOT_RUN, stdout.text(), "timed out after " + timeout.toSeconds() + " s");
            }
            return new Result(process.exitValue(), stdout.text(), stderr.text());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new Result(NOT_RUN, stdout.text(), "interrupted");
        }
    }

    /** Drains one stream on its own thread so a full pipe can never block the child process. */
    private static final class StreamCollector {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final Thread thread;

        StreamCollector(InputStream stream) {
            thread = new Thread(() -> {
                byte[] chunk = new byte[8192];
                try (stream) {
                    int count;
                    while ((count = stream.read(chunk)) != -1) {
                        synchronized (buffer) {
                            buffer.write(chunk, 0, count);
                        }
                    }
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            }, "shell-stream");
            thread.setDaemon(true);
            thread.start();
        }

        String text() {
            try {
                thread.join(TimeUnit.SECONDS.toMillis(10));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            synchronized (buffer) {
                return buffer.toString(StandardCharsets.UTF_8);
            }
        }
    }
}
