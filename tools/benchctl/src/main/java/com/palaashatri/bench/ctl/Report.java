package com.palaashatri.bench.ctl;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Readable HTML reports for benchctl result envelopes.
 *
 * <p>A report never turns an invalid or smoke result into a performance claim. Their numbers are shown as diagnostics
 * under an explicit banner, and comparisons are drawn only between measurement-valid benchmark results. Unknown values
 * render as "unknown", never as zero.
 */
final class Report {
    static final String UNKNOWN = "unknown";

    /** Aggregate keys written by {@link Tier2#aggregate}, in display order: key, label, unit. */
    private static final String[][] AGGREGATE_METRICS = {
        {"throughput", "Throughput", "req/s"},
        {"p50_ms", "p50 latency", "ms"},
        {"p99_ms", "p99 latency", "ms"},
        {"p999_ms", "p99.9 latency", "ms"},
        {"gc_pause_p99_ms", "GC pause p99", "ms"},
        {"rss_mb_max", "Peak RSS", "MiB"},
        {"process_cpu_pct_one_core_mean", "Process CPU (one-core mean)", "%"},
        {"native_memory_committed_mb", "Committed native memory", "MiB"},
        {"code_cache_used_mb", "Code cache used", "MiB"},
        {"compiled_methods", "Compiled methods", "count"},
        {"compiler_time_seconds", "Compiler time", "s"},
        {"jfr_allocation_sample_rate_mb_s", "Allocation rate (JFR)", "MiB/s"},
        {"process_tree_process_count_max", "Process tree: most processes at once", "count"},
        {"process_tree_rss_mb_max", "Process tree: peak RSS", "MiB"},
        {"process_tree_cpu_pct_one_core_mean", "Process tree: CPU (one-core mean)", "%"},
        {"process_tree_gc_pause_p99_ms", "Process tree: GC pause p99 (all JVMs)", "ms"},
        {"startup_default_median_ms", "Startup: time to health, default mode (median)", "ms"},
        {"startup_no_cds_median_ms", "Startup: time to health, no CDS (median)", "ms"},
        {"startup_appcds_median_ms", "Startup: time to health, AppCDS (median)", "ms"},
        {"process_tree_native_memory_committed_mb", "Process tree: committed native memory (all JVMs)", "MiB"},
        {"process_tree_jfr_allocation_sample_rate_mb_s", "Process tree: allocation sample rate (all JVMs)", "MiB/s"},
    };

    private static final String CSS = """
            body { font: 15px/1.5 system-ui, sans-serif; margin: 0 auto; max-width: 1100px; padding: 16px; color: #1d1d1f; }
            h1 { font-size: 1.6em; } h2 { font-size: 1.25em; margin-top: 2em; border-bottom: 1px solid #ccc; padding-bottom: 4px; }
            table { border-collapse: collapse; width: 100%; margin: 8px 0 16px; font-size: 14px; }
            th, td { text-align: left; padding: 4px 8px; border-bottom: 1px solid #e3e3e3; vertical-align: top; }
            th { font-weight: 600; background: #f5f5f7; }
            td.num { text-align: right; font-variant-numeric: tabular-nums; }
            td.unknown, .unknown { color: #6e6e73; font-style: italic; }
            .banner { padding: 10px 12px; border-radius: 6px; margin: 12px 0; }
            .banner.valid { background: #e8f5e9; border-left: 4px solid #2e7d32; }
            .banner.diagnostic { background: #fff4e5; border-left: 4px solid #b26a00; }
            .banner ul { margin: 6px 0 0 18px; padding: 0; }
            figure { margin: 12px 0; } figure svg { max-width: 100%; height: auto; }
            code { font-size: 13px; word-break: break-all; }
            """;

    static final int CHART_WIDTH = 640;
    static final int CHART_HEIGHT = 240;
    private static final int MARGIN_LEFT = 64;
    private static final int MARGIN_RIGHT = 16;
    private static final int MARGIN_TOP = 28;
    private static final int MARGIN_BOTTOM = 40;
    private static final String[] SERIES_COLOURS = {"#2f6fbf", "#c4572b", "#2e7d32", "#7b1fa2", "#00838f", "#8d6e00"};

