package com.palaashatri.bench.ctl;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Host observations for Tier 2 measurement and the quiet-machine preflight. Each observation is null when the
 * operating system does not expose it. Unknown is never reported as a passing value: a preflight check on an
 * unknown setting fails.
 */
public final class HostEnvironment {
    /** Environment variables the JVM reads at start-up. They add options the controller did not write. */
    static final List<String> JVM_OPTION_VARIABLES = List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS");
    /** A 1-minute load above this fraction of the logical CPUs means the host is not quiet. */
    static final double MAX_LOAD_FRACTION = 0.5;

    private static final String POWERSHELL = "powershell.exe";
    private static Map<String, Object> staticFacts;

    private HostEnvironment() {
    }

    /** The full fingerprint recorded with every result: static host facts plus the moment of capture. */
    public static synchronized Map<String, Object> fingerprint(Repo repo) {
        if (staticFacts == null) {
            staticFacts = staticFacts(repo);
        }
        Map<String, Object> fingerprint = new LinkedHashMap<>(staticFacts);
        fingerprint.put("captured_at_epoch_ms", Instant.now().toEpochMilli());
        fingerprint.putAll(observations());
        return fingerprint;
    }

    private static Map<String, Object> staticFacts(Repo repo) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("os", System.getProperty("os.name"));
        facts.put("os_release", System.getProperty("os.version"));
        facts.put("os_version", System.getProperty("os.version"));
        facts.put("architecture", System.getProperty("os.arch"));
        facts.put("java", System.getProperty("java.version"));
        String[] cpu = cpuModelAndCores();
        facts.put("cpu_model", cpu[0]);
        facts.put("physical_cores", cpu[1] == null ? null : Long.parseLong(cpu[1]));
        facts.put("logical_cpus", (long) Runtime.getRuntime().availableProcessors());
        facts.put("memory_bytes", totalMemoryBytes());
        boolean cgroupV2 = Files.exists(Paths.get("/sys/fs/cgroup/cgroup.controllers"));
        facts.put("cgroup_version", cgroupV2 ? 2L : Files.exists(Paths.get("/sys/fs/cgroup")) ? 1L : null);
        facts.put("cgroup_cpu_max", readText("/sys/fs/cgroup/cpu.max"));
        facts.put("cgroup_memory_max", readText("/sys/fs/cgroup/memory.max"));
        facts.put("transparent_hugepages", readText("/sys/kernel/mm/transparent_hugepage/enabled"));
        facts.put("git_commit", gitValue(repo, "rev-parse", "HEAD"));
        String dirty = gitValue(repo, "status", "--porcelain");
        facts.put("git_dirty", dirty == null ? null : !dirty.isEmpty());
        facts.put("hostname_hash", hostnameHash());
        return facts;
    }

    /** Fields that change while the host runs: governors, load, SMT, NUMA, swap and injected options. */
    static Map<String, Object> observations() {
        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("cpu_governors", cpuGovernors());
        observed.put("transparent_hugepage_mode", transparentHugepageMode());
        observed.put("smt_control", readText("/sys/devices/system/cpu/smt/control"));
        observed.put("numa_nodes", countIdRanges(readText("/sys/devices/system/node/online")));
        observed.put("online_cpus", countIdRanges(readText("/sys/devices/system/cpu/online")));
        observed.put("swap_devices", swapDeviceCount());
        observed.put("loadavg_1m", loadAverage());
        observed.put("injected_jvm_options", injectedJvmOptions());
        return observed;
    }

    /** Quiet-machine checks for benchmark runs. {@code passed} is false if any check fails. */
    public static Map<String, Object> preflight(Map<String, Object> environment) {
        List<Map<String, Object>> checks = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        Object governors = environment.get("cpu_governors");
        if (governors == null) {
            check(checks, failures, "cpu_governor", "fail", "CPU frequency governor is not observable");
        } else if (List.of("performance").equals(governors)) {
            check(checks, failures, "cpu_governor", "pass", "performance on every CPU");
        } else {
            check(checks, failures, "cpu_governor", "fail",
                    "governors are " + String.join(", ", strings(governors)) + "; expected performance");
        }

        Map<String, Object> injected = Json.asMap(environment.get("injected_jvm_options"));
        if (injected.isEmpty()) {
            check(checks, failures, "jvm_options", "pass", "no JVM option variables are set");
        } else {
            check(checks, failures, "jvm_options", "fail",
                    "environment injects JVM options outside the recorded flags: " + String.join(", ", new java.util.TreeSet<>(injected.keySet())));
        }

        Double load = Json.number(environment.get("loadavg_1m"));
        Long cpus = environment.get("logical_cpus") instanceof Number number ? number.longValue() : null;
        if (load == null || cpus == null || cpus == 0) {
            check(checks, failures, "load", "fail", "1-minute load average or CPU count is not observable");
        } else if (load > MAX_LOAD_FRACTION * cpus) {
            check(checks, failures, "load", "fail",
                    String.format(Locale.ROOT, "1-minute load %.2f exceeds %s x %d CPUs", load, MAX_LOAD_FRACTION, cpus));
        } else {
            check(checks, failures, "load", "pass", String.format(Locale.ROOT, "1-minute load %.2f on %d CPUs", load, cpus));
        }

        Object thp = environment.get("transparent_hugepage_mode");
        if (thp == null) {
            check(checks, failures, "transparent_hugepages", "warn", "THP mode is not observable");
        } else if (List.of("never", "madvise").contains(thp)) {
            check(checks, failures, "transparent_hugepages", "pass", "THP is " + thp);
        } else {
            check(checks, failures, "transparent_hugepages", "warn", "THP is " + thp + "; allocation latency may vary");
        }

        Object smt = environment.get("smt_control");
        if (smt == null) {
            check(checks, failures, "smt", "warn", "SMT state is not observable and is recorded as unknown");
        } else if (List.of("off", "forceoff", "notsupported").contains(smt)) {
            check(checks, failures, "smt", "pass", "SMT is " + smt);
        } else {
            check(checks, failures, "smt", "warn", "SMT is " + smt + "; record it with the results");
        }

        String cgroupCpu = (String) environment.get("cgroup_cpu_max");
        if (cgroupCpu != null && !cgroupCpu.split("\\s+")[0].equals("max")) {
            check(checks, failures, "cgroup_cpu", "warn", "cgroup CPU quota is " + cgroupCpu + "; throttling can distort latency");
        } else {
            check(checks, failures, "cgroup_cpu", "pass", "no cgroup CPU quota");
        }

        Long swap = environment.get("swap_devices") instanceof Number number ? number.longValue() : null;
        if (swap == null) {
            check(checks, failures, "swap", "warn", "swap configuration is not observable");
        } else if (swap > 0) {
            check(checks, failures, "swap", "warn", swap + " swap device(s) configured");
        } else {
            check(checks, failures, "swap", "pass", "no swap devices");
        }

        return Json.map("passed", failures.isEmpty(), "checks", checks, "failures", failures);
    }

    private static void check(List<Map<String, Object>> checks, List<String> failures, String name, String status,
                              String detail) {
        checks.add(Json.map("check", name, "status", status, "detail", detail));
        if ("fail".equals(status)) {
            failures.add(name + ": " + detail);
        }
    }

    static List<String> strings(Object value) {
        List<String> values = new ArrayList<>();
        for (Object item : Json.asList(value)) {
            values.add(String.valueOf(item));
        }
        return values;
    }

    /** Model and physical core count, from the platform's own tools. Either may be null. */
    private static String[] cpuModelAndCores() {
        if (Repo.isWindows()) {
            String model = powershell("(Get-CimInstance Win32_Processor | Select-Object -First 1).Name");
            String cores = powershell("(Get-CimInstance Win32_Processor | Measure-Object -Property NumberOfCores -Sum).Sum");
            return new String[] {model, cores};
        }
        if (Repo.isMacOs()) {
            return new String[] {command("sysctl", "-n", "machdep.cpu.brand_string"), command("sysctl", "-n", "hw.physicalcpu")};
        }
        String cpuinfo = readText("/proc/cpuinfo");
        if (cpuinfo == null) {
            return new String[] {null, null};
        }
        String model = null;
        java.util.Set<String> physical = new java.util.HashSet<>();
        String physicalId = null;
        String coreId = null;
        for (String line : (cpuinfo + "\n").lines().toList()) {
            if (line.isBlank()) {
                if (physicalId != null && coreId != null) {
                    physical.add(physicalId + "/" + coreId);
                }
                physicalId = null;
                coreId = null;
                continue;
            }
            int separator = line.indexOf(':');
            if (separator < 0) {
                continue;
            }
            String key = line.substring(0, separator).strip();
            String value = line.substring(separator + 1).strip();
            if (model == null && (key.equals("model name") || key.equals("Hardware"))) {
                model = value;
            } else if (key.equals("physical id")) {
                physicalId = value;
            } else if (key.equals("core id")) {
                coreId = value;
            }
        }
        if (physicalId != null && coreId != null) {
            physical.add(physicalId + "/" + coreId);
        }
        return new String[] {model, physical.isEmpty() ? null : String.valueOf(physical.size())};
    }

    private static Long totalMemoryBytes() {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean sun) {
            long total = sun.getTotalMemorySize();
            return total > 0 ? total : null;
        }
        return null;
    }

    private static Double loadAverage() {
        double load = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        return load < 0 ? null : load;
    }

    private static List<String> cpuGovernors() {
        if (!Repo.isLinux()) {
            return null;
        }
        java.util.TreeSet<String> governors = new java.util.TreeSet<>();
        try (Stream<Path> paths = Files.list(Paths.get("/sys/devices/system/cpu"))) {
            paths.filter(path -> path.getFileName().toString().matches("cpu[0-9]+"))
                    .map(path -> path.resolve("cpufreq/scaling_governor"))
                    .map(HostEnvironment::readText)
                    .filter(value -> value != null && !value.isEmpty())
                    .forEach(governors::add);
        } catch (IOException exception) {
            return null;
        }
        return governors.isEmpty() ? null : List.copyOf(governors);
    }

    private static String transparentHugepageMode() {
        String text = readText("/sys/kernel/mm/transparent_hugepage/enabled");
        if (text == null) {
            return null;
        }
        for (String token : text.split("\\s+")) {
            if (token.startsWith("[") && token.endsWith("]")) {
                return token.substring(1, token.length() - 1);
            }
        }
        return null;
    }

    private static Long swapDeviceCount() {
        String text = readText("/proc/swaps");
        if (text == null) {
            return null;
        }
        return Math.max(text.lines().count() - 1, 0);
    }

    /** Count of ids in a kernel range list such as {@code 0-3,8-11}, which is eight ids. */
    static Long countIdRanges(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        long count = 0;
        Pattern range = Pattern.compile("(\\d+)-(\\d+)");
        for (String part : text.split(",")) {
            String trimmed = part.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            Matcher match = range.matcher(trimmed);
            if (match.matches()) {
                count += Long.parseLong(match.group(2)) - Long.parseLong(match.group(1)) + 1;
            } else {
                count++;
            }
        }
        return count;
    }

    /** Names of JVM option variables that are set, mapped to a digest of each value (values can hold credentials). */
    static Map<String, Object> injectedJvmOptions() {
        Map<String, Object> injected = new LinkedHashMap<>();
        for (String name : JVM_OPTION_VARIABLES) {
            String value = System.getenv(name);
            if (value != null && !value.isEmpty()) {
                injected.put(name, Digest.sha256(value).substring(0, 16));
            }
        }
        return injected;
    }

    private static String hostnameHash() {
        try {
            return Digest.sha256(InetAddress.getLocalHost().getHostName()).substring(0, 12);
        } catch (IOException exception) {
            return null;
        }
    }

    private static String gitValue(Repo repo, String... arguments) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        Shell.Result result = Shell.run(command, repo.root(), null, java.time.Duration.ofSeconds(20));
        return result.succeeded() ? result.stdout().strip() : null;
    }

    private static String readText(String path) {
        return readText(Paths.get(path));
    }

    private static String readText(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8).strip();
        } catch (IOException exception) {
            return null;
        }
    }

    private static String powershell(String script) {
        return command(POWERSHELL, "-NoProfile", "-NonInteractive", "-Command", script);
    }

    private static String command(String... arguments) {
        Shell.Result result = Shell.run(List.of(arguments), null, null, java.time.Duration.ofSeconds(20));
        String text = result.stdout().strip();
        return result.succeeded() && !text.isEmpty() ? text : null;
    }
}
