package com.palaashatri.bench.ctl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportTest {
    @TempDir
    Path temporary;

    private static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            map.put((String) keysAndValues[index], keysAndValues[index + 1]);
        }
        return map;
    }

    private static Map<String, Object> metric(double median, double spread) {
        return map("count", 5L, "mean", median, "median", median, "standard_deviation", spread,
                "median_absolute_deviation", spread, "coefficient_of_variation", spread / median,
                "bootstrap_mean_ci", map("low", median - spread, "high", median + spread, "confidence", 0.95),
                "values", List.of());
    }

    private static Map<String, Object> unknownMetric() {
        return map("count", 0L, "mean", null, "median", null, "standard_deviation", null,
                "median_absolute_deviation", null, "coefficient_of_variation", null,
                "bootstrap_mean_ci", map("low", null, "high", null, "confidence", 0.95));
    }

    private static Map<String, Object> benchmarkResult(double throughput, boolean valid, List<String> reasons) {
        List<String> invalidReasons = reasons != null ? reasons
                : valid ? List.of() : List.of("repetitions are fewer than five");
        return map(
                "schema_version", "1.0.0",
                "benchmark", "01-fintech-ledger",
                "run_kind", valid ? "benchmark" : "smoke",
                "implementation_tier", valid ? "tier-2" : "tier-1",
                "measurement_valid", valid,
                "invalid_reasons", invalidReasons,
                "warnings", List.of(),
                "runtime", "jdk-21-g1",
                "requested_collector", "g1",
                "java_identity_sha256", "ab".repeat(32),
                "repetitions", List.of(map("status", "passed",
                        "environment", map("cpu_model", "fixture-cpu"),
                        "load", map("kpis", map("throughput", throughput, "p99_ms", 3.0)))),
                "aggregate", map("throughput", metric(throughput, 0.5),
                        "p99_ms", metric(3.0, 0.1),
                        "gc_pause_p99_ms", unknownMetric()));
    }

    private static Map<String, Object> benchmarkResult(double throughput) {
        return benchmarkResult(throughput, true, null);
    }

    private static Report.Entry entry(String label, Map<String, Object> document) {
        return new Report.Entry(label, document);
    }

    @Test
    void validBenchmarkGetsAPositiveBannerAndAggregateTable() {
        String html = Report.renderReport(List.of(entry("run-a", benchmarkResult(1000.0))));
        assertTrue(html.contains("Measurement-valid benchmark result."), html);
        assertTrue(html.contains("Aggregate over repetitions"));
        assertTrue(html.contains("1,000"));
        assertFalse(html.contains("e+0"));
    }

    @Test
    void invalidResultIsLabelledAsDiagnosticAndListsReasons() {
        String html = Report.renderReport(List.of(entry("smoke", benchmarkResult(900.0, false, null))));
        assertTrue(html.contains("Not a measurement-valid result"));
        assertTrue(html.contains("repetitions are fewer than five"));
        assertFalse(html.contains("Measurement-valid benchmark result."));
    }

    @Test
    void unknownMetricsRenderAsUnknownNeverZero() {
        String html = Report.renderReport(List.of(entry("run-a", benchmarkResult(1000.0))));
        String gcRow = html.split("<td>GC pause p99</td>", -1)[1].split("</tr>", -1)[0];
        // The sample count (0) is a real count; the measured statistics must be unknown.
        assertTrue(gcRow.contains("<td class=\"num unknown\">unknown</td>"), gcRow);
        assertFalse(gcRow.contains("<td class=\"num\">0</td><td class=\"num\">0</td>"), gcRow);
    }

    @Test
    void freeTextIsEscaped() {
        Map<String, Object> hostile = benchmarkResult(1.0, false, List.of("<script>alert(1)</script>"));
        String html = Report.renderReport(List.of(entry("x", hostile)));
        assertFalse(html.contains("<script>alert(1)</script>"));
        assertTrue(html.contains("&lt;script&gt;"));
    }

    @Test
    void entryLabelUsesTheRunDirectoryForResultJson() throws Exception {
        Path run = Files.createDirectories(temporary.resolve("run-042"));
        Files.writeString(run.resolve("result.json"), "{}");
        assertEquals("run-042", Report.entryLabel(run.resolve("result.json")));
        assertEquals("single", Report.entryLabel(temporary.resolve("single.json")));
    }

    @Test
    void invalidEntriesAreNotComparableAndValidOnesAreCompared() {
        List<Report.Entry> entries = List.of(
                entry("baseline", benchmarkResult(1000.0)),
                entry("candidate", benchmarkResult(1200.0)),
                entry("smoke", benchmarkResult(9999.0, false, null)));
        String html = Report.renderReport(entries);
        String comparison = html.split("<h2>Comparison</h2>", -1)[1].split("</table>", -1)[0];
        assertTrue(comparison.contains("baseline"));
        assertTrue(comparison.contains("improvement"), comparison);
        assertTrue(comparison.contains("not comparable"));
        assertFalse(comparison.contains("9999"));
    }

    @Test
    void singleResultHasNoComparisonSection() {
        String html = Report.renderReport(List.of(entry("only", benchmarkResult(1000.0))));
        assertFalse(html.contains("<h2>Comparison</h2>"));
    }

    @Test
    void reportCommandWritesComparisonToTheRequestedOutput() throws Exception {
        Path left = temporary.resolve("left.json");
        Path right = temporary.resolve("right.json");
        Files.writeString(left, Json.pretty(benchmarkResult(1000.0)));
        Files.writeString(right, Json.pretty(benchmarkResult(1200.0)));
        Path output = Files.createDirectories(temporary.resolve("out")).resolve("report.html");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int status = BenchCtl.run(new String[] {"report", left.toString(), right.toString(), "-o", output.toString()},
                new PrintStream(stdout, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream()));
        assertEquals(0, status);
        String text = Files.readString(output);
        assertTrue(text.contains("<h2>Comparison</h2>"));
        assertTrue(text.contains("<title>Benchmark report</title>"));
    }

    @Test
    void runDirectoryIsReportedAsOneSectionPerWorkload() throws Exception {
        Map<String, Object> run = map(
                "schema_version", "1.0.0",
                "run_kind", "benchmark",
                "measurement_valid", false,
                "results", List.of(benchmarkResult(1000.0), benchmarkResult(1100.0, false, null)));
        Path directory = Files.createDirectories(temporary.resolve("run"));
        Files.writeString(directory.resolve("result.json"), Json.pretty(run));
        Path output = temporary.resolve("report.html");
        Report.write(List.of(directory), output, path -> Json.asMap(
                Repo.readJsonFile(Files.isDirectory(path) ? path.resolve("result.json") : path)));
        String text = Files.readString(output);
        assertTrue(text.contains("run / 01-fintech-ledger"), text);
        assertTrue(text.contains("Measurement-valid benchmark result."));
        assertTrue(text.contains("Not a measurement-valid result"));
    }

    @Test
    void missingBarValuesAreLabelledUnknownAndNeverDrawn() {
        String svg = Report.svgBarChart("Peak RSS", List.of("rep 1", "rep 2", "rep 3"),
                Arrays.asList(100.0, null, 120.0), "MiB", null, null);
        assertEquals(2, count(svg, "<rect"));
        assertTrue(svg.contains(">unknown</text>"));
    }

    @Test
    void noMeasuredValuesProducesTextNotAnEmptyChart() {
        String svg = Report.svgBarChart("GC pause", List.of("rep 1"), Arrays.asList((Double) null), "ms", null, null);
        assertFalse(svg.contains("<svg"));
        assertTrue(svg.contains("no measured values"));
    }

    @Test
    void lineBreaksAtMissingPointsInsteadOfDroppingToZero() {
        String svg = Report.svgLineChart("p50",
                List.of(new Report.Series("rep 1", Arrays.asList(5.0, 6.0, null, 7.0, 8.0))), "ms", "second", null);
        // Two runs of two points each: the gap splits the line into two polylines and no point is plotted at zero.
        assertEquals(2, count(svg, "<polyline"));
        assertEquals(0, count(svg, "<circle"));
    }

    @Test
    void warmUpMarkerIsDrawnAtTheWarmUpBoundary() {
        String svg = Report.svgLineChart("throughput",
                List.of(new Report.Series("rep 1", Collections.nCopies(6, 20.0))), "req/s", "second", 2);
        assertTrue(svg.contains("warm-up ends"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void repetitionChartsComeFromTheRecordedSeries() {
        Map<String, Object> document = benchmarkResult(1000.0);
        Map<String, Object> repetition = (Map<String, Object>) ((List<Object>) document.get("repetitions")).get(0);
        Map<String, Object> load = (Map<String, Object>) repetition.get("load");
        load.put("series", map("completed", List.of(20, 20, 20, 20), "p50_ms", List.of(4.0, 4.1, 3.9, 4.0)));
        load.put("phases", map("warmup_s", 1, "measure_s", 2));
        List<String> charts = Report.repetitionCharts(document);
        assertTrue(charts.stream().anyMatch(chart -> chart.contains("Delivered throughput over time")));
        assertTrue(charts.stream().anyMatch(chart -> chart.contains("p50 latency over time")));
    }

    @Test
    void comparisonChartRefusesToPlotInvalidResults() {
        String svg = Report.comparisonChart(List.of(
                entry("baseline", benchmarkResult(1000.0)),
                entry("smoke", benchmarkResult(9999.0, false, null))));
        assertTrue(svg.contains("smoke (not comparable)"), svg);
        assertFalse(svg.contains("9,999"));
    }

    @Test
    void chartTitlesAreEscaped() {
        String svg = Report.svgBarChart("<b>x</b>", List.of("a"), List.of(1.0), "ms", null, null);
        assertFalse(svg.contains("<b>x</b>"));
    }

    @Test
    void smallValuesKeepPlainNotation() {
        assertEquals("999", Report.formatNumber(999.0).replace(".0", ""));
        assertEquals("9.5", Report.formatNumber(9.5));
        assertEquals("0.00123", Report.formatNumber(0.00123));
        assertEquals("1.23e-05", Report.formatNumber(0.0000123));
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + 1)) {
            count++;
        }
        return count;
    }
}
