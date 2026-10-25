package com.palaashatri.bench.ctl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The framed-tcp and startup load shapes: how the chat driver's and the start timings' results enter the gates. */
class ChatAndStartupShapeTest {
    private static Tier2.Options options() {
        return new Tier2.Options(null, null, "g1", 100, 6, 6, 8, 1, 424242L, 512, "calibration", "06-massive-chat-loom",
                null, false, List.of());
    }

    /** A driver envelope's results for a clean 6 s measurement window at 100 messages per second. */
    private static Map<String, Object> results() {
        Map<String, Object> results = new LinkedHashMap<>();
        results.put("messages_scheduled_in_window", 600);
        results.put("messages_sent_in_window", 600);
        results.put("deliveries_in_window", 7200);
        results.put("error_frames", 0);
        results.put("conserved", true);
        results.put("delivery_latency_ms", Map.of("p50", 2.5, "p99", 4.5, "max", 6.0, "mean", 2.7));
        results.put("series", Map.of("bucket_seconds", 1));
        return results;
    }

    @Test
    void aCleanChatWindowMapsToCompletedMessagesAndDeliveryThroughput() {
        Map<String, Object> load = Tier2.chatSummary("06-massive-chat-loom", results(), options());
        assertEquals(600L, ((Number) Json.asMap(load.get("scheduled")).get("measurement")).longValue());
        assertEquals(600L, ((Number) Json.asMap(load.get("completed")).get("measurement")).longValue());
        assertEquals(0L, ((Number) Json.asMap(load.get("errors")).get("measurement")).longValue());
        assertEquals(1200.0, ((Number) Json.asMap(load.get("kpis")).get("throughput")).doubleValue(), 1e-9);
    }

    @Test
    void anUnsentMessageLeavesCompletedBehindScheduled() {
        Map<String, Object> results = results();
        results.put("messages_sent_in_window", 590);
        Map<String, Object> load = Tier2.chatSummary("06-massive-chat-loom", results, options());
        assertEquals(600L, ((Number) Json.asMap(load.get("scheduled")).get("measurement")).longValue());
        assertEquals(590L, ((Number) Json.asMap(load.get("completed")).get("measurement")).longValue());
    }

    @Test
    void aConservationFailureCountsAsOneError() {
        Map<String, Object> results = results();
        results.put("conserved", false);
        Map<String, Object> load = Tier2.chatSummary("06-massive-chat-loom", results, options());
        assertEquals(1L, ((Number) Json.asMap(load.get("errors")).get("measurement")).longValue());
    }

    private static Map<String, Object> startupRepetition(double cvDefault, double cvNoCds) {
        Map<String, Object> modes = new LinkedHashMap<>();
        modes.put("default", Map.of("samples_ms", List.of(500.0), "errors", 0, "median_ms", 500.0, "cv", cvDefault));
        modes.put("no-cds", Map.of("samples_ms", List.of(580.0), "errors", 0, "median_ms", 580.0, "cv", cvNoCds));
        Map<String, Object> load = new LinkedHashMap<>();
        load.put("errors", Map.of("measurement", 0));
        load.put("completed", Map.of("measurement", 20));
        load.put("scheduled", Map.of("measurement", 20));
        load.put("startup", Map.of("field", "time_to_health_ms", "modes", modes));
        Map<String, Object> repetition = new LinkedHashMap<>();
        repetition.put("status", "passed");
        repetition.put("load_shape", "startup");
        repetition.put("warmup_stability", null);
        repetition.put("load", load);
        repetition.put("telemetry", Map.of("process", Map.of("sample_count", 20), "nmt", Map.of("committed_bytes", 1L),
                "compiler", Map.of("compiled", 1), "jfr_conversion", Map.of("returncode", 0),
                "jfr", Map.of("allocation_sample_weight_bytes", 1)));
        repetition.put("requested_collector", "default");
        repetition.put("actual_collector", "default");
        Map<String, Object> environment = new LinkedHashMap<>();
        for (String field : Tier2.CRITICAL_ENVIRONMENT_FIELDS) {
            environment.put(field, "fixed");
        }
        repetition.put("environment", environment);
        return repetition;
    }

    private static Tier2.Validity validity(Map<String, Object> repetition) {
        List<Map<String, Object>> repetitions = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            repetitions.add(repetition);
        }
        return Tier2.validity(repetitions, "benchmark", Map.of("passed", true, "checks", List.of(), "failures", List.of()));
    }

    @Test
    void aStartupRepetitionWithNoWarmupCheckIsNotReportedAsUnstable() {
        Tier2.Validity validity = validity(startupRepetition(0.02, 0.03));
        assertFalse(validity.reasons().stream().anyMatch(reason -> reason.contains("warm-up")), validity.reasons().toString());
    }

    @Test
    void aModeWhoseStartsVaryTooMuchIsReportedByMode() {
        Tier2.Validity validity = validity(startupRepetition(0.02, 0.25));
        assertTrue(validity.reasons().stream().anyMatch(reason -> reason.contains("startup times for mode no-cds")),
                validity.reasons().toString());
    }
}