    /** One series of a line chart; a null point breaks the line rather than dropping to zero. */
    record Series(String name, List<Double> values) {}

    /** A labelled result, after run envelopes have been expanded into one entry per workload. */
    record Entry(String label, Map<String, Object> document) {}

    private Report() {}

    /** Loads each path, expands run envelopes, and writes one HTML report. */
    static Path write(List<Path> paths, Path output, Function<Path, Map<String, Object>> load) {
        List<Entry> entries = new ArrayList<>();
        for (Path path : paths) {
            entries.add(new Entry(entryLabel(path), load.apply(path)));
        }
        try {
            Files.writeString(output, renderReport(expandRunEnvelopes(entries)), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new BenchError("cannot write " + output + ": " + exception.getMessage(), exception);
        }
        return output;
    }

    /** Render one HTML document covering the given entries. */
    static String renderReport(List<Entry> entries) {
        String title = entries.size() == 1 ? "Benchmark report: " + entries.get(0).label() : "Benchmark report";
        List<String> sections = new ArrayList<>();
        for (Entry entry : entries) {
            sections.add(documentSection(entry.label(), entry.document()));
        }
        if (entries.size() > 1) {
            sections.add(0, comparisonTable(entries));
            sections.add(1, "<figure>" + comparisonChart(entries) + "</figure>");
        }
        return "<!doctype html><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width, initial-scale=1'>"
                + "<title>" + escape(title) + "</title><style>" + CSS + "</style>"
                + "<h1>" + escape(title) + "</h1>"
                + "<p>Smoke output verifies behaviour only. Only measurement-valid benchmark results may be compared "
                + "or quoted as performance.</p>"
                + String.join("\n", sections) + "\n";
    }

    /** Name a result by its run directory, or by its file name without the extension. */
    static String entryLabel(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        String name = String.valueOf(absolute.getFileName());
        if (Files.isDirectory(absolute) || name.equals("result.json")) {
            return name.equals("result.json") ? String.valueOf(absolute.getParent().getFileName()) : name;
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** A run directory holds one envelope per workload under {@code results}; each one is reported separately. */
    static List<Entry> expandRunEnvelopes(List<Entry> entries) {
        List<Entry> expanded = new ArrayList<>();
        for (Entry entry : entries) {
            List<Object> items = Json.asList(entry.document().get("results"));
            if (!items.isEmpty() && items.stream().allMatch(item -> item instanceof Map)) {
                for (int index = 0; index < items.size(); index++) {
                    Map<String, Object> item = Json.asMap(items.get(index));
                    Object benchmark = item.get("benchmark");
                    String name = benchmark == null || benchmark.toString().isEmpty()
                            ? "item " + (index + 1) : benchmark.toString();
                    expanded.add(new Entry(entry.label() + " / " + name, item));
                }
            } else {
                expanded.add(entry);
            }
        }
        return expanded;
    }

    // ---- value formatting -------------------------------------------------------------------------------

    static String formatNumber(Object value) {
        if (value == null) {
            return UNKNOWN;
        }
        if (value instanceof Boolean flag) {
            return flag.toString();
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return String.format(Locale.ROOT, "%,d", ((Number) value).longValue());
        }
        if (value instanceof Number number) {
            double magnitude = Math.abs(number.doubleValue());
            if (!Double.isFinite(number.doubleValue())) {
                return UNKNOWN;
            }
            if (magnitude >= 1000) {
                return String.format(Locale.ROOT, "%,.0f", number.doubleValue());
            }
            if (magnitude >= 10) {
                return String.format(Locale.ROOT, "%.1f", number.doubleValue());
            }
            // Keep small values in plain notation, as Python's ".3g" does; a bare "%g" would print 999.5 as 1e+03.
            return threeSignificantFigures(number.doubleValue());
        }
        return escape(value.toString());
    }

    /** Python's {@code format(value, ".3g")}: three significant figures with trailing zeros removed. */
    static String threeSignificantFigures(double value) {
        if (value == 0) {
            return "0";
        }
        BigDecimal rounded = new BigDecimal(value).round(new MathContext(3, RoundingMode.HALF_EVEN));
        int exponent = rounded.precision() - rounded.scale() - 1;
        if (exponent < -4 || exponent >= 3) {
            BigDecimal mantissa = rounded.movePointLeft(exponent).stripTrailingZeros();
            return mantissa.toPlainString() + "e" + (exponent < 0 ? "-" : "+")
                    + String.format(Locale.ROOT, "%02d", Math.abs(exponent));
        }
        return rounded.stripTrailingZeros().toPlainString();
    }

    static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (char character : text.toCharArray()) {
            switch (character) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#x27;");
                default -> out.append(character);
            }
        }
        return out.toString();
    }

    private static String cell(Object value, boolean numeric) {
        List<String> css = new ArrayList<>();
        if (numeric) {
            css.add("num");
        }
        if (value == null) {
            css.add("unknown");
        }
        String classAttribute = css.isEmpty() ? "" : " class=\"" + String.join(" ", css) + "\"";
        return "<td" + classAttribute + ">" + formatNumber(value) + "</td>";
    }

    private static String textCell(Object value) {
        if (value == null) {
            return "<td class=\"unknown\">" + UNKNOWN + "</td>";
        }
        return "<td>" + escape(value.toString()) + "</td>";
    }

    static Object dig(Object document, String... keys) {
        Object value = document;
        for (String key : keys) {
            if (!(value instanceof Map<?, ?> map)) {
                return null;
            }
            value = map.get(key);
        }
        return value;
    }

    private static String intervalText(Map<String, Object> metric) {
        Map<String, Object> interval = Json.asMap(metric.get("bootstrap_mean_ci"));
        Object low = interval.get("low");
        Object high = interval.get("high");
        if (low == null || high == null) {
            return UNKNOWN;
        }
        return formatNumber(low) + " to " + formatNumber(high);
    }

    // ---- tables -----------------------------------------------------------------------------------------

    private static String aggregateTable(Map<String, Object> aggregate) {
        String header = "<tr><th>Metric</th><th>Unit</th><th>n</th><th>Median</th><th>Mean</th>"
                + "<th>Std. dev.</th><th>MAD</th><th>CV</th><th>95% CI of mean</th></tr>";
        StringBuilder rows = new StringBuilder();
        for (String[] definition : AGGREGATE_METRICS) {
            if (!(aggregate.get(definition[0]) instanceof Map<?, ?>)) {
                continue;
            }
            Map<String, Object> metric = Json.asMap(aggregate.get(definition[0]));
            rows.append("<tr>")
                    .append("<td>").append(escape(definition[1])).append("</td>")
                    .append("<td>").append(escape(definition[2])).append("</td>")
                    .append(cell(metric.get("count"), true))
                    .append(cell(metric.get("median"), true))
                    .append(cell(metric.get("mean"), true))
                    .append(cell(metric.get("standard_deviation"), true))
                    .append(cell(metric.get("median_absolute_deviation"), true))
                    .append(cell(metric.get("coefficient_of_variation"), true))
                    .append("<td>").append(intervalText(metric)).append("</td>")
                    .append("</tr>");
        }
        if (rows.isEmpty()) {
            return "<p class='unknown'>No aggregate metrics were recorded.</p>";
        }
        return "<table>" + header + rows + "</table>";
    }

    private static String kpiTable(Map<String, Object> kpis) {
        StringBuilder rows = new StringBuilder();
        for (Map.Entry<String, Object> entry : kpis.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> || entry.getValue() instanceof List<?>) {
                continue;
            }
            rows.append("<tr><td><code>").append(escape(entry.getKey())).append("</code></td>")
                    .append(cell(entry.getValue(), true)).append("</tr>");
        }
        if (rows.isEmpty()) {
            return "<p class='unknown'>No single-value KPIs were recorded.</p>";
        }
        return "<table><tr><th>KPI</th><th>Value</th></tr>" + rows + "</table>";
    }

