package com.palaashatri.bench.b08.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
        long records = Long.parseLong(opts.getOrDefault("records", "1000"));
        long checksum = 0L;
        for (long i = 0; i < records; i++) {
            checksum += (i * 31L) ^ BENCHMARK.hashCode();
        }
        long elapsedMs = Math.max(1L, (System.nanoTime() - start) / 1_000_000L);
        Path outDir = Path.of(opts.getOrDefault("out", "build/output"));
        Files.createDirectories(outDir);
        String result = """
                {"benchmark":"%s","records":%d,"checksum":%d,"elapsed_ms":%d}
                """.formatted(BENCHMARK, records, checksum, elapsedMs).trim();
        Files.writeString(outDir.resolve("result.json"), result + System.lineSeparator());
        System.out.println(result);
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
}
