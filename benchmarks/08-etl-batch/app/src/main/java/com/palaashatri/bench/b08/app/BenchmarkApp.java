package com.palaashatri.bench.b08.app;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BenchmarkApp {
    private static final String BENCHMARK = "08-etl-batch";
    private BenchmarkApp() { }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parse(args);
        if (args.length == 1 && args[0].chars().allMatch(Character::isDigit)) {
            new MiniHttpServer(BENCHMARK, "ETL Batch Job").start(Integer.parseInt(args[0]));
            return;
        }
        if (opts.containsKey("server")) {
            new MiniHttpServer(BENCHMARK, "ETL Batch Job").start(Integer.parseInt(opts.getOrDefault("port", "8080")));
            return;
        }

        long start = System.nanoTime();
        String impl = opts.getOrDefault("impl", "single-jvm");
        Path out = Path.of(opts.getOrDefault("out", "build/output"));
        Path outDir = Files.isDirectory(out) || !out.toString().endsWith(".csv") ? out : out.getParent();
        if (outDir != null) {
            Files.createDirectories(outDir);
        }
        Path outputCsv = Files.isDirectory(out) || !out.toString().endsWith(".csv") ? out.resolve("part-00000.csv") : out;
        List<String> rows = opts.containsKey("in") ? Files.readAllLines(Path.of(opts.get("in"))) : syntheticRows(Long.parseLong(opts.getOrDefault("seed", "424242")));
        Map<String, Agg> aggregates = aggregate(rows);
        writeCsv(outputCsv, aggregates);
        long elapsedMs = Math.max(1L, (System.nanoTime() - start) / 1_000_000L);
        String result = "{\"benchmark\":\"" + BENCHMARK + "\",\"impl\":\"" + escape(impl) + "\",\"offheap\":" + Boolean.parseBoolean(opts.getOrDefault("offheap", "false")) + ",\"input_rows\":" + Math.max(0, rows.size() - 1) + ",\"output_groups\":" + aggregates.size() + ",\"output\":\"" + escape(outputCsv.toString()) + "\",\"elapsed_ms\":" + elapsedMs + "}";
        Path resultDir = Files.isDirectory(out) || !out.toString().endsWith(".csv") ? out : out.getParent();
        if (resultDir == null) {
            resultDir = Path.of(".");
        }
        Files.writeString(resultDir.resolve("result.json"), result + System.lineSeparator());
        System.out.println(result);
    }

    private static Map<String, Agg> aggregate(List<String> rows) {
        Map<String, Agg> out = new LinkedHashMap<>();
        for (String row : rows) {
            if (row.isBlank() || row.startsWith("user_id,")) {
                continue;
            }
            String[] parts = row.split(",");
            if (parts.length < 4) {
                continue;
            }
            String key = parts[0] + "," + parts[1];
            BigDecimal amount = new BigDecimal(parts[2].trim());
            out.computeIfAbsent(key, ignored -> new Agg()).add(amount);
        }
        return out;
    }

    private static List<String> syntheticRows(long seed) {
        return List.of(
                "user_id,event_day,amount,category",
                "u" + (seed % 3) + ",2026-01-01,10.25,alpha",
                "u" + (seed % 3) + ",2026-01-01,5.75,beta",
                "u" + ((seed + 1) % 3) + ",2026-01-02,3.00,alpha");
    }

    private static void writeCsv(Path outputCsv, Map<String, Agg> aggregates) throws Exception {
        StringBuilder out = new StringBuilder("user_id,event_day,total_amount,event_count\n");
        for (Map.Entry<String, Agg> entry : aggregates.entrySet()) {
            out.append(entry.getKey()).append(',')
                    .append(entry.getValue().total.setScale(2, RoundingMode.HALF_UP))
                    .append(',').append(entry.getValue().count).append('\n');
        }
        Files.writeString(outputCsv, out.toString());
    }

    private static String escape(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--")) {
                String k = a.substring(2);
                String v = "true";
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    v = args[++i];
                }
                out.put(k, v);
            }
        }
        return out;
    }

    private static final class Agg {
        private BigDecimal total = BigDecimal.ZERO;
        private long count;

        private void add(BigDecimal amount) {
            total = total.add(amount);
            count++;
        }
    }
}
