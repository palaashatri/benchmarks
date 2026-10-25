package com.palaashatri.bench.b09.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Classifier HTTP service.
 *
 * <p>The engine is an ONNX Runtime session when the Maven ONNX lane supplies a provider
 * and the session loads; otherwise the Java reference engine runs. A missing provider is
 * reported as {@code java-fallback}, never as ONNX. Every response names the engine that
 * computed it, and {@code onnx_session_active} is true only for a live session.
 */
public final class MiniHttpServer implements AutoCloseable {
    private static final String BENCHMARK = "09-onnx-inference";
    private static final String[] LABELS = {"setosa", "versicolor", "virginica"};
    private static final String ONNX_MODE = "onnx-runtime";

    private final InferenceEngine engine;
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong inferenceRequests = new AtomicLong();
    private final AtomicLong inferenceNanos = new AtomicLong();
    private HttpServer server;

    public MiniHttpServer() throws Exception {
        this.engine = selectEngine();
    }

    static InferenceEngine selectEngine() throws Exception {
        ClassifierWeights weights = ClassifierWeights.seeded();
        Optional<InferenceEngineProvider> provider = ServiceLoader.load(InferenceEngineProvider.class).findFirst();
        return provider.isPresent() ? provider.get().create(weights) : new JavaFallbackEngine(weights);
    }

    public void start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 256);
        server.createContext("/health", this::health);
        server.createContext("/runtime", this::runtime);
        server.createContext("/metrics", this::metrics);
        server.createContext("/api/v1/inference/health", this::health);
        server.createContext("/api/v1/inference/classify", this::classify);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.printf("{\"event\":\"started\",\"benchmark\":\"%s\",\"mode\":\"%s\",\"port\":%d,\"pid\":%d}%n",
                BENCHMARK, engine.mode(), port(), ProcessHandle.current().pid());
    }

    /** The bound HTTP port, valid after {@link #start(int)}. */
    public int port() {
        return server.getAddress().getPort();
    }

    /** The engine that computes classifications; its mode is what responses report. */
    public String mode() {
        return engine.mode();
    }

    @Override
    public synchronized void close() {
        if (server != null) server.stop(0);
        engine.close();
    }

    private boolean onnxSessionActive() {
        return ONNX_MODE.equals(engine.mode());
    }

    private void health(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        json(exchange, 200, "{\"status\":\"UP\",\"mode\":\"" + engine.mode() + "\""
                + ",\"onnx_session_active\":" + onnxSessionActive() + "}");
    }

    private void runtime(HttpExchange exchange) throws IOException {
        json(exchange, 200, "{\"pid\":" + ProcessHandle.current().pid()
                + ",\"run_token\":\"" + escape(System.getenv().getOrDefault("BENCH_RUN_TOKEN", "")) + "\""
                + ",\"java_version\":\"" + escape(System.getProperty("java.version")) + "\""
                + ",\"vm_name\":\"" + escape(System.getProperty("java.vm.name")) + "\""
                + ",\"mode\":\"" + engine.mode() + "\"}");
    }

    private void metrics(HttpExchange exchange) throws IOException {
        String mode = engine.mode();
        String body = "# TYPE inference_requests_total counter\n"
                + "inference_requests_total{mode=\"" + mode + "\"} " + inferenceRequests.get() + "\n"
                + "# TYPE inference_duration_seconds_sum counter\n"
                + "inference_duration_seconds_sum{mode=\"" + mode + "\"} "
                + format(inferenceNanos.get() / 1_000_000_000.0) + "\n"
                + "# TYPE onnx_session_active gauge\nonnx_session_active " + (onnxSessionActive() ? 1 : 0) + "\n"
                + "# TYPE benchmark_requests_total counter\nbenchmark_requests_total{benchmark=\""
                + BENCHMARK + "\"} " + requests.get() + "\n";
        bytes(exchange, 200, "text/plain; version=0.0.4", body);
    }

    private void classify(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            json(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        float[] features = parseFeatures(body);
        if (features == null) {
            features = tokenize(stringField(body, "text", "default input"));
        }
        long started = System.nanoTime();
        float[] probabilities = engine.infer(features);
        long duration = System.nanoTime() - started;
        inferenceRequests.incrementAndGet();
        inferenceNanos.addAndGet(duration);
        int classification = argmax(probabilities);
        json(exchange, 200, "{\"class\":" + classification
                + ",\"label\":\"" + LABELS[classification] + "\""
                + ",\"confidence\":" + format(probabilities[classification])
                + ",\"inference_ms\":" + format(duration / 1_000_000.0)
                + ",\"mode\":\"" + engine.mode() + "\""
                + ",\"onnx_session_active\":" + onnxSessionActive() + "}");
    }

    private static float[] parseFeatures(String body) {
        int start = body.indexOf('['), end = body.indexOf(']', start);
        if (start < 0 || end < 0) return null;
        String[] values = body.substring(start + 1, end).split(",");
        if (values.length != 4) return null;
        float[] result = new float[4];
        try {
            for (int i = 0; i < result.length; i++) result[i] = Float.parseFloat(values[i].trim());
            return result;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static float[] tokenize(String text) {
        long hash = 1125899906842597L;
        for (int i = 0; i < text.length(); i++) hash = 31 * hash + text.charAt(i);
        return new float[]{(hash & 255) / 255f, ((hash >>> 8) & 255) / 255f,
                ((hash >>> 16) & 255) / 255f, ((hash >>> 24) & 255) / 255f};
    }

    private static String stringField(String body, String name, String fallback) {
        String key = "\"" + name + "\"";
        int keyAt = body.indexOf(key), colon = body.indexOf(':', keyAt + key.length());
        int start = body.indexOf('"', colon + 1), end = start < 0 ? -1 : body.indexOf('"', start + 1);
        return keyAt < 0 || colon < 0 || start < 0 || end < 0 ? fallback : body.substring(start + 1, end);
    }

    private static int argmax(float[] values) {
        int best = 0;
        for (int i = 1; i < values.length; i++) if (values[i] > values[best]) best = i;
        return best;
    }

    private static String format(double value) { return String.format(java.util.Locale.ROOT, "%.6f", value); }
    private static String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
    private static void json(HttpExchange exchange, int status, String body) throws IOException { bytes(exchange, status, "application/json", body); }
    private static void bytes(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(payload); }
    }
}
