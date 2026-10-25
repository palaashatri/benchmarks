package com.palaashatri.bench.ctl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The jobs load shape: job records summarised in the load generator's shape, with the same error and phase rules. */
class JobSummaryTest {
    private static Map<String, Object> record(double intended, String status, boolean digest, double sojournMs) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("intended_s", intended);
        record.put("status", status);
        record.put("digest_match", digest);
        record.put("sojourn_ms", sojournMs);
        return record;
    }

    /** One warm-up second, then three measurement seconds: five jobs with one warm-up failure and one digest mismatch. */
    private static List<Map<String, Object>> records() {
        List<Map<String, Object>> records = new ArrayList<>();
        records.add(record(0.1, "completed", true, 100));
        records.add(record(0.5, "failed", false, 0));
        records.add(record(1.2, "completed", true, 300));
        records.add(record(2.4, "completed", true, 500));
        records.add(record(3.0, "completed", false, 900));
        return records;
    }

    @Test
    void measurementPhaseCountsOnlyItsOwnCompletionsAndErrors() {
        Map<String, Object> result = Tier2.jobSummary("08-etl-batch", records(), 1.0, 1, 3);
        Map<String, Object> scheduled = Json.asMap(result.get("scheduled"));
        Map<String, Object> completed = Json.asMap(result.get("completed"));
        Map<String, Object> errors = Json.asMap(result.get("errors"));
        assertEquals(2L, ((Number) scheduled.get("warmup")).longValue());
        assertEquals(3L, ((Number) scheduled.get("measurement")).longValue());
        assertEquals(1L, ((Number) completed.get("warmup")).longValue());
        assertEquals(2L, ((Number) completed.get("measurement")).longValue());
        assertEquals(1L, ((Number) errors.get("warmup")).longValue());
        assertEquals(1L, ((Number) errors.get("measurement")).longValue(),
                "a digest mismatch is an error, not a completion");
    }

    @Test
    void throughputAndLatencyComeFromMeasurementCompletionsOnly() {
        Map<String, Object> kpis = Json.asMap(Tier2.jobSummary("08-etl-batch", records(), 1.0, 1, 3).get("kpis"));
        assertEquals(2.0 / 3.0, ((Number) kpis.get("throughput")).doubleValue(), 1e-9);
        assertEquals(300.0, ((Number) kpis.get("p50_ms")).doubleValue(), 1e-9);
        assertEquals(500.0, ((Number) kpis.get("max_ms")).doubleValue(), 1e-9);
    }

    @Test
    void seriesBucketsScheduleByIntendedTimeAndCompletionsByFinishTime() {
        Map<String, Object> series = Json.asMap(Tier2.jobSummary("08-etl-batch", records(), 1.0, 1, 3).get("series"));
        assertEquals(List.of(2L, 1L, 1L, 1L), longs(series.get("scheduled")));
        assertEquals(List.of(1L, 1L, 1L, 0L), longs(series.get("completed")));
        assertEquals(100.0, ((Number) ((List<?>) series.get("p50_ms")).get(0)).doubleValue(), 1e-9);
        assertNull(((List<?>) series.get("p50_ms")).get(3), "a bucket with no completions has no latency");
    }

    private static List<Long> longs(Object values) {
        List<Long> result = new ArrayList<>();
        for (Object value : (List<?>) values) {
            result.add(((Number) value).longValue());
        }
        return result;
    }
}
