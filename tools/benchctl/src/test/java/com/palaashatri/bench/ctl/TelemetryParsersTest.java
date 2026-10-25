package com.palaashatri.bench.ctl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TelemetryParsersTest {
    private static final String JSTAT_COMPILER = """
            Compiled Failed Invalid   Time   FailedType FailedMethod
                3309      3       0     2.83          1 jdk/internal/org/objectweb/asm/MethodWriter visitLabel
            """;
    private static final String BANNER = "Picked up JAVA_TOOL_OPTIONS: -Dhttps.proxyHost=127.0.0.1";

    @Test
    void parsesPlainJstatCompilerOutput() {
        Map<String, Object> parsed = Telemetry.parseJstatCompiler(JSTAT_COMPILER);
        assertEquals(3309L, parsed.get("compiled"));
        assertEquals(3L, parsed.get("failed"));
        assertEquals(0L, parsed.get("invalid"));
        assertEquals(2.83, parsed.get("time_seconds"));
    }

    @Test
    void bannerLineDoesNotShiftTheColumns() {
        // A banner after the values line was once read as the header pair, so every statistic came back null.
        Map<String, Object> parsed = Telemetry.parseJstatCompiler(BANNER + "\n" + JSTAT_COMPILER + "\n" + BANNER + "\n");
        assertEquals(3309L, parsed.get("compiled"));
    }

    @Test
    void missingOutputIsUnknownNotZero() {
        Map<String, Object> parsed = Telemetry.parseJstatCompiler(BANNER);
        assertNull(parsed.get("compiled"));
        assertNull(parsed.get("failed"));
        assertNull(parsed.get("invalid"));
        assertNull(parsed.get("time_seconds"));
    }

    @Test
    void gcPausesAreParsedFromUnifiedAndLegacyLogs() {
        Map<String, Object> legacy = Telemetry.parseGcPauses(
                "4.971: [GC pause (G1 Evacuation Pause) (young), 0.0074204 secs]\\n");
        assertEquals(1, legacy.get("count"));
        assertEquals(7.4204, ((Number) legacy.get("p50_ms")).doubleValue(), 1e-9);
        Map<String, Object> none = Telemetry.parseGcPauses("CommandLine flags: -XX:+UseG1GC\\n");
        assertEquals(0, none.get("count"));
        assertNull(none.get("p99_ms"));
    }
}
