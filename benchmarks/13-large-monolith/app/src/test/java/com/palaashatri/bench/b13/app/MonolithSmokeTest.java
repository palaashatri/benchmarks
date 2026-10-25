package com.palaashatri.bench.b13.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.palaashatri.bench.common.Json;
import com.palaashatri.bench.testkit.AppProcess;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Functional gate: the generated graph is wired completely and every traversal reproduces the reference result. */
class MonolithSmokeTest {
    private static final String APP = "com.palaashatri.bench.b13.app.BenchmarkApp";

    @Test
    void graphIsWiredAndTraversalsMatchTheReference() {
        try (AppProcess app = AppProcess.builder(APP).start()) {
            Map<String, Object> graph = Json.parseObject(app.get("/api/v1/monolith/graph"));
            long components = number(graph, "components");
            assertEquals(number(graph, "modules") * number(graph, "depth"), components, String.valueOf(graph));
            assertTrue(components >= 1000, String.valueOf(graph));
            assertEquals(components, number(graph, "wired_components"), String.valueOf(graph));
            assertEquals(graph.get("expected_result"), graph.get("reference_result"), String.valueOf(graph));

            for (int traversal = 0; traversal < 10; traversal++) {
                Map<String, Object> work = Json.parseObject(app.get("/api/v1/monolith/work"));
                assertEquals(components, number(work, "components_visited"), String.valueOf(work));
                assertEquals(graph.get("expected_result"), work.get("result"), String.valueOf(work));
            }
            assertEquals(components, number(Json.parseObject(app.get("/health")), "wired_components"));

            Map<String, Object> warmup = Json.parseObject(app.get("/api/v1/monolith/warmup/status"));
            assertEquals(components, number(warmup, "components"), String.valueOf(warmup));
            assertTrue(number(warmup, "loaded_classes") >= components, String.valueOf(warmup));
            assertTrue(number(warmup, "startup_ms") >= 0, String.valueOf(warmup));
            assertEquals(10L, number(warmup, "business_operations"), String.valueOf(warmup));

            assertTrue(app.get("/metrics").contains("monolith_components"));
        }
    }

    private static long number(Map<String, Object> object, String key) {
        return ((Number) object.get(key)).longValue();
    }
}