    private static String repetitionTable(List<Map<String, Object>> repetitions) {
        StringBuilder rows = new StringBuilder();
        for (int index = 0; index < repetitions.size(); index++) {
            Map<String, Object> repetition = repetitions.get(index);
            rows.append("<tr>")
                    .append("<td class='num'>").append(index + 1).append("</td>")
                    .append(textCell(repetition.get("status")))
                    .append(cell(dig(repetition, "load", "kpis", "throughput"), true))
                    .append(cell(dig(repetition, "load", "kpis", "p99_ms"), true))
                    .append(cell(dig(repetition, "telemetry", "gc", "p99_ms"), true))
                    .append("</tr>");
        }
        return "<table><tr><th>Repetition</th><th>Status</th><th>Throughput (req/s)</th>"
                + "<th>p99 (ms)</th><th>GC pause p99 (ms)</th></tr>" + rows + "</table>";
    }

    /** The first repetition's environment fingerprint, if one was recorded. */
    private static String environmentTable(Map<String, Object> document) {
        List<Object> repetitions = Json.asList(document.get("repetitions"));
        Object environment = repetitions.isEmpty() ? null : dig(repetitions.get(0), "environment");
        if (!(environment instanceof Map<?, ?>)) {
            return "<p class='unknown'>No environment fingerprint was recorded.</p>";
        }
        StringBuilder rows = new StringBuilder();
        for (Map.Entry<String, Object> entry : new TreeMap<>(Json.asMap(environment)).entrySet()) {
            Object value = entry.getValue();
            Object shown = value;
            if (value instanceof Map<?, ?>) {
                shown = Json.write(new TreeMap<>(Json.asMap(value)));
            } else if (value instanceof List<?>) {
                shown = Json.write(value);
            }
            rows.append("<tr><td><code>").append(escape(entry.getKey())).append("</code></td>")
                    .append(textCell(shown)).append("</tr>");
        }
        return "<table><tr><th>Field</th><th>Value</th></tr>" + rows + "</table>";
    }

