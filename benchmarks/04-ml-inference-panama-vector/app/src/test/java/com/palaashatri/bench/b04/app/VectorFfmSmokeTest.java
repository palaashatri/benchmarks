package com.palaashatri.bench.b04.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.palaashatri.bench.common.Json;
import com.palaashatri.bench.testkit.AppProcess;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Functional gate: the scalar and vectorised inference paths agree on the same input. */
class VectorFfmSmokeTest {
    private static final String APP = "com.palaashatri.bench.b04.app.BenchmarkApp";
    private static final String FEATURES =
            "{\"features\":[0.1,0.2,0.3,0.4,0.5,0.6,0.7,0.8,0.9,1.0,0.1,0.2,0.3,0.4,0.5,0.6]}";

    @Test
    void scalarAndVectorPathsAgree() {
        try (AppProcess app = AppProcess.builder(APP)
                .jvmOptions("--enable-preview", "--add-modules=jdk.incubator.vector")
                .start()) {
            Map<String, Object> scalar = Json.parseObject(app.request("POST", "/api/v1/inference/scalar", FEATURES));
            Map<String, Object> simd = Json.parseObject(app.request("POST", "/api/v1/inference", FEATURES));
            assertEquals(scalar.get("class"), simd.get("class"), () -> scalar + " vs " + simd);
            double difference = Math.abs(((Number) scalar.get("confidence")).doubleValue()
                    - ((Number) simd.get("confidence")).doubleValue());
            assertEquals(true, difference < 1e-4, () -> scalar + " vs " + simd);
            assertEquals("scalar", scalar.get("method"));
            assertEquals("simd", simd.get("method"));
        }
    }
}
