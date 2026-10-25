package com.palaashatri.bench.ctl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Lane summaries, the shared gates the lanes use, and the aggregate metric the Tier 2 engine reports. */
class LaneAndStatisticsTest {
    private static List<Double> values(Double... values) {
        return Arrays.asList(values);
    }

    private static Map<String, Object> passingPreflight() {
        return Map.of("passed", true, "checks", List.of(), "failures", List.of());
    }

    private static Map<String, Object> repetition(int index) {
        Map<String, Object> environment = new LinkedHashMap<>();
        for (String field : Tier2.CRITICAL_ENVIRONMENT_FIELDS) {
            environment.put(field, "fixed");
        }
        Map<String, Object> repetition = new LinkedHashMap<>();
        repetition.put("repetition", index);
        repetition.put("status", "passed");
        repetition.put("environment", environment);
        repetition.put("application_runtime", Map.of("pid", 1L, "run_token", "t"));
        return repetition;
    }

    private static List<Map<String, Object>> passingRepetitions(int count) {
        List<Map<String, Object>> repetitions = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            repetitions.add(repetition(index));
        }
        return repetitions;
    }

    @Test
    void missingValuesAreExcludedAndNeverCountedAsZero() {
        Map<String, Object> summary = Lanes.summarize(values(10.0, null, 12.0, null, 14.0), 1);
        assertEquals(3, summary.get("count"));
        assertEquals(12.0, summary.get("median"));
        assertEquals(10.0, summary.get("minimum"));
    }

    @Test
    void noKnownValuesGiveNullStatistics() {
        Map<String, Object> summary = Lanes.summarize(values(null, null), 1);
        assertEquals(0, summary.get("count"));
        assertNull(summary.get("median"));
        assertNull(Json.asMap(summary.get("bootstrap_mean_ci")).get("low"));
    }

    @Test
    void medianAbsoluteDeviationComesFromTheSharedStatistics() {
        Map<String, Object> summary = Lanes.summarize(values(1.0, 2.0, 3.0, 4.0, 100.0), 1);
        assertEquals(1.0, summary.get("median_absolute_deviation"));
        assertFalse(((List<?>) summary.get("outliers")).isEmpty(), "the extreme value must be reported, not removed");
        assertEquals(5, summary.get("count"));
    }

    @Test
    void aLaneRefusesABenchmarkForATierOneWorkloadWhateverTheGates() {
        Map<String, Object> envelope = Lanes.envelope("07-coldstart-suite", "startup", "tier-1", "benchmark",
                passingRepetitions(5), passingPreflight(), passingPreflight(), List.of(), List.of(), Map.of(), Map.of());
        assertEquals(false, envelope.get("measurement_valid"));
        assertTrue(((List<?>) envelope.get("invalid_reasons")).contains(Lanes.TIER_ONE_POLICY),
                String.valueOf(envelope.get("invalid_reasons")));
    }

    @Test
    void aLaneIsValidOnlyWhenEveryGatePasses() {
        Map<String, Object> envelope = Lanes.envelope("08-etl-batch", "jobs", "tier-2", "benchmark",
                passingRepetitions(5), passingPreflight(), passingPreflight(), List.of(), List.of(), Map.of(), Map.of());
        assertEquals(true, envelope.get("measurement_valid"), String.valueOf(envelope.get("invalid_reasons")));
    }

    @Test
    void aFailedRepetitionOrAFailedPreflightInvalidatesTheLane() {
        List<Map<String, Object>> repetitions = passingRepetitions(5);
        repetitions.get(2).put("status", "failed");
        Map<String, Object> failed = Lanes.envelope("08-etl-batch", "jobs", "tier-2", "benchmark", repetitions,
                passingPreflight(), passingPreflight(), List.of(), List.of(), Map.of(), Map.of());
        assertEquals(false, failed.get("measurement_valid"));
        assertTrue(((List<?>) failed.get("invalid_reasons")).contains("one or more repetitions failed"));

        Map<String, Object> hot = Map.of("passed", false, "checks", List.of(), "failures", List.of("load: busy"));
        Map<String, Object> busy = Lanes.envelope("08-etl-batch", "jobs", "tier-2", "benchmark", passingRepetitions(5),
                passingPreflight(), hot, List.of(), List.of(), Map.of(), Map.of());
        assertTrue(((List<?>) busy.get("invalid_reasons")).contains("host failed preflight: after run: load: busy")
                || ((List<?>) busy.get("invalid_reasons")).stream().anyMatch(reason -> String.valueOf(reason).contains("busy")),
                String.valueOf(busy.get("invalid_reasons")));
    }

    @Test
    void theSpreadGateFlagsAWideSpreadAndAcceptsATightOne() {
        assertNull(Tier2.spreadReason("metric", values(100.0, 101.0, 99.0, 100.5, 100.2)));
        assertTrue(Tier2.spreadReason("metric", values(10.0, 20.0, 30.0, 40.0, 50.0)).contains("coefficient of variation"));
    }

    @Test
    void valuesAtReadsNestedNumbersAndNullsMissingOnes() {
        List<Map<String, Object>> runs = List.of(
                Map.of("metrics", Map.of("records_per_second", Map.of("median", 4.5))),
                Map.of("metrics", Map.of()));
        assertEquals(Arrays.asList(4.5, null), Lanes.valuesAt(runs, "metrics", "records_per_second", "median"));
    }

    @Test
    void aMetricWithNoRepetitionsHasUnknownSpread() {
        Map<String, Object> metric = Tier2.metric(List.of(5.0), 1);
        assertEquals(1, metric.get("count"));
        assertEquals(5.0, metric.get("median"));
        assertNull(metric.get("standard_deviation"));
        assertNull(metric.get("coefficient_of_variation"));
    }

    @Test
    void multipleSamplesReportSpread() {
        Map<String, Object> metric = Tier2.metric(List.of(4.0, 6.0), 1);
        assertEquals(Math.sqrt(2), (Double) metric.get("standard_deviation"), 1e-9);
        assertEquals(0.2828, (Double) metric.get("coefficient_of_variation"), 1e-4);
    }

    @Test
    void emptyValuesAreUnknownNotZero() {
        Map<String, Object> metric = Tier2.metric(List.of(), 1);
        assertEquals(0, metric.get("count"));
        assertNull(metric.get("median"));
        assertNull(metric.get("standard_deviation"));
    }

    @Test
    void aFailedRepetitionIsNeverZeroedInTheSpreadGate() {
        assertNull(Tier2.spreadReason("metric", values(100.0, null, 101.0, 99.0, 100.5)));
    }
}
