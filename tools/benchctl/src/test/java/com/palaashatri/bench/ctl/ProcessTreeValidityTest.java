package com.palaashatri.bench.ctl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The process-tree gates: children the gateway reports must be observed, every observed child needs a GC log, and a
 * child the engine saw must also have its native memory and a converted flight recording, or be reported as exited.
 */
class ProcessTreeValidityTest {
    /** One child's captures: committed native memory (null when missing), and the JFR conversion return code. */
    private static Map<String, Object> child(Long committedBytes, int conversionCode) {
        Map<String, Object> nmt = new LinkedHashMap<>();
        nmt.put("committed_bytes", committedBytes);
        Map<String, Object> child = new LinkedHashMap<>();
        child.put("nmt", nmt);
        child.put("capture_error", null);
        child.put("jfr_conversion", Map.of("returncode", conversionCode));
        child.put("jfr", Map.of("allocation_sample_weight_bytes", 512));
        return child;
    }

    /** A repetition that passes every non-tree gate, so each test sees only the process-tree reasons. */
    private static Map<String, Object> repetition(List<Long> observed, List<Long> logged, List<Long> reported,
                                                  Map<String, Object> childTelemetry) {
        Map<String, Object> environment = new LinkedHashMap<>();
        for (String field : Tier2.CRITICAL_ENVIRONMENT_FIELDS) {
            environment.put(field, "fixed");
        }
        Map<String, Object> load = new LinkedHashMap<>();
        load.put("errors", Map.of("measurement", 0));
        load.put("completed", Map.of("measurement", 80));
        load.put("scheduled", Map.of("measurement", 80));
        load.put("kpis", Map.of("throughput", 50.0, "p99_ms", 2.0));
        load.put("phases", Map.of("measure_s", 2.0));
        Map<String, Object> processTree = new LinkedHashMap<>();
        processTree.put("observed_child_pids", observed);
        processTree.put("gc_log_pids", logged);
        processTree.put("child_telemetry", childTelemetry);
        Map<String, Object> telemetry = new LinkedHashMap<>();
        telemetry.put("process", Map.of("sample_count", 20));
        telemetry.put("nmt", Map.of("committed_bytes", 10L * 1024 * 1024));
        telemetry.put("compiler", Map.of("compiled", 100));
        telemetry.put("jfr_conversion", Map.of("returncode", 0));
        telemetry.put("jfr", Map.of("allocation_sample_weight_bytes", 1024));
        telemetry.put("process_tree", processTree);
        List<Map<String, Object>> children = new ArrayList<>();
        for (long pid : reported) {
            children.add(Map.of("pid", pid));
        }
        Map<String, Object> repetition = new LinkedHashMap<>();
        repetition.put("status", "passed");
        repetition.put("warmup_stability", Map.of("stable_before_measurement", true));
        repetition.put("requested_collector", "g1");
        repetition.put("actual_collector", "g1");
        repetition.put("environment", environment);
        repetition.put("load", load);
        repetition.put("telemetry", telemetry);
        repetition.put("child_processes_reported", children);
        return repetition;
    }

    /** A repetition in which every reported child has healthy captures. */
    private static Map<String, Object> repetition(List<Long> observed, List<Long> logged, List<Long> reported) {
        Map<String, Object> childTelemetry = new LinkedHashMap<>();
        for (long pid : reported) {
            childTelemetry.put(Long.toString(pid), child(5L * 1024 * 1024, 0));
        }
        return repetition(observed, logged, reported, childTelemetry);
    }

    private static Tier2.Validity validity(Map<String, Object> repetition) {
        List<Map<String, Object>> repetitions = List.of(repetition, repetition, repetition, repetition, repetition);
        return Tier2.validity(repetitions, "benchmark", Map.of("passed", true, "checks", List.of(), "failures", List.of()));
    }

    @Test
    void aConsistentTreeAddsNoProcessTreeReasonOrWarning() {
        Tier2.Validity validity = validity(repetition(List.of(11L, 12L), List.of(11L, 12L), List.of(11L, 12L)));
        assertFalse(validity.reasons().stream().anyMatch(reason -> reason.contains("child")), validity.reasons().toString());
        assertFalse(validity.warnings().stream().anyMatch(warning -> warning.contains("child JVM")),
                validity.warnings().toString());
    }