    private static String identityTable(Map<String, Object> document) {
        Object benchmark = document.get("benchmark") != null ? document.get("benchmark") : document.get("workload");
        Object collector = document.get("requested_collector") != null
                ? document.get("requested_collector") : document.get("gc");
        Object repetitions = document.get("repetitions") instanceof List<?> list ? (Object) list.size() : null;
        Object[][] fields = {
            {"Benchmark", benchmark},
            {"Runtime", document.get("runtime")},
            {"Requested collector", collector},
            {"Java identity (sha256)", document.get("java_identity_sha256")},
            {"Repetitions", repetitions},
        };
        StringBuilder rows = new StringBuilder();
        for (Object[] field : fields) {
            rows.append("<tr><th>").append(field[0]).append("</th>").append(textCell(field[1])).append("</tr>");
        }
        return "<table>" + rows + "</table>";
    }

    private static String banner(Map<String, Object> document) {
        boolean valid = Boolean.TRUE.equals(document.get("measurement_valid"))
                && "benchmark".equals(document.get("run_kind"));
        String headline = valid
                ? "Measurement-valid benchmark result."
                : "Not a measurement-valid result. The numbers below are diagnostics, not performance claims.";
        StringBuilder items = new StringBuilder();
        for (Object reason : Json.asList(document.get("invalid_reasons"))) {
            items.append("<li>").append(escape(String.valueOf(reason))).append("</li>");
        }
        for (Object warning : Json.asList(document.get("warnings"))) {
            items.append("<li>warning: ").append(escape(String.valueOf(warning))).append("</li>");
        }
        String listing = items.isEmpty() ? "" : "<ul>" + items + "</ul>";
        Object measured = document.get("measurement_valid");
        return "<div class='banner " + (valid ? "valid" : "diagnostic") + "'><strong>" + escape(headline) + "</strong><br>"
                + "run_kind: <code>" + code(document.get("run_kind")) + "</code> · "
                + "tier: <code>" + code(document.get("implementation_tier")) + "</code> · "
                + "measurement_valid: <code>" + code(measured) + "</code>" + listing + "</div>";
    }

