package com.palaashatri.bench.ctl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The process-tree gates: children the gateway reports must be observed, and every observed child needs a GC log. */
class ProcessTreeValidityTest {
    /** A repetition that passes every non-tree gate, so each test sees only the process-tree reasons. */
    private static Map<String, Object> repetition(List<Long> observed, List<Long> logged, List<Long> reported) {
        Map<String, Object> environment = new LinkedHashMap<>();
        for (String field : Tier2.CRITICAL_ENVIRONMENT_FIELDS) {
            environment.put(field, "fixed");
        }
        Map<String, Object> load = new LinkedHashMap<>();
        load.put("errors", Map.of("measurement", 0));
        load.put("completed", Map.of("measurement", 80));
        load.put("scheduled", Map.of("measurement", 80));
        load.put("kpis", Map.of("throughput", 50.0, "p99_ms", 2.0));
        Map<String, Object> processTree = new LinkedHashMap<>();
        processTree.put("observed_child_pids", observed);
        processTree.put("gc_log_pids", logged);
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

    private static Tier2.Validity validity(Map<String, Object> repetition) {
        List<Map<String, Object>> repetitions = List.of(repetition, repetition, repetition, repetition, repetition);
        return Tier2.validity(repetitions, "benchmark", Map.of("passed", true, "checks", List.of(), "failures", List.of()));
    }

    @Test
    void aConsistentTreeAddsNoProcessTreeReasonAndNamesTheCollectorCaveat() {
        Tier2.Validity validity = validity(repetition(List.of(11L, 12L), List.of(11L, 12L), List.of(11L, 12L)));
        assertFalse(validity.reasons().stream().anyMatch(reason -> reason.contains("child")), validity.reasons().toString());
        assertTrue(validity.warnings().stream().anyMatch(warning -> warning.contains("child JVM NMT and JFR")),
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
    void aProcessTreeWithNoChildAtAllIsReported() {
        Tier2.Validity validity = validity(repetition(List.of(), List.of(), List.of()));
        assertTrue(validity.reasons().contains("no child JVM process was observed for a process-tree workload"),
                validity.reasons().toString());
    }

    @Test
    void childPidsAreReadFromNumbersOrFromReportedChildMaps() {
        assertTrue(Tier2.pidsOf(List.of(3, Map.of("pid", 7L))).containsAll(List.of(3L, 7L)));
        assertTrue(Tier2.pidsOf("not-a-list").isEmpty());
    }
}
