package com.palaashatri.bench.common;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;

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
                "java_executable", javaExecutable().toString(), "jvm_args", ManagementFactory.getRuntimeMXBean().getInputArguments()));
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
            System.exit(0);
        }, "parent-liveness");
        thread.setDaemon(true);
        thread.start();
    }
}