    private static String code(Object value) {
        return value == null ? UNKNOWN : escape(value.toString());
    }

    // ---- charts -----------------------------------------------------------------------------------------

    private static double scaleTop(List<Double> values) {
        double top = values.stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
        return top > 0 ? top * 1.1 : 1.0;
    }

    /**
     * Vertical bars with optional confidence whiskers. A null value is drawn as the word "unknown" at the baseline,
     * never as a bar, so a missing measurement cannot look like zero.
     */
    static String svgBarChart(String title, List<String> labels, List<Double> values, String unit,
                              List<Double> lows, List<Double> highs) {
        List<Double> measured = values.stream().filter(value -> value != null).toList();
        if (measured.isEmpty()) {
            return "<p class='unknown'>" + escape(title) + ": no measured values.</p>";
        }
        int left = MARGIN_LEFT;
        int top = MARGIN_TOP;
        int width = CHART_WIDTH - MARGIN_LEFT - MARGIN_RIGHT;
        int height = CHART_HEIGHT - MARGIN_TOP - MARGIN_BOTTOM;
        List<Double> extents = new ArrayList<>(measured);
        if (lows != null) {
            lows.stream().filter(bound -> bound != null).forEach(extents::add);
        }
        if (highs != null) {
            highs.stream().filter(bound -> bound != null).forEach(extents::add);
        }
        double ceiling = scaleTop(extents);
        double slot = (double) width / Math.max(values.size(), 1);
        double barWidth = slot * .6;
        StringBuilder svg = new StringBuilder();
        svg.append(String.format(Locale.ROOT, "<svg viewBox=\"0 0 %d %d\" width=\"100%%\" role=\"img\" aria-label=\"%s\">",
                CHART_WIDTH, CHART_HEIGHT, escape(title)));
        svg.append(String.format(Locale.ROOT, "<text x=\"%d\" y=\"16\" font-size=\"13\" font-weight=\"600\">%s (%s)</text>",
                left, escape(title), escape(unit)));
        svg.append(axes(left, top, width, height));
        svg.append(String.format(Locale.ROOT, "<text x=\"%d\" y=\"%d\" font-size=\"11\" text-anchor=\"end\">%s</text>",
                left - 6, top + 4, formatNumber(ceiling)));
        for (int index = 0; index < values.size(); index++) {
            double centre = left + slot * index + slot / 2;
            double x = centre - barWidth / 2;
            Double value = values.get(index);
            if (value == null) {
                svg.append(String.format(Locale.ROOT, "<text x=\"%.1f\" y=\"%d\" font-size=\"11\" text-anchor=\"middle\" "
                        + "fill=\"#6e6e73\">unknown</text>", centre, top + height - 6));
            } else {
                double barHeight = height * value / ceiling;
                svg.append(String.format(Locale.ROOT, "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" "
                        + "fill=\"%s\"/>", x, top + height - barHeight, barWidth, barHeight, SERIES_COLOURS[0]));
                svg.append(String.format(Locale.ROOT, "<text x=\"%.1f\" y=\"%.1f\" font-size=\"11\" text-anchor=\"middle\">%s</text>",
                        centre, top + height - barHeight - 4, formatNumber(value)));
                Double low = lows != null && index < lows.size() ? lows.get(index) : null;
                Double high = highs != null && index < highs.size() ? highs.get(index) : null;
                if (low != null && high != null) {
                    svg.append(String.format(Locale.ROOT, "<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" "
                            + "stroke=\"#222\" stroke-width=\"1.5\"/>", centre,
                            top + height - height * low / ceiling, centre, top + height - height * high / ceiling));
                }
            }
            svg.append(String.format(Locale.ROOT, "<text x=\"%.1f\" y=\"%d\" font-size=\"11\" text-anchor=\"middle\">%s</text>",
                    centre, top + height + 16, escape(labels.get(index))));
        }
        return svg.append("</svg>").toString();
    }