    @Test
    void aChildTheGatewayReportsButTheSamplerNeverSawIsReported() {
        Tier2.Validity validity = validity(repetition(List.of(11L), List.of(11L), List.of(11L, 99L)));
        assertTrue(validity.reasons().contains("gateway-reported child pid 99 was not observed by the process sampler"),
                validity.reasons().toString());
    }

    @Test
    void anObservedChildWithoutItsGcLogIsReported() {
        Tier2.Validity validity = validity(repetition(List.of(11L, 12L), List.of(11L), List.of()));
        assertTrue(validity.reasons().contains("child JVM pid 12 has no GC log"), validity.reasons().toString());
    }

    @Test
    void aChildWithNoCaptureIsReported() {
        Tier2.Validity validity = validity(repetition(List.of(11L, 12L), List.of(11L, 12L), List.of(11L)));
        assertTrue(validity.reasons().contains("child JVM pid 12 has no native-memory summary"), validity.reasons().toString());
    }

    @Test
    void aChildRetiredMidRunIsHeldToTheSameStandardAsOneThatStayed() {
        // Pid 12 was retired before the end of the run: the gateway no longer reports it, but it wrote its own capture.
        Map<String, Object> childTelemetry = new LinkedHashMap<>();
        childTelemetry.put("11", child(5L * 1024 * 1024, 0));
        childTelemetry.put("12", child(5L * 1024 * 1024, 0));
        Tier2.Validity validity = validity(repetition(List.of(11L, 12L), List.of(11L, 12L), List.of(11L), childTelemetry));
        assertFalse(validity.reasons().stream().anyMatch(reason -> reason.contains("child")), validity.reasons().toString());
    }

    @Test
    void aChildWithoutNativeMemoryIsReported() {
        Map<String, Object> childTelemetry = new LinkedHashMap<>();
        childTelemetry.put("11", child(null, 0));
        Tier2.Validity validity = validity(repetition(List.of(11L), List.of(11L), List.of(11L), childTelemetry));
        assertTrue(validity.reasons().contains("child JVM pid 11 has no native-memory summary"), validity.reasons().toString());
    }

    @Test
    void aChildWhoseRecordingDidNotConvertIsReported() {
        Map<String, Object> childTelemetry = new LinkedHashMap<>();
        childTelemetry.put("11", child(5L * 1024 * 1024, 1));
        Tier2.Validity validity = validity(repetition(List.of(11L), List.of(11L), List.of(11L), childTelemetry));
        assertTrue(validity.reasons().contains("child JVM pid 11 has no usable flight recording"), validity.reasons().toString());
    }

    @Test
    void aProcessTreeWithNoChildAtAllIsReported() {
        Tier2.Validity validity = validity(repetition(List.of(), List.of(), List.of()));
        assertTrue(validity.reasons().contains("no child JVM process was observed for a process-tree workload"),
                validity.reasons().toString());
    }

    @Test
    void treeNativeMemoryAndAllocationSumEveryJvmAndAreUnknownWhenOneIsMissing() {
        // The gateway has 10 MiB committed and 1024 bytes of allocation samples over 2 s; the child has 5 MiB and 512.
        Map<String, Object> whole = repetition(List.of(11L), List.of(11L), List.of(11L));
        assertEquals(15.0, Tier2.treeNativeMemoryBytes(whole).doubleValue() / 1024 / 1024, 1e-9);
        assertEquals(1536.0 / 2 / 1024 / 1024, Tier2.treeAllocationRate(whole).doubleValue(), 1e-12);

        Map<String, Object> childTelemetry = new LinkedHashMap<>();
        childTelemetry.put("11", child(null, 0));
        Map<String, Object> missing = repetition(List.of(11L), List.of(11L), List.of(11L), childTelemetry);
        assertNull(Tier2.treeNativeMemoryBytes(missing));
    }

    @Test
    void childPidsAreReadFromNumbersOrFromReportedChildMaps() {
        assertTrue(Tier2.pidsOf(List.of(3, Map.of("pid", 7L))).containsAll(List.of(3L, 7L)));
        assertTrue(Tier2.pidsOf("not-a-list").isEmpty());
    }
}
