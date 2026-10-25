package com.palaashatri.bench.ctl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Lane summaries and envelopes, and the aggregate metric the Tier 2 engine reports for each measured quantity. */
class LaneAndStatisticsTest {
    private static List<Double> values(Double... values) {
        return Arrays.asList(values);
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
    void everyLaneEnvelopeIsLabelledUncontrolledCalibration() {
        Map<String, Object> document = Lanes.envelope("07-coldstart-suite", "startup", Map.of(), List.of(), List.of(),
                Map.of(), Map.of());
        assertEquals("calibration", document.get("run_kind"));
        assertEquals(Boolean.FALSE, document.get("measurement_valid"));
        assertTrue(((List<?>) document.get("invalid_reasons")).contains(Lanes.UNCONTROLLED_HOST));
    }

    @Test
    void envelopesSerialiseWithoutNonFiniteNumbers() {
        Map<String, Object> document = Lanes.envelope("08-etl-batch", "jobs", Map.of(), List.of(), List.of(), Map.of(),
                Map.of("metrics", Lanes.summarize(values(1.0, 2.0), 1)));
        assertFalse(Json.write(document).contains("NaN"));
        assertFalse(Json.write(document).contains("Infinity"));
    }

    @Test
    void aSingleSampleHasUnknownSpread() {
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
}
