package com.palaashatri.bench.b03.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.palaashatri.bench.common.Json;
import com.palaashatri.bench.testkit.AppProcess;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Functional gate for the streaming processor: deterministic event-time windows and a drained consumer. */
class StreamingSmokeTest {
    private static final String APP = "com.palaashatri.bench.b03.app.BenchmarkApp";

    @Test
    void windowsAreDeterministicAndTheConsumerDrains() throws InterruptedException {
        try (AppProcess app = AppProcess.builder(APP).start()) {
            for (double value : new double[] {1.0, 2.0, 3.0}) {
                String body = "{\"key\":\"sensor-a\",\"value\":" + value + ",\"timestamp_ms\":1700000001234}";
                Map<String, Object> accepted = Json.parseObject(app.request("POST", "/api/v1/events", body));
                assertEquals(Boolean.TRUE, accepted.get("accepted"));
            }

            Map<String, Object> lag = null;
            for (int attempt = 0; attempt < 100; attempt++) {
                lag = Json.parseObject(app.get("/api/v1/lag"));
                if (number(lag, "accepted") == 3 && number(lag, "consumed") == 3 && number(lag, "lag") == 0) {
                    break;
                }
                Thread.sleep(20);
            }
            assertEquals(3, number(lag, "accepted"), String.valueOf(lag));
            assertEquals(3, number(lag, "consumed"), String.valueOf(lag));
            assertEquals(0, number(lag, "lag"), String.valueOf(lag));

            Map<String, Object> window = Json.parseObject(app.get("/api/v1/windows/sensor-a"));
            assertEquals(1700000000000L, number(window, "window_start_ms"));
            assertEquals(10000L, number(window, "window_size_ms"));
            assertEquals(3L, number(window, "count"));
            assertEquals(6.0, decimal(window, "sum"), 1e-9);
            assertEquals(2.0, decimal(window, "average"), 1e-9);
            assertEquals(1.0, decimal(window, "minimum"), 1e-9);
            assertEquals(3.0, decimal(window, "maximum"), 1e-9);

            Map<String, Object> health = Json.parseObject(app.get("/health"));
            assertEquals("in-memory", health.get("state_backend"));
            assertEquals(Boolean.FALSE, health.get("external_broker"));
        }
    }

    private static long number(Map<String, Object> object, String key) {
        return ((Number) object.get(key)).longValue();
    }

    private static double decimal(Map<String, Object> object, String key) {
        return ((Number) object.get(key)).doubleValue();
    }
}
