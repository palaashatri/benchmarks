package com.palaashatri.bench.b00.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.palaashatri.bench.testkit.AppClasspath;
import com.palaashatri.bench.testkit.AppProcess;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Functional gate for the Java 8 client: the envelope it writes never claims a measurement. This module is Java 8
 * bytecode, so the test reads the envelope with plain string checks instead of the shared JSON parser.
 */
class HarnessSmokeTest {
    private static final String APP = "com.palaashatri.bench.b00.app.BenchmarkApp";

    @TempDir
    Path temporary;

    @Test
    void envelopeIdentifiesTheApplicationAndNeverClaimsMeasurements() throws Exception {
        try (AppProcess app = AppProcess.builder(APP)
                .classpath(AppClasspath.of(Paths.get("..", "app")))
                .jvmOptions("-XX:+UseSerialGC")
                .start()) {
            Path out = temporary.resolve("result.json");
            BenchmarkHarness.main(new String[] {"--base-url", "http://127.0.0.1:" + app.port(), "--requests", "5",
                    "--threads", "2", "--runs", "2", "--out", out.toString()});
            String json = new String(Files.readAllBytes(out), StandardCharsets.UTF_8).replaceAll("\\s+", "");
            assertTrue(json.contains("\"run_kind\":\"smoke\""), json);
            assertTrue(json.contains("\"measurement_valid\":false"), json);
            assertTrue(json.contains("\"kpis\":null"), json);
            assertTrue(json.contains("\"requests\":10"), json);
            assertTrue(json.contains("\"ok\":10"), json);
            assertTrue(json.contains("\"run_token\":\"" + app.token() + "\""), json);
            assertTrue(json.contains("-XX:+UseSerialGC"), json);
            assertFalse(json.contains("G1"), json);
        }
    }
}
