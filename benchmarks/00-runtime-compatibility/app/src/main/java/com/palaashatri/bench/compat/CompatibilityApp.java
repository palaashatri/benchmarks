package com.palaashatri.bench.compat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Java 8 bytecode compatibility workload used unchanged across JDK 8-25. */
public final class CompatibilityApp {
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong workUnits = new AtomicLong();
    private final AtomicLong allocatedBytes = new AtomicLong();

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().containsKey("PORT")
                ? System.getenv("PORT") : (args.length > 0 ? args[0] : "8080"));
        new CompatibilityApp().start(port);
    }

    private void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 256);
        server.createContext("/health", this::health);
        server.createContext("/runtime", this::runtime);
        server.createContext("/metrics", this::metrics);
        server.createContext("/work", this::work);
        server.setExecutor(Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors())));
        server.start();
        System.out.println("{\"event\":\"started\",\"benchmark\":\"00-runtime-compatibility\",\"port\":"
                + port + ",\"pid\":" + pid() + "}");
    }

    private void health(HttpExchange exchange) throws IOException {
        json(exchange, 200, "{\"status\":\"UP\",\"bytecode_target\":8}");
    }

    private void runtime(HttpExchange exchange) throws IOException {
        json(exchange, 200, "{\"pid\":" + pid()
                + ",\"run_token\":\"" + escape(environment("BENCH_RUN_TOKEN")) + "\""
                + ",\"java_version\":\"" + escape(System.getProperty("java.version")) + "\""
                + ",\"java_vendor\":\"" + escape(System.getProperty("java.vendor")) + "\""
                + ",\"vm_name\":\"" + escape(System.getProperty("java.vm.name")) + "\""
                + ",\"bytecode_target\":8}");
    }

    private void work(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        byte[] body = readAll(exchange);
        long seed = parseSeed(body, requests.get());
        int size = parseSize(body, 4096);
        if (size < 128 || size > 262144) {
            json(exchange, 400, "{\"error\":\"size_out_of_range\"}");
            return;
        }
        try {
            Result result = execute(seed, size);
            workUnits.addAndGet(size);
            allocatedBytes.addAndGet(result.allocated);
            json(exchange, 200, "{\"checksum\":\"" + result.checksum + "\",\"size\":" + size
                    + ",\"compressed_bytes\":" + result.compressedBytes + ",\"bytecode_target\":8}");
        } catch (Exception exception) {
            json(exchange, 500, "{\"error\":\"work_failed\",\"message\":\"" + escape(exception.toString()) + "\"}");
        }
    }

    private void metrics(HttpExchange exchange) throws IOException {
        String body = "# TYPE compatibility_requests_total counter\ncompatibility_requests_total " + requests.get() + "\n"
                + "# TYPE compatibility_work_units_total counter\ncompatibility_work_units_total " + workUnits.get() + "\n"
                + "# TYPE compatibility_allocated_bytes_estimate_total counter\ncompatibility_allocated_bytes_estimate_total " + allocatedBytes.get() + "\n";
        bytes(exchange, 200, "text/plain; version=0.0.4", body.getBytes(StandardCharsets.UTF_8));
    }

    private static Result execute(long seed, int size) throws Exception {
        Random random = new Random(seed);
        int[] values = new int[size];
        byte[] raw = new byte[size * 4];
        for (int i = 0; i < size; i++) {
            int value = random.nextInt(); values[i] = value;
            int at = i * 4; raw[at] = (byte) value; raw[at + 1] = (byte) (value >>> 8);
            raw[at + 2] = (byte) (value >>> 16); raw[at + 3] = (byte) (value >>> 24);
        }
        Arrays.sort(values);
        Deflater deflater = new Deflater(1);
        deflater.setInput(raw); deflater.finish();
        byte[] compressed = new byte[raw.length + 128];
        int compressedLength = deflater.deflate(compressed); deflater.end();
        Inflater inflater = new Inflater(); inflater.setInput(compressed, 0, compressedLength);
        byte[] restored = new byte[raw.length]; int restoredLength = inflater.inflate(restored); inflater.end();
        if (restoredLength != raw.length || !Arrays.equals(raw, restored)) throw new IllegalStateException("compression round-trip mismatch");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(restored);
        for (int value : values) {
            digest.update((byte) value); digest.update((byte) (value >>> 8));
            digest.update((byte) (value >>> 16)); digest.update((byte) (value >>> 24));
        }
        return new Result(hex(digest.digest()), compressedLength, raw.length + compressed.length + restored.length + values.length * 4L);
    }

    private static byte[] readAll(HttpExchange exchange) throws IOException {
        byte[] buffer = new byte[4096]; ByteArrayOutputStream out = new ByteArrayOutputStream(); int read;
        while ((read = exchange.getRequestBody().read(buffer)) >= 0) out.write(buffer, 0, read);
        return out.toByteArray();
    }
    private static long parseSeed(byte[] body, long fallback) { return number(body, "seed", fallback); }
    private static int parseSize(byte[] body, int fallback) { long value = number(body, "size", fallback); return value > Integer.MAX_VALUE ? fallback : (int) value; }
    private static long number(byte[] body, String name, long fallback) {
        String text = new String(body, StandardCharsets.UTF_8), key = "\"" + name + "\""; int at = text.indexOf(key); if (at < 0) return fallback;
        int colon = text.indexOf(':', at + key.length()); if (colon < 0) return fallback; int start = colon + 1;
        while (start < text.length() && Character.isWhitespace(text.charAt(start))) start++; int end = start;
        while (end < text.length() && (Character.isDigit(text.charAt(end)) || text.charAt(end) == '-')) end++;
        try { return Long.parseLong(text.substring(start, end)); } catch (RuntimeException ignored) { return fallback; }
    }
    private static String environment(String name) { String value = System.getenv(name); return value == null ? "" : value; }
    private static long pid() { try { return Long.parseLong(ManagementFactory.getRuntimeMXBean().getName().split("@")[0]); } catch (RuntimeException ignored) { return -1; } }
    private static String hex(byte[] bytes) { StringBuilder out = new StringBuilder(bytes.length * 2); for (byte value : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 255)); return out.toString(); }
    private static String escape(String value) { return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"); }
    private static void json(HttpExchange exchange, int status, String body) throws IOException { bytes(exchange, status, "application/json", body.getBytes(StandardCharsets.UTF_8)); }
    private static void bytes(HttpExchange exchange, int status, String type, byte[] body) throws IOException { exchange.getResponseHeaders().set("Content-Type", type); exchange.sendResponseHeaders(status, body.length); OutputStream output = exchange.getResponseBody(); try { output.write(body); } finally { output.close(); } }
    static final class Result { final String checksum; final int compressedBytes; final long allocated; Result(String checksum, int compressedBytes, long allocated) { this.checksum = checksum; this.compressedBytes = compressedBytes; this.allocated = allocated; } }
}
