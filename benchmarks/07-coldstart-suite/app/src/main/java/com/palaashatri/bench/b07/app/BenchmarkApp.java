package com.palaashatri.bench.b07.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class BenchmarkApp {
    private static final String BENCHMARK = "07-coldstart-suite";
    private BenchmarkApp() { }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parse(args);
        if (args.length == 1 && args[0].chars().allMatch(Character::isDigit)) {
            new MiniHttpServer(BENCHMARK, "Coldstart Function Suite").start(Integer.parseInt(args[0]));
            return;
        }
        if (opts.containsKey("server")) {
            new MiniHttpServer(BENCHMARK, "Coldstart Function Suite").start(Integer.parseInt(opts.getOrDefault("port", "8080")));
            return;
        }

        long start = System.nanoTime();
        String function = opts.getOrDefault("function", "json-transform");
        long seed = Long.parseLong(opts.getOrDefault("seed", "424242"));
        String payload = opts.getOrDefault("payload", "local-smoke");
        String result = invoke(function, seed, payload, start);
        Path outDir = Path.of(opts.getOrDefault("out", "build/output"));
        Files.createDirectories(outDir);
        Files.writeString(outDir.resolve("result.json"), result + System.lineSeparator());
        System.out.println(result);
    }

    private static String invoke(String function, long seed, String payload, long startNanos) {
        long checksum = deterministic(function + ":" + payload, seed);
        long elapsedMs = Math.max(1L, (System.nanoTime() - startNanos) / 1_000_000L);
        return switch (function) {
            case "json-transform" -> "{\"benchmark\":\"" + BENCHMARK + "\",\"function\":\"json-transform\",\"status\":\"OK\",\"uppercase\":\"" + escape(payload.toUpperCase(java.util.Locale.ROOT)) + "\",\"checksum\":" + checksum + ",\"elapsed_ms\":" + elapsedMs + "}";
            case "thumbnail-stub" -> "{\"benchmark\":\"" + BENCHMARK + "\",\"function\":\"thumbnail-stub\",\"status\":\"OK\",\"width\":" + (64 + checksum % 512) + ",\"height\":" + (64 + (checksum / 7) % 512) + ",\"checksum\":" + checksum + ",\"elapsed_ms\":" + elapsedMs + "}";
            case "crud-tiny" -> "{\"benchmark\":\"" + BENCHMARK + "\",\"function\":\"crud-tiny\",\"status\":\"OK\",\"created\":1,\"read\":1,\"updated\":1,\"deleted\":1,\"checksum\":" + checksum + ",\"elapsed_ms\":" + elapsedMs + "}";
            case "lightweight-infer" -> "{\"benchmark\":\"" + BENCHMARK + "\",\"function\":\"lightweight-infer\",\"status\":\"OK\",\"class_id\":" + (checksum % 5) + ",\"confidence\":" + String.format(java.util.Locale.ROOT, "%.4f", 0.5D + (checksum % 5000) / 10000.0D) + ",\"checksum\":" + checksum + ",\"elapsed_ms\":" + elapsedMs + "}";
            default -> throw new IllegalArgumentException("unknown function: " + function);
        };
    }

    private static long deterministic(String value, long seed) {
        long h = 1125899906842597L ^ seed;
        for (int i = 0; i < value.length(); i++) {
            h = 31L * h + value.charAt(i);
        }
        return Math.floorMod(h, 1_000_000_007L);
    }

    private static String escape(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
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
