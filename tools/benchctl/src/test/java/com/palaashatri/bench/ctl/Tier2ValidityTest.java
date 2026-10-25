package com.palaashatri.bench.ctl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The Tier 2 validity gates: a result is measurement-valid only when every gate passes. */
class Tier2ValidityTest {
    private static final Map<String, Object> PASSING_PREFLIGHT =
            Map.of("passed", true, "checks", List.of(), "failures", List.of());
    private static final Map<String, Object> FAILING_PREFLIGHT =
            Map.of("passed", false, "checks", List.of(), "failures", List.of("cpu_governor: governors are powersave"));

    private static Map<String, Object> repetition(double throughput) {
        Map<String, Object> environment = new LinkedHashMap<>();
        for (String field : Tier2.CRITICAL_ENVIRONMENT_FIELDS) {
            environment.put(field, "fixed");
        }
        Map<String, Object> load = new LinkedHashMap<>();
        load.put("errors", Map.of("measurement", 0));
        load.put("completed", Map.of("measurement", 80));
        load.put("scheduled", Map.of("measurement", 80));
        load.put("kpis", Map.of("throughput", throughput, "p99_ms", 2.0));
        Map<String, Object> telemetry = new LinkedHashMap<>();
        telemetry.put("process", Map.of("sample_count", 20));
        telemetry.put("nmt", Map.of("committed_bytes", 10L * 1024 * 1024));
        telemetry.put("compiler", Map.of("compiled", 100));
        telemetry.put("jfr_conversion", Map.of("returncode", 0));
        telemetry.put("jfr", Map.of("allocation_sample_weight_bytes", 1024));
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("status", "passed");
        document.put("warmup_stability", Map.of("stable_before_measurement", true));
        document.put("requested_collector", "g1");
        document.put("actual_collector", "g1");
        document.put("environment", environment);
        document.put("load", load);
        document.put("telemetry", telemetry);
        return document;
    }

    private static List<Map<String, Object>> repetitions(double... throughputs) {
        List<Map<String, Object>> repetitions = new ArrayList<>();
        for (double throughput : throughputs) {
            repetitions.add(repetition(throughput));
        }
        return repetitions;
    }

    @Test
    void fiveStableRepetitionsAreValid() {
        Tier2.Validity validity = Tier2.validity(repetitions(100, 101, 102, 103, 104), "benchmark", PASSING_PREFLIGHT);
        assertTrue(validity.valid(), validity.reasons().toString());
        assertEquals(List.of(), validity.reasons());
    }

    @Test
    void failedPreflightInvalidatesAnOtherwiseValidRun() {
        Tier2.Validity validity = Tier2.validity(repetitions(100, 101, 102, 103, 104), "benchmark", FAILING_PREFLIGHT);
        assertFalse(validity.valid());
        assertTrue(validity.reasons().stream().anyMatch(reason -> reason.contains("cpu_governor")), validity.reasons().toString());
    }

    @Test
    void missingPreflightIsRecordedAsAReason() {
        Tier2.Validity validity = Tier2.validity(repetitions(100, 101, 102, 103, 104), "benchmark", null);
        assertFalse(validity.valid());
        assertTrue(validity.reasons().contains("host preflight was not recorded"), validity.reasons().toString());
    }

    @Test
    void tooFewRepetitionsAreInvalid() {
        Tier2.Validity validity = Tier2.validity(repetitions(100), "benchmark", null);
        assertFalse(validity.valid());
        assertTrue(validity.reasons().stream().anyMatch(reason -> reason.contains("at least five repetitions")),
                validity.reasons().toString());
    }

    @Test
    void throughputCoefficientOfVariationGate() {
        Tier2.Validity validity = Tier2.validity(repetitions(50, 200, 80, 300, 90), "benchmark", null);
        assertFalse(validity.valid());
        assertTrue(validity.reasons().stream().anyMatch(reason -> reason.contains("coefficient of variation")),
                validity.reasons().toString());
    }
}
