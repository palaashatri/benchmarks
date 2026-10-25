package com.palaashatri.bench.ctl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The terminal summary and the automatic HTML report: plain ASCII, unknown stays unknown, verdict stated. */
class ReportSummaryTest {
    @TempDir
    Path temporary;

    private static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            map.put((String) keysAndValues[index], keysAndValues[index + 1]);
        }
        return map;
    }

    private static Map<String, Object> repetition(Double throughput, Double p99, List<Double> completed) {
        return map("status", "passed",
                "load", map("kpis", map("throughput", throughput, "p99_ms", p99),
                        "series", map("completed", completed == null ? List.of() : completed)));
    }

    private static Map<String, Object> document(List<Map<String, Object>> repetitions, boolean valid) {
        return map("benchmark", "06-massive-chat-loom", "run_kind", valid ? "benchmark" : "calibration",
                "implementation_tier", "tier-2", "runtime", "jdk-21-g1", "measurement_valid", valid,
                "invalid_reasons", valid ? List.of() : List.of("run_kind is not benchmark"),
                "warnings", List.of(), "repetitions", repetitions);
    }

    @Test
    void theTerminalSummaryIsPlainAscii() {
        Map<String, Object> document = document(List.of(repetition(1200.0, 4.5, Arrays.asList(1.0, 2.0)),
                repetition(null, 5.0, null)), false);
        String text = Report.textSummary("06-massive-chat-loom", document, temporary.resolve("report.html"));
        assertTrue(text.chars().allMatch(character -> character < 128), text);
    }

    @Test
    void aMissingValueIsUnknownAndDrawsNoBar() {
        Map<String, Object> document = document(List.of(repetition(1200.0, 4.5, null), repetition(null, 5.0, null)), false);
        String text = Report.textSummary("06-massive-chat-loom", document, null);
        List<String> lines = text.lines().filter(line -> line.contains("unknown")).toList();
        assertFalse(lines.isEmpty(), text);
        assertTrue(lines.stream().noneMatch(line -> line.contains("#")), text);
    }

    @Test
    void aNotValidResultSaysSoAndListsItsReasons() {
        String text = Report.textSummary("06-massive-chat-loom", document(List.of(repetition(1200.0, 4.5, null)), false), null);
        assertTrue(text.contains("NOT measurement-valid"), text);
        assertTrue(text.contains("run_kind is not benchmark"), text);
    }

    @Test
    void sparklineMarksMissingSecondsAndSpansTheRamp() {
        String text = Report.textSummary("06-massive-chat-loom",
                document(List.of(repetition(1200.0, 4.5, Arrays.asList(1.0, null, 3.0))), false), null);
        assertTrue(text.contains("?@"), text);
    }

    @Test
    void theHtmlReportHasCardsAndTheVerdictBanner() throws Exception {
        Path html = Report.writeDocument(temporary.resolve("report.html"), "06-massive-chat-loom",
                document(List.of(repetition(1200.0, 4.5, Arrays.asList(1.0, 2.0))), false));
        String page = Files.readString(html, StandardCharsets.UTF_8);
        assertTrue(page.contains("class='cards'"), page);
        assertTrue(page.contains("class='banner diagnostic'"), page);
        assertTrue(page.contains("<svg"), page);
    }

    @Test
    void theRunSummaryCountsValidWorkloadsAndNamesTheReport() {
        Map<String, Object> run = map("results", List.of(document(List.of(), true), document(List.of(), false)));
        String text = Report.runSummary(temporary.resolve("run-1"), run, temporary.resolve("run-1/report.html"));
        assertTrue(text.contains("2 (1 measurement-valid)"), text);
        assertTrue(text.contains("NOT measurement-valid"), text);
        assertTrue(text.contains("report.html"), text);
    }
}
