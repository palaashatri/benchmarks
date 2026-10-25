package com.palaashatri.bench.ctl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CompareTest {
    private static Map<String, Object> benchmark(boolean valid, double median, double low, double high) {
        return Json.map(
                "benchmark", "01-fintech-ledger",
                "run_kind", "benchmark",
                "measurement_valid", valid,
                "aggregate", Json.map("throughput", Json.map(
                        "median", median, "bootstrap_mean_ci", Json.map("low", low, "high", high))));
    }

    @Test
    void compareRequiresValidBenchmarkResults() {
        Map<String, Object> invalid = Compare.compare(
                Json.map("benchmark", "01-fintech-ledger", "run_kind", "benchmark", "measurement_valid", false,
                        "kpis", Json.map("throughput", 100)),
                Json.map("benchmark", "01-fintech-ledger", "run_kind", "benchmark", "measurement_valid", true,
                        "kpis", Json.map("throughput", 110)));
        assertEquals("invalid", invalid.get("outcome"));
    }

    @Test
    void compareUsesConfidenceIntervalOverlap() {
        Map<String, Object> left = benchmark(true, 100.0, 95.0, 105.0);
        assertEquals("inconclusive", Compare.compare(left, benchmark(true, 103.0, 98.0, 108.0)).get("outcome"));
        assertEquals("improvement", Compare.compare(left, benchmark(true, 130.0, 120.0, 140.0)).get("outcome"));
    }
}
