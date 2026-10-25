package com.palaashatri.bench.b01.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.palaashatri.bench.common.Json;
import com.palaashatri.bench.testkit.AppClasspath;
import com.palaashatri.bench.testkit.AppProcess;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Functional gate for the harness: it drives the application and writes a smoke envelope that never claims a
 * measurement. Build the application first, so its classes and dependency list exist.
 */
class HarnessSmokeTest {
    private static final String APP = "com.palaashatri.bench.b01.app.BenchmarkApp";

    @TempDir
    Path temporary;

    @Test
    void envelopeIdentifiesTheApplicationAndNeverClaimsMeasurements() throws Exception {
        Path appDirectory = Paths.get("..", "app");
        try (AppProcess app = AppProcess.builder(APP)
                .classpath(AppClasspath.of(appDirectory))
                .jvmOptions("-XX:+UseSerialGC")
                .start()) {
            Path out = temporary.resolve("result.json");
            BenchmarkHarness.main(new String[] {"--base-url", "http://127.0.0.1:" + app.port(), "--requests", "5",
                    "--threads", "2", "--runs", "2", "--out", out.toString()});
            Map<String, Object> result = Json.parseObject(new String(Files.readAllBytes(out), StandardCharsets.UTF_8));
            assertEquals("smoke", result.get("run_kind"));
            assertEquals(Boolean.FALSE, result.get("measurement_valid"));
            assertTrue(!((List<?>) result.get("invalid_reasons")).isEmpty());
            assertNull(result.get("kpis"));
            assertEquals(10L, ((Number) result.get("requests")).longValue());
            assertEquals(10L, ((Number) result.get("ok")).longValue());
            Map<String, Object> identity = Json.parseObject(Json.stringify(result.get("application_runtime")));
            assertEquals(app.token(), identity.get("run_token"));
            assertTrue(String.valueOf(result.get("jvm_flags")).contains("-XX:+UseSerialGC"), String.valueOf(result));
            assertFalse(String.valueOf(result.get("gc")).contains("G1"), String.valueOf(result));
            assertTrue(!((List<?>) result.get("gc")).isEmpty(), String.valueOf(result));
        }
    }
}