    /**
     * One polyline per series. A null point breaks the line rather than dropping to zero. {@code markerX} draws a
     * dashed line at the end of warm-up.
     */
    static String svgLineChart(String title, List<Series> series, String unit, String xLabel, Integer markerX) {
        List<Double> points = series.stream().flatMap(item -> item.values().stream())
                .filter(value -> value != null).toList();
        if (points.isEmpty()) {
            return "<p class='unknown'>" + escape(title) + ": no measured values.</p>";
        }
        int left = MARGIN_LEFT;
        int top = MARGIN_TOP;
        int width = CHART_WIDTH - MARGIN_LEFT - MARGIN_RIGHT;
        int height = CHART_HEIGHT - MARGIN_TOP - MARGIN_BOTTOM;
        double ceiling = scaleTop(points);
        int length = series.stream().mapToInt(item -> item.values().size()).max().orElse(1);
        double xStep = (double) width / Math.max(length - 1, 1);
        StringBuilder svg = new StringBuilder();
        svg.append(String.format(Locale.ROOT, "<svg viewBox=\"0 0 %d %d\" width=\"100%%\" role=\"img\" aria-label=\"%s\">",
                CHART_WIDTH, CHART_HEIGHT, escape(title)));
        svg.append(String.format(Locale.ROOT, "<text x=\"%d\" y=\"16\" font-size=\"13\" font-weight=\"600\">%s (%s)</text>",
                left, escape(title), escape(unit)));
        svg.append(axes(left, top, width, height));
        svg.append(String.format(Locale.ROOT, "<text x=\"%d\" y=\"%d\" font-size=\"11\" text-anchor=\"end\">%s</text>",
                left - 6, top + 4, formatNumber(ceiling)));
        svg.append(String.format(Locale.ROOT, "<text x=\"%d\" y=\"%d\" font-size=\"11\" text-anchor=\"end\">%s</text>",
                left + width, top + height + 30, escape(xLabel)));
        if (markerX != null && markerX >= 0 && markerX < length) {
            double x = left + markerX * xStep;
            svg.append(String.format(Locale.ROOT, "<line x1=\"%.1f\" y1=\"%d\" x2=\"%.1f\" y2=\"%d\" stroke=\"#888\" "
                    + "stroke-dasharray=\"4 3\"/>", x, top, x, top + height));
            svg.append(String.format(Locale.ROOT, "<text x=\"%.1f\" y=\"%d\" font-size=\"11\" fill=\"#555\">warm-up ends</text>",
                    x + 4, top + 12));
        }
        for (int index = 0; index < series.size(); index++) {
            Series item = series.get(index);
            String colour = SERIES_COLOURS[index % SERIES_COLOURS.length];
            List<String> run = new ArrayList<>();
            for (int position = 0; position < item.values().size(); position++) {
                Double value = item.values().get(position);
                if (value == null) {
                    flushRun(svg, run, colour);
                    run = new ArrayList<>();
                } else {
                    run.add(String.format(Locale.ROOT, "%.1f,%.1f", left + position * xStep,
                            top + height - height * value / ceiling));
                }
            }
            flushRun(svg, run, colour);
            svg.append(String.format(Locale.ROOT, "<text x=\"%d\" y=\"%d\" font-size=\"11\" fill=\"%s\">%s</text>",
                    left + 8 + index * 110, CHART_HEIGHT - 6, colour, escape(item.name())));
        }
        return svg.append("</svg>").toString();
    }

    private static String axes(int left, int top, int width, int height) {
        return String.format(Locale.ROOT, "<line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#888\"/>"
                + "<line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#888\"/>",
                left, top + height, left + width, top + height, left, top, left, top + height);
    }

