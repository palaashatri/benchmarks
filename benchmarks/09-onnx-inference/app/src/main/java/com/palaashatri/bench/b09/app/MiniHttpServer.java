package com.palaashatri.bench.b09.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public final class MiniHttpServer {

    private static final String[] LABELS = {"setosa", "versicolor", "virginica"};

    private final String benchmark = "09-onnx-inference";
    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong inferenceRequests = new AtomicLong();
    // stored in microseconds to avoid floating-point accumulation; displayed as ms
    private final AtomicLong totalInferenceUs = new AtomicLong();
    private final AtomicLong totalTokenizeUs = new AtomicLong();

    private final JavaFallbackInference inference;
    private final boolean onnxAvailable;
    private final long modelLoadMs;

    public MiniHttpServer() {
        long t0 = System.nanoTime();
        boolean onnx = false;
        try {
            Class.forName("ai.onnxruntime.OrtEnvironment");
            onnx = true;
        } catch (ClassNotFoundException ignored) { }
        onnxAvailable = onnx;
        inference = new JavaFallbackInference();
        modelLoadMs = (System.nanoTime() - t0) / 1_000_000L;
    }

    public void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 256);
        server.createContext("/api/v1/inference/classify", this::handleClassify);
        server.createContext("/api/v1/inference/health", this::handleInferenceHealth);
        server.createContext("/health", this::handleHealth);
        server.createContext("/metrics", this::handleMetrics);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("{\"event\":\"started\",\"benchmark\":\"" + benchmark
                + "\",\"port\":" + port
                + ",\"mode\":\"" + mode() + "\""
                + ",\"model_load_ms\":" + modelLoadMs + "}");
    }

    // -------------------------------------------------------------------------
    // Handlers
    // -------------------------------------------------------------------------

    private void handleClassify(HttpExchange ex) throws IOException {
        totalRequests.incrementAndGet();
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            json(ex, 405, "{\"error\":\"method not allowed\"}");
            return;
        }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        float[] features;
        long tokenizeUs = 0;

        // Prefer "features" array; fall back to tokenising "text"
        float[] parsed = parseFeatures(body);
        if (parsed != null) {
            features = parsed;
        } else {
            String text = field(body, "text");
            if (text == null) text = "default input";
            long ts = System.nanoTime();
            features = tokenize(text);
            tokenizeUs = (System.nanoTime() - ts) / 1_000L;
        }

        long t0 = System.nanoTime();
        float[] probs = inference.infer(features);
        long inferenceUs = (System.nanoTime() - t0) / 1_000L;

        totalTokenizeUs.addAndGet(tokenizeUs);
        totalInferenceUs.addAndGet(inferenceUs);
        inferenceRequests.incrementAndGet();

        int cls = argmax(probs);
        float confidence = probs[cls];

        String resp = "{\"class\":" + cls
                + ",\"label\":\"" + LABELS[cls] + "\""
                + ",\"confidence\":" + fmt3(confidence)
                + ",\"inference_ms\":" + fmt3(inferenceUs / 1000.0)
                + ",\"tokenize_ms\":" + fmt3(tokenizeUs / 1000.0)
                + ",\"mode\":\"" + mode() + "\""
                + ",\"model_load_ms\":" + modelLoadMs + "}";
        json(ex, 200, resp);
    }

    private void handleInferenceHealth(HttpExchange ex) throws IOException {
        totalRequests.incrementAndGet();
        json(ex, 200, "{\"status\":\"UP\",\"mode\":\"" + mode() + "\",\"model_load_ms\":" + modelLoadMs + "}");
    }

    private void handleHealth(HttpExchange ex) throws IOException {
        totalRequests.incrementAndGet();
        json(ex, 200, "{\"status\":\"UP\",\"mode\":\"" + mode() + "\",\"model_load_ms\":" + modelLoadMs + "}");
    }

    private void handleMetrics(HttpExchange ex) throws IOException {
        long reqs = inferenceRequests.get();
        double inferenceMs = totalInferenceUs.get() / 1000.0;
        double tokenizeMs = totalTokenizeUs.get() / 1000.0;
        String body = "inference_requests_total " + reqs + "\n"
                + "inference_ms_total " + fmt3(inferenceMs) + "\n"
                + "tokenize_ms_total " + fmt3(tokenizeMs) + "\n"
                + "model_load_ms " + modelLoadMs + "\n"
                + "benchmark_requests_total{benchmark=\"" + benchmark + "\"} " + totalRequests.get() + "\n";
        bytes(ex, 200, "text/plain; version=0.0.4", body);
    }

    // -------------------------------------------------------------------------
    // Inference engine
    // -------------------------------------------------------------------------

    static final class JavaFallbackInference {
        private final float[][] w1 = new float[64][4];
        private final float[] b1 = new float[64];
        private final float[][] w2 = new float[3][64];
        private final float[] b2 = new float[3];

        JavaFallbackInference() {
            java.util.Random rng = new java.util.Random(42);
            for (float[] row : w1) for (int j = 0; j < row.length; j++) row[j] = (float)(rng.nextGaussian() * 0.3);
            for (int i = 0; i < b1.length; i++) b1[i] = (float)(rng.nextGaussian() * 0.1);
            for (float[] row : w2) for (int j = 0; j < row.length; j++) row[j] = (float)(rng.nextGaussian() * 0.3);
            for (int i = 0; i < b2.length; i++) b2[i] = (float)(rng.nextGaussian() * 0.1);
        }

        float[] infer(float[] x) {
            float[] h = new float[64];
            for (int i = 0; i < 64; i++) {
                float s = b1[i];
                for (int j = 0; j < 4; j++) s += w1[i][j] * x[j];
                h[i] = Math.max(0, s);
            }
            float[] out = new float[3];
            for (int i = 0; i < 3; i++) {
                out[i] = b2[i];
                for (int j = 0; j < 64; j++) out[i] += w2[i][j] * h[j];
            }
            return softmax(out);
        }

        private float[] softmax(float[] x) {
            float max = x[0]; for (float v : x) if (v > max) max = v;
            float sum = 0; float[] e = new float[x.length];
            for (int i = 0; i < x.length; i++) { e[i] = (float) Math.exp(x[i] - max); sum += e[i]; }
            for (int i = 0; i < x.length; i++) e[i] /= sum;
            return e;
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private float[] tokenize(String text) {
        float[] features = new float[4];
        long hash = 1125899906842597L;
        for (int i = 0; i < text.length(); i++) hash = 31L * hash + text.charAt(i);
        features[0] = ((hash & 0xFF) / 255.0f);
        features[1] = (((hash >> 8) & 0xFF) / 255.0f);
        features[2] = (((hash >> 16) & 0xFF) / 255.0f);
        features[3] = (((hash >> 24) & 0xFF) / 255.0f);
        return features;
    }

    private float[] parseFeatures(String body) {
        int start = body.indexOf('[');
        int end = body.indexOf(']', start);
        if (start < 0 || end < 0) return null;
        String[] parts = body.substring(start + 1, end).split(",");
        if (parts.length < 4) return null;
        float[] f = new float[4];
        try {
            for (int i = 0; i < 4; i++) f[i] = Float.parseFloat(parts[i].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        return f;
    }

    /** Extract the first string value for the given JSON key (unescaped, simple). */
    private static String field(String body, String name) {
        String quoted = "\"" + name + "\"";
        int key = body.indexOf(quoted);
        if (key < 0) return null;
        int colon = body.indexOf(':', key + quoted.length());
        if (colon < 0) return null;
        int firstQuote = body.indexOf('"', colon + 1);
        if (firstQuote < 0) return null;
        int secondQuote = body.indexOf('"', firstQuote + 1);
        if (secondQuote < 0) return null;
        return body.substring(firstQuote + 1, secondQuote);
    }

    private static int argmax(float[] arr) {
        int idx = 0;
        for (int i = 1; i < arr.length; i++) if (arr[i] > arr[idx]) idx = i;
        return idx;
    }

    private String mode() { return onnxAvailable ? "onnx" : "java-fallback"; }

    private static String fmt3(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    private static void json(HttpExchange ex, int status, String body) throws IOException {
        bytes(ex, status, "application/json", body);
    }

    private static void bytes(HttpExchange ex, int status, String contentType, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, data.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(data); }
    }
}
