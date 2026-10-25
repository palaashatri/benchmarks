package com.palaashatri.bench.ctl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StatsTest {
    private static List<Double> values(double... values) {
        return Arrays.stream(values).boxed().toList();
    }

    private static List<Double> nullable(Double... values) {
        return Arrays.asList(values);
    }

    @Test
    void modifiedZFlagsTheExtremePointAndKeepsTheRest() {
        List<Map<String, Object>> flagged = Stats.modifiedZOutliers(values(10.0, 10.2, 9.9, 10.1, 10.0, 25.0));
        assertEquals(List.of(5), flagged.stream().map(item -> item.get("index")).toList());
        assertEquals("modified_z", flagged.get(0).get("method"));
    }

    @Test
    void zeroMadReportsNothingRatherThanEverything() {
        assertTrue(Stats.modifiedZOutliers(values(5.0, 5.0, 5.0, 9.0)).isEmpty());
    }

    @Test
    void iqrFencesNeedFourValues() {
        assertTrue(Stats.iqrOutliers(values(1.0, 100.0, 2.0)).isEmpty());
        assertEquals(List.of(4), Stats.iqrOutliers(values(1.0, 1.1, 1.2, 1.3, 50.0)).stream()
                .map(item -> item.get("index")).toList());
    }

    @Test
    void describeOutliersUsesRepetitionLabelsAndNeverDropsValues() {
        Map<String, Object> report = Stats.describeOutliers("throughput", values(10.0, 10.1, 9.9, 10.0, 40.0),
                List.of(1L, 2L, 3L, 4L, 5L));
        assertEquals(5, report.get("count"));
        assertTrue(((List<?>) report.get("outliers")).stream()
                .anyMatch(item -> Long.valueOf(5).equals(((Map<?, ?>) item).get("repetition"))));
    }

    @Test
    void aSingleSampleHasNoSpread() {
        assertNull(Stats.coefficientOfVariation(values(3.0)));
        assertNull(Stats.medianAbsoluteDeviation(List.of()));
    }

    @Test
    void bootstrapIntervalIsNullWithoutTwoSamplesAndReproducible() {
        Map<String, Object> single = Stats.bootstrapInterval(values(1.0), 1);
        assertNull(single.get("low"));
        assertNull(single.get("high"));
        List<Double> sample = values(9.0, 10.0, 11.0, 10.5, 9.5);
        assertEquals(Stats.bootstrapInterval(sample, 7), Stats.bootstrapInterval(sample, 7));
    }

    @Test
    void rampThenPlateauIsDetectedAtThePlateau() {
        List<Double> series = values(5, 20, 50, 80);
        List<Double> plateau = new java.util.ArrayList<>(series);
        for (int repeat = 0; repeat < 3; repeat++) {
            plateau.addAll(values(100, 101, 99, 100, 100, 102));
        }
        assertEquals(4, Stats.findStableWindow(plateau, Stats.STABLE_WINDOW, Stats.STABLE_MAX_CV));
    }

    @Test
    void neverStableSeriesReturnsNull() {
        assertNull(Stats.findStableWindow(values(1, 100, 2, 90, 3, 80, 4, 70, 5), Stats.STABLE_WINDOW, Stats.STABLE_MAX_CV));
    }

    @Test
    void zeroBucketsNeverCountAsStable() {
        assertNull(Stats.findStableWindow(values(0, 0, 0, 0, 0, 0), Stats.STABLE_WINDOW, Stats.STABLE_MAX_CV));
    }

    @Test
    void emptyBucketsAreNotStableAndDoNotCrash() {
        assertEquals(2, Stats.findStableWindow(nullable(null, null, 5.0, 5.0, 5.0, 5.0, 5.0), 5, Stats.STABLE_MAX_CV));
        assertNull(Stats.findStableWindow(nullable(null, 5.0, null, 5.0, null, 5.0), 5, Stats.STABLE_MAX_CV));
    }

    @Test
    void warmupMustContainTheStableWindow() {
        List<Double> series = values(5, 20, 50, 80, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100);
        Map<String, Object> inside = Stats.warmupStability(series, 10);
        assertEquals(Boolean.TRUE, inside.get("stable_before_measurement"));
        assertEquals(4, inside.get("stable_at_second"));
        assertFalse(Boolean.TRUE.equals(Stats.warmupStability(series, 8).get("stable_before_measurement")));
    }
}
