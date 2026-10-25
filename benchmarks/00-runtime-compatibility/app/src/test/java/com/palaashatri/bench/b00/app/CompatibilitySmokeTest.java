package com.palaashatri.bench.b00.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.palaashatri.bench.testkit.AppProcess;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Functional gate: the application reports a Java 8 bytecode target, and its deterministic work returns the same
 * checksum for the same request. Java 8 bytecode, so the test reads fields with a pattern rather than a JSON library.
 */
class CompatibilitySmokeTest {
    private static final String APP = "com.palaashatri.bench.b00.app.BenchmarkApp";
    private static final String WORK = "{\"seed\":424242,\"size\":4096}";

    @Test
    void deterministicWorkReportsAJava8TargetAndTheSameChecksum() {
        try (AppProcess app = AppProcess.builder(APP).start()) {
            String runtime = app.get("/runtime");
            assertEquals("8", field(runtime, "bytecode_target"), runtime);
            String first = app.request("POST", "/work", WORK);
            String second = app.request("POST", "/work", WORK);
            assertEquals(field(first, "checksum"), field(second, "checksum"));
            assertEquals("8", field(first, "bytecode_target"), first);
        }
    }

    private static String field(String json, String key) {
        Matcher match = Pattern.compile("\"" + key + "\"\\s*:\\s*(\"([^\"]*)\"|[-0-9.]+|true|false|null)").matcher(json);
        assertTrue(match.find(), "no " + key + " in " + json);
        return match.group(2) != null ? match.group(2) : match.group(1);
    }
}
