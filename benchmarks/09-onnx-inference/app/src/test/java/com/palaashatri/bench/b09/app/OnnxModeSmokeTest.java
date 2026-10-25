package com.palaashatri.bench.b09.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.palaashatri.bench.common.Json;
import com.palaashatri.bench.testkit.AppProcess;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Functional gate: the reported inference mode is truthful. The ONNX Runtime lane is on the Maven test classpath, so
 * the service reports either its live ONNX session or the Java fallback, and the classify response must agree with
 * the health endpoint about which one answered.
 */
class OnnxModeSmokeTest {
    private static final String APP = "com.palaashatri.bench.b09.app.BenchmarkApp";

    @Test
    void reportedModeMatchesTheSessionThatAnswered() {
        try (AppProcess app = AppProcess.builder(APP).start()) {
            Map<String, Object> health = Json.parseObject(app.get("/api/v1/inference/health"));
            String mode = String.valueOf(health.get("mode"));
            assertTrue(Set.of("onnx-runtime", "java-fallback").contains(mode), String.valueOf(health));
            boolean session = Boolean.TRUE.equals(health.get("onnx_session_active"));
            assertEquals("onnx-runtime".equals(mode), session, String.valueOf(health));

            Map<String, Object> result = Json.parseObject(app.request("POST", "/api/v1/inference/classify",
                    "{\"features\":[5.1,3.5,1.4,0.2]}"));
            assertEquals(mode, result.get("mode"), String.valueOf(result));
            assertEquals(session, Boolean.TRUE.equals(result.get("onnx_session_active")), String.valueOf(result));
        }
    }
}
