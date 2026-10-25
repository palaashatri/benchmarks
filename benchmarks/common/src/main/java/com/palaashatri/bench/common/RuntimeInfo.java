package com.palaashatri.bench.common;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import javax.management.ObjectName;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;

/** Process identity and a port publication made only after the listener starts. */
public final class RuntimeInfo {
    private RuntimeInfo() { }
    public static String role() { return System.getenv().getOrDefault("BENCH_SERVICE_ROLE", "gateway"); }
    public static String token() { return System.getenv().getOrDefault("BENCH_RUN_TOKEN", ""); }
    public static Path javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
    }
    public static String json() {
        return Json.stringify(Map.of("pid", ProcessHandle.current().pid(), "run_token", token(),
                "role", role(), "java_version", System.getProperty("java.version"),
                "vm_name", System.getProperty("java.vm.name"),
                "collectors", ManagementFactory.getGarbageCollectorMXBeans().stream().map(java.lang.management.GarbageCollectorMXBean::getName).toList(),
                "java_executable", javaExecutable().toString(), "jvm_args", ManagementFactory.getRuntimeMXBean().getInputArguments(),
                "child_processes", ChildJvm.describeLive()));
    }
    public static void publishPort(int port) throws IOException {
        String name = System.getenv("BENCH_PORT_FILE");
        if (name == null || name.isBlank()) return;
        Path target = Path.of(name);
        Path temporary = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.writeString(temporary, Integer.toString(port));
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException unsupported) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
    public static void watchParent() {
        String parent = System.getenv("BENCH_PARENT_PID");
        if (parent == null) return;
        // The parent owns the write end of this pipe. EOF also works when a
        // PID namespace and its mounted /proc expose different PID numbers.
        Thread thread = new Thread(() -> {
            try { while (System.in.read() != -1) { } }
            catch (IOException closed) { }
            captureChildTelemetry();
            System.exit(0);
        }, "parent-liveness");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Writes this JVM's native memory summary and its {@code bench} flight recording into the directory the engine
     * names with {@code -Dbench.child.telemetry}, just before the child exits. The child takes them itself because a
     * child the gateway retires mid-run is gone before any snapshot from outside could reach it. A capture that fails
     * leaves its file absent, and the engine reports the missing telemetry.
     */
    static void captureChildTelemetry() {
        String directory = System.getProperty("bench.child.telemetry");
        if (directory == null || directory.isBlank()) return;
        long pid = ProcessHandle.current().pid();
        Path target = Path.of(directory);
        var problems = new StringBuilder();
        try {
            Files.createDirectories(target);
            Object summary = ManagementFactory.getPlatformMBeanServer().invoke(
                    new ObjectName("com.sun.management:type=DiagnosticCommand"), "vmNativeMemory",
                    new Object[]{new String[]{"summary", "scale=KB"}}, new String[]{String[].class.getName()});
            Files.writeString(target.resolve("nmt-" + pid + ".txt"), String.valueOf(summary));
        } catch (Exception failure) {
            problems.append("native memory: ").append(failure).append('\n');
        }
        try {
            boolean found = false;
            for (Recording recording : FlightRecorder.getFlightRecorder().getRecordings()) {
                if ("bench".equals(recording.getName())) {
                    recording.dump(target.resolve("jfr-" + pid + ".jfr"));
                    found = true;
                }
            }
            if (!found) problems.append("no recording named bench\n");
        } catch (Exception failure) {
            problems.append("flight recording: ").append(failure).append('\n');
        }
        if (problems.length() > 0) {
            try { Files.writeString(target.resolve("error-" + pid + ".txt"), problems.toString()); }
            catch (IOException ignored) { }
        }
    }
}