    private static void flushRun(StringBuilder svg, List<String> run, String colour) {
        if (run.size() == 1) {
            String[] point = run.get(0).split(",");
            svg.append(String.format(Locale.ROOT, "<circle cx=\"%s\" cy=\"%s\" r=\"2.5\" fill=\"%s\"/>",
                    point[0], point[1], colour));
        } else if (!run.isEmpty()) {
            svg.append(String.format(Locale.ROOT, "<polyline points=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"1.8\"/>",
                    String.join(" ", run), colour));
        }
    }

    private static List<Double> doubles(Object value) {
        List<Double> numbers = new ArrayList<>();
        for (Object item : Json.asList(value)) {
            numbers.add(item instanceof Number number ? number.doubleValue() : null);
        }
        return numbers;
    }

    static List<String> repetitionCharts(Map<String, Object> document) {
        List<Map<String, Object>> repetitions = new ArrayList<>();
        for (Object item : Json.asList(document.get("repetitions"))) {
            if (item instanceof Map<?, ?>) {
                repetitions.add(Json.asMap(item));
            }
        }
        List<String> charts = new ArrayList<>();
        if (repetitions.isEmpty()) {
            return charts;
        }
        List<String> labels = new ArrayList<>();
        for (int index = 0; index < repetitions.size(); index++) {
            labels.add("rep " + repetitions.get(index).getOrDefault("repetition", index + 1));
        }
        Object warmup = dig(dig(repetitions.get(0), "load"), "phases", "warmup_s");
        Integer marker = warmup instanceof Integer whole ? whole : null;

        List<Series> completed = new ArrayList<>();
        List<Series> p50 = new ArrayList<>();
        for (int index = 0; index < repetitions.size(); index++) {
            completed.add(new Series(labels.get(index), doubles(dig(repetitions.get(index), "load", "series", "completed"))));
            p50.add(new Series(labels.get(index), doubles(dig(repetitions.get(index), "load", "series", "p50_ms"))));
        }
        if (completed.stream().noneMatch(item -> item.values().isEmpty())) {
            charts.add(svgLineChart("Delivered throughput over time", completed, "req/s", "second", marker));
        }
        if (p50.stream().noneMatch(item -> item.values().isEmpty())) {
            charts.add(svgLineChart("p50 latency over time (by intended send second)", p50, "ms", "second", marker));
        }
        charts.add(svgBarChart("Throughput per repetition", labels, repetitionValues(repetitions,
                "load", "kpis", "throughput"), "req/s", null, null));
        charts.add(svgBarChart("GC pause p99 per repetition", labels, repetitionValues(repetitions,
                "telemetry", "gc", "p99_ms"), "ms", null, null));
        List<Double> rss = new ArrayList<>();
        for (Map<String, Object> repetition : repetitions) {
            Object bytes = dig(repetition, "telemetry", "process", "rss_bytes_max");
            rss.add(bytes instanceof Number number ? number.doubleValue() / (1024.0 * 1024.0) : null);
        }
        charts.add(svgBarChart("Peak RSS per repetition", labels, rss, "MiB", null, null));
        return charts;
    }

    private static List<Double> repetitionValues(List<Map<String, Object>> repetitions, String... keys) {
        List<Double> values = new ArrayList<>();
        for (Map<String, Object> repetition : repetitions) {
            Object value = dig(repetition, keys);
            values.add(value instanceof Number number ? number.doubleValue() : null);
        }
        return values;
    }

    static String comparisonChart(List<Entry> entries) {
        List<String> labels = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        List<Double> lows = new ArrayList<>();
        List<Double> highs = new ArrayList<>();
        for (Entry entry : entries) {
            Map<String, Object> metric = Json.asMap(dig(entry.document(), "aggregate", "throughput"));
            boolean valid = Boolean.TRUE.equals(entry.document().get("measurement_valid"));
            Map<String, Object> interval = Json.asMap(metric.get("bootstrap_mean_ci"));
            labels.add(valid ? entry.label() : entry.label() + " (not comparable)");
            values.add(valid ? number(metric.get("median")) : null);
            lows.add(valid ? number(interval.get("low")) : null);
            highs.add(valid ? number(interval.get("high")) : null);
        }
        return svgBarChart("Throughput median across runs", labels, values, "req/s", lows, highs);
    }

