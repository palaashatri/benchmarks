package com.palaashatri.bench.ctl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class HostPreflightTest {
    private static Map<String, Object> quietHost(Map<String, Object> overrides) {
        Map<String, Object> environment = new HashMap<>(Json.map(
                "cpu_governors", List.of("performance"),
                "injected_jvm_options", Map.of(),
                "loadavg_1m", 0.2,
                "logical_cpus", 8L,
                "transparent_hugepage_mode", "madvise",
                "smt_control", "off",
                "cgroup_cpu_max", "max 100000",
                "swap_devices", 0L));
        environment.putAll(overrides);
        return environment;
    }

    private static Set<String> failing(Map<String, Object> report) {
        return ((List<?>) report.get("checks")).stream()
                .map(item -> (Map<?, ?>) item)
                .filter(check -> "fail".equals(check.get("status")))
                .map(check -> (String) check.get("check"))
                .collect(Collectors.toSet());
    }

    private static Map<String, Object> preflight(Map<String, Object> overrides) {
        return HostEnvironment.preflight(quietHost(overrides));
    }

    @Test
    void quietHostPasses() {
        assertTrue((Boolean) preflight(Map.of()).get("passed"));
    }

    @Test
    void nonPerformanceGovernorFails() {
        Map<String, Object> report = preflight(Map.of("cpu_governors", List.of("performance", "powersave")));
        assertFalse((Boolean) report.get("passed"));
        assertTrue(failing(report).contains("cpu_governor"));
    }

    @Test
    void unobservableGovernorFailsRatherThanPassing() {
        Map<String, Object> overrides = new HashMap<>();
        overrides.put("cpu_governors", null);
        assertTrue(failing(preflight(overrides)).contains("cpu_governor"));
    }

    @Test
    void injectedJvmOptionsFailTheHost() {
        assertTrue(failing(preflight(Map.of("injected_jvm_options", Map.of("JAVA_TOOL_OPTIONS", "abc"))))
                .contains("jvm_options"));
    }

    @Test
    void busyHostFailsOnLoad() {
        assertTrue(failing(preflight(Map.of("loadavg_1m", 6.0))).contains("load"));
    }

    @Test
    void unknownLoadFailsRatherThanPassing() {
        Map<String, Object> overrides = new HashMap<>();
        overrides.put("loadavg_1m", null);
        assertTrue(failing(preflight(overrides)).contains("load"));
    }

    @Test
    void warningsDoNotFailTheHost() {
        Map<String, Object> report = preflight(Map.of("transparent_hugepage_mode", "always", "swap_devices", 1L));
        assertTrue((Boolean) report.get("passed"));
        Map<String, String> statuses = ((List<?>) report.get("checks")).stream()
                .map(item -> (Map<?, ?>) item)
                .collect(Collectors.toMap(check -> (String) check.get("check"), check -> (String) check.get("status")));
        assertEquals("warn", statuses.get("transparent_hugepages"));
        assertEquals("warn", statuses.get("swap"));
    }

    @Test
    void cpuRangeListsAreCounted() {
        assertEquals(8L, HostEnvironment.countIdRanges("0-3,8-11"));
        assertEquals(1L, HostEnvironment.countIdRanges("0"));
        assertNull(HostEnvironment.countIdRanges(null));
    }

    @Test
    void injectedValuesAreDigestedNotStored() {
        Map<String, Object> injected = HostEnvironment.injectedJvmOptions();
        for (Object digest : injected.values()) {
            assertEquals(16, String.valueOf(digest).length());
            assertTrue(String.valueOf(digest).matches("[0-9a-f]+"));
        }
    }
}
