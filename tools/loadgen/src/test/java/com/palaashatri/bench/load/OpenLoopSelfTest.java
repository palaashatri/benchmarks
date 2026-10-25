package com.palaashatri.bench.load;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.palaashatri.bench.common.Json;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Open-loop HdrHistogram self-test against a local server, and rejection of a malformed request contract. */
class OpenLoopSelfTest {
    @TempDir
    Path temporary;

    @Test
    void openLoopRunReportsTheContractedSeriesAndCounts() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"status\":\"ok\",\"accepted\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            Path results = temporary.resolve("results.json");
            Path histogram = temporary.resolve("latency.hgrm");
            OpenLoopLoad.main(new String[] {
                "--base-url", "http://127.0.0.1:" + server.getAddress().getPort(),
                "--mix", Path.of("fixtures", "self-test-mix.tsv").toString(),
                "--benchmark", "self-test",
                "--target-rate", "20",
                "--warmup-seconds", "1",
                "--measure-seconds", "2",
                "--threads", "4",
                "--out", results.toString(),
                "--histogram-out", histogram.toString()});

            Map<String, Object> result = Json.parseObject(Files.readString(results, StandardCharsets.UTF_8));
            assertEquals("open-loop", result.get("load_model"));
            assertEquals(Boolean.TRUE, result.get("coordinated_omission_corrected"));
            assertEquals(40L, number(result, "scheduled", "measurement"));
            assertEquals(40L, number(result, "completed", "measurement"));
            assertEquals(0L, number(result, "errors", "measurement"));
            assertTrue(((Number) asMapOf(result, "kpis").get("p99_ms")).doubleValue() >= 0);
            Map<String, Object> series = asMapOf(result, "series");
            assertEquals(1L, ((Number) series.get("bucket_seconds")).longValue());
            assertEquals("response_time", series.get("completed_bucketed_by"));
            assertEquals("intended_send_time", series.get("scheduled_bucketed_by"));
            List<?> scheduled = (List<?>) series.get("scheduled");
            assertEquals(3, scheduled.size(), String.valueOf(series));
            assertEquals(60L, sum(scheduled), String.valueOf(series));
            assertEquals(60L, sum((List<?>) series.get("completed")), String.valueOf(series));
            for (Object p50 : (List<?>) series.get("p50_ms")) {
                assertTrue(p50 == null || ((Number) p50).doubleValue() > 0, String.valueOf(p50));
            }
            assertEquals("self-test", result.get("benchmark"));
            assertFalse(result.containsKey("implementation_tier"));
            assertFalse(result.containsKey("measurement_valid"));
            assertTrue(Files.size(histogram) > 0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void malformedRequestContractIsRejected() {
        Path malformed = Path.of("fixtures", "malformed-mix.tsv");
        assertThrows(RuntimeException.class, () -> OpenLoopLoad.main(new String[] {
            "--base-url", "http://127.0.0.1:9",
            "--mix", malformed.toString(),
            "--out", temporary.resolve("malformed.json").toString()}));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMapOf(Map<String, Object> object, String key) {
        return (Map<String, Object>) object.get(key);
    }

    private static long number(Map<String, Object> result, String outer, String inner) {
        return ((Number) asMapOf(result, outer).get(inner)).longValue();
    }

    private static long sum(List<?> values) {
        long total = 0;
        for (Object value : values) {
            total += value == null ? 0 : ((Number) value).longValue();
        }
        return total;
    }
}