    private static Double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    // ---- sections ---------------------------------------------------------------------------------------

    private static String documentSection(String label, Map<String, Object> document) {
        List<String> parts = new ArrayList<>();
        parts.add("<h2>" + escape(label) + "</h2>");
        parts.add(banner(document));
        parts.add(identityTable(document));
        if (document.get("aggregate") instanceof Map<?, ?>) {
            parts.add("<h3>Aggregate over repetitions</h3>");
            parts.add(aggregateTable(Json.asMap(document.get("aggregate"))));
        } else if (document.get("kpis") instanceof Map<?, ?>) {
            parts.add("<h3>KPIs</h3>");
            parts.add(kpiTable(Json.asMap(document.get("kpis"))));
        }
        if (!Json.asList(document.get("repetitions")).isEmpty()) {
            List<Map<String, Object>> repetitions = new ArrayList<>();
            for (Object item : Json.asList(document.get("repetitions"))) {
                repetitions.add(Json.asMap(item));
            }
            parts.add("<h3>Repetitions</h3>");
            parts.add(repetitionTable(repetitions));
            for (String chart : repetitionCharts(document)) {
                parts.add("<figure>" + chart + "</figure>");
            }
        }
        parts.add("<h3>Environment</h3>");
        parts.add(environmentTable(document));
        return String.join("\n", parts);
    }

    private static String comparisonTable(List<Entry> entries) {
        Entry baseline = entries.stream().filter(entry -> Boolean.TRUE.equals(entry.document().get("measurement_valid")))
                .findFirst().orElse(null);
        StringBuilder rows = new StringBuilder();
        for (Entry entry : entries) {
            Map<String, Object> document = entry.document();
            boolean valid = Boolean.TRUE.equals(document.get("measurement_valid"));
            Object throughput = dig(document, "aggregate", "throughput");
            boolean aggregated = throughput instanceof Map<?, ?>;
            Map<String, Object> metric = Json.asMap(throughput);
            Object median = aggregated ? metric.get("median") : dig(document, "kpis", "throughput");
            String interval = aggregated ? intervalText(metric) : UNKNOWN;
            String outcome;
            if (!valid) {
                List<String> reasons = new ArrayList<>();
                for (Object reason : Json.asList(document.get("invalid_reasons"))) {
                    reasons.add(String.valueOf(reason));
                }
                outcome = "not comparable: " + (reasons.isEmpty() ? UNKNOWN : String.join("; ", reasons));
            } else if (baseline == null) {
                outcome = UNKNOWN;
            } else if (baseline.document() == document) {
                outcome = "baseline";
            } else {
                outcome = String.valueOf(Compare.compare(baseline.document(), document).getOrDefault("outcome", UNKNOWN));
            }
            Object runtime = document.get("runtime");
            Object collector = document.get("requested_collector") != null
                    ? document.get("requested_collector") : document.get("gc");
            rows.append("<tr>")
                    .append("<td>").append(escape(entry.label())).append("</td>")
                    .append(textCell(runtime))
                    .append(textCell(collector))
                    .append("<td>").append(valid ? "yes" : "no").append("</td>")
                    .append(cell(valid ? median : null, true))
                    .append("<td>").append(valid ? interval : UNKNOWN).append("</td>")
                    .append("<td>").append(escape(outcome)).append("</td>")
                    .append("</tr>");
        }
        return "<h2>Comparison</h2><table><tr><th>Run</th><th>Runtime</th><th>Collector</th><th>Valid</th>"
                + "<th>Throughput median (req/s)</th><th>Throughput 95% CI</th>"
                + "<th>Outcome vs. baseline</th></tr>" + rows + "</table>";
    }
}
