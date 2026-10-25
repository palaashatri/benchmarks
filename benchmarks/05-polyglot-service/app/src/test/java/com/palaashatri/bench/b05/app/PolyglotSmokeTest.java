package com.palaashatri.bench.b05.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.palaashatri.bench.common.Json;
import com.palaashatri.bench.testkit.AppProcess;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Functional gate: the script sandbox is deterministic, caches compiled scripts, and rejects host access. */
class PolyglotSmokeTest {
    private static final String APP = "com.palaashatri.bench.b05.app.BenchmarkApp";
    private static final String SOURCE = "function score(data){return data.value*2.0;}score(data);";

    @Test
    void sandboxIsDeterministicCachedAndRejectsHostAccess() {
        try (AppProcess app = AppProcess.builder(APP).start()) {
            Map<String, Object> rule = Json.parseObject(app.request("POST", "/api/v1/score/rule/1", "{\"amount\":750}"));
            assertEquals(Boolean.TRUE, rule.get("cache_hit"));
            assertEquals(0.375, Double.parseDouble(String.valueOf(rule.get("result"))), 1e-9);

            String request = "{\"script\":\"" + SOURCE + "\",\"data\":{\"value\":21}}";
            Map<String, Object> first = Json.parseObject(app.request("POST", "/api/v1/score", request));
            Map<String, Object> second = Json.parseObject(app.request("POST", "/api/v1/score", request));
            assertEquals("42", String.valueOf(first.get("result")));
            assertEquals(Boolean.FALSE, first.get("cache_hit"));
            assertTrue(((Number) first.get("compile_ns")).longValue() > 0, String.valueOf(first));
            assertEquals("42", String.valueOf(second.get("result")));
            assertEquals(Boolean.TRUE, second.get("cache_hit"));
            assertEquals(0L, ((Number) second.get("compile_ns")).longValue());

            AppProcess.Response rejected = app.exchange("POST", "/api/v1/score",
                    "{\"script\":\"Packages.java.lang.System.exit(0);\",\"data\":{}}");
            assertEquals(400, rejected.status(), rejected.body());
            assertEquals("invalid_script", Json.parseObject(rejected.body()).get("error"));

            Map<String, Object> cache = Json.parseObject(app.get("/api/v1/scripts"));
            assertEquals(1L, ((Number) cache.get("cache_misses")).longValue());
            assertTrue(((Number) cache.get("cache_hits")).longValue() >= 2, String.valueOf(cache));
            assertEquals(Boolean.FALSE, cache.get("host_class_access"));
            assertTrue(((Number) cache.get("rejected_scripts")).longValue() >= 1, String.valueOf(cache));
        }
    }
}
