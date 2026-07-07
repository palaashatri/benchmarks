package com.palaashatri.bench.b04.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

public final class MiniHttpServer {
    private static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;

    // Neural network weight matrices (pre-generated at startup, seed=42)
    // W1: [64 x 16] (64 rows, 16 cols)  — hidden layer weights
    // W2: [3 x 64]  (3 rows, 64 cols)   — output layer weights
    private static final int INPUT_DIM  = 16;
    private static final int HIDDEN_DIM = 64;
    private static final int OUTPUT_DIM = 3;

    private final float[][] w1 = new float[HIDDEN_DIM][INPUT_DIM];
    private final float[][] w2 = new float[OUTPUT_DIM][HIDDEN_DIM];

    private final String benchmark;
    private final AtomicLong totalRequests   = new AtomicLong();
    private final AtomicLong simdRequests    = new AtomicLong();
    private final AtomicLong scalarRequests  = new AtomicLong();
    private final DoubleAdder simdMsTotal    = new DoubleAdder();
    private final DoubleAdder scalarMsTotal  = new DoubleAdder();

    public MiniHttpServer(String benchmark, String title) {
        this.benchmark = benchmark;
        initWeights();
    }

    // -----------------------------------------------------------------------
    // Weight initialisation
    // -----------------------------------------------------------------------

    private void initWeights() {
        Random rng = new Random(42L);
        for (int i = 0; i < HIDDEN_DIM; i++)
            for (int j = 0; j < INPUT_DIM; j++)
                w1[i][j] = (rng.nextFloat() * 2f - 1f) * 0.5f;
        for (int i = 0; i < OUTPUT_DIM; i++)
            for (int j = 0; j < HIDDEN_DIM; j++)
                w2[i][j] = (rng.nextFloat() * 2f - 1f) * 0.5f;
    }

    // -----------------------------------------------------------------------
    // Server startup
    // -----------------------------------------------------------------------

    public void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 256);
        server.createContext("/api/v1/inference/scalar", this::inferenceScalar);
        server.createContext("/api/v1/inference", this::inferenceSimd);
        server.createContext("/api/v1/health",   this::health);
        server.createContext("/health",            this::health);
        server.createContext("/actuator/health",   this::health);
        server.createContext("/metrics",           this::metrics);
        server.createContext("/actuator/prometheus", this::metrics);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        log("started", "\"port\":" + port + ",\"simd_width\":" + SPECIES.length()
                + ",\"species\":\"" + SPECIES + "\"");
    }

    // -----------------------------------------------------------------------
    // Handlers
    // -----------------------------------------------------------------------

    private void health(HttpExchange ex) throws IOException {
        json(ex, 200, "{\"status\":\"UP\",\"simd_width\":" + SPECIES.length()
                + ",\"species\":\"SPECIES_PREFERRED\"}");
    }

    private void metrics(HttpExchange ex) throws IOException {
        long req = totalRequests.get();
        String body = "# TYPE inference_requests_total counter\n"
                + "inference_requests_total " + req + "\n"
                + "# TYPE inference_simd_ms_sum gauge\n"
                + "inference_simd_ms_sum " + fmt(simdMsTotal.sum()) + "\n"
                + "# TYPE inference_scalar_ms_sum gauge\n"
                + "inference_scalar_ms_sum " + fmt(scalarMsTotal.sum()) + "\n"
                + "# TYPE simd_vector_width gauge\n"
                + "simd_vector_width " + SPECIES.length() + "\n"
                + "# TYPE benchmark_requests_total counter\n"
                + "benchmark_requests_total{benchmark=\"" + benchmark + "\"} " + req + "\n";
        bytes(ex, 200, "text/plain; version=0.0.4", body);
    }

    private void inferenceSimd(HttpExchange ex) throws IOException {
        totalRequests.incrementAndGet();
        simdRequests.incrementAndGet();
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            json(ex, 405, "{\"error\":\"method not allowed\"}"); return;
        }
        String bodyStr = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        float[] rawFeatures;
        try {
            rawFeatures = parseFeatures(bodyStr);
        } catch (IllegalArgumentException e) {
            json(ex, 400, "{\"error\":\"" + escape(e.getMessage()) + "\"}"); return;
        }

        long t0 = System.nanoTime();
        // Panama FFM demo: write features to off-heap and read back
        float[] features = offHeapRoundTrip(rawFeatures);
        // Normalise
        float[] norm = normalize(features);
        // Forward pass (SIMD)
        float[] hidden  = simdMatMulSigmoid(w1, norm, INPUT_DIM);
        float[] output  = simdMatMul(w2, hidden, HIDDEN_DIM);
        float[] probs   = softmax(output);
        long   tMs100   = (System.nanoTime() - t0) / 10_000L;  // 0.01 ms resolution
        double elapsedMs = tMs100 / 100.0;
        simdMsTotal.add(elapsedMs);

        int bestClass = argmax(probs);
        json(ex, 200, "{\"class\":" + bestClass + ",\"confidence\":"
                + fmt(probs[bestClass]) + ",\"inference_ms\":" + fmt(elapsedMs)
                + ",\"method\":\"simd\"}");
    }

    private void inferenceScalar(HttpExchange ex) throws IOException {
        totalRequests.incrementAndGet();
        scalarRequests.incrementAndGet();
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            json(ex, 405, "{\"error\":\"method not allowed\"}"); return;
        }
        String bodyStr = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        float[] rawFeatures;
        try {
            rawFeatures = parseFeatures(bodyStr);
        } catch (IllegalArgumentException e) {
            json(ex, 400, "{\"error\":\"" + escape(e.getMessage()) + "\"}"); return;
        }

        long t0 = System.nanoTime();
        float[] features = offHeapRoundTrip(rawFeatures);
        float[] norm     = normalize(features);
        float[] hidden   = scalarMatMulSigmoid(w1, norm, INPUT_DIM);
        float[] output   = scalarMatMul(w2, hidden, HIDDEN_DIM);
        float[] probs    = softmax(output);
        long   tMs100    = (System.nanoTime() - t0) / 10_000L;
        double elapsedMs = tMs100 / 100.0;
        scalarMsTotal.add(elapsedMs);

        int bestClass = argmax(probs);
        json(ex, 200, "{\"class\":" + bestClass + ",\"confidence\":"
                + fmt(probs[bestClass]) + ",\"inference_ms\":" + fmt(elapsedMs)
                + ",\"method\":\"scalar\"}");
    }

    // -----------------------------------------------------------------------
    // Panama FFM demo: round-trip features through an off-heap segment
    // -----------------------------------------------------------------------

    private static float[] offHeapRoundTrip(float[] in) {
        try (Arena arena = Arena.ofConfined()) {
            long byteSize = (long) in.length * Float.BYTES;
            MemorySegment seg = arena.allocate(byteSize, Float.BYTES);
            // Write: copy from on-heap array into off-heap segment
            MemorySegment.copy(MemorySegment.ofArray(in), ValueLayout.JAVA_FLOAT, 0L,
                               seg, ValueLayout.JAVA_FLOAT, 0L, in.length);
            // Read back into a new on-heap array
            float[] out = new float[in.length];
            MemorySegment.copy(seg, ValueLayout.JAVA_FLOAT, 0L,
                               MemorySegment.ofArray(out), ValueLayout.JAVA_FLOAT, 0L, in.length);
            return out;
        }
    }

    // -----------------------------------------------------------------------
    // Numeric kernels
    // -----------------------------------------------------------------------

    /** SIMD matrix-vector multiply followed by sigmoid on each output element. */
    private float[] simdMatMulSigmoid(float[][] w, float[] x, int cols) {
        int rows = w.length;
        float[] out = new float[rows];
        int bound = SPECIES.loopBound(cols);
        for (int i = 0; i < rows; i++) {
            float[] row = w[i];
            FloatVector acc = FloatVector.zero(SPECIES);
            int j = 0;
            for (; j < bound; j += SPECIES.length()) {
                FloatVector vr = FloatVector.fromArray(SPECIES, row, j);
                FloatVector vx = FloatVector.fromArray(SPECIES, x,   j);
                acc = acc.add(vr.mul(vx));
            }
            float sum = acc.reduceLanes(VectorOperators.ADD);
            // tail
            for (; j < cols; j++) sum += row[j] * x[j];
            out[i] = sigmoid(sum);
        }
        return out;
    }

    /** SIMD matrix-vector multiply (no activation). */
    private float[] simdMatMul(float[][] w, float[] x, int cols) {
        int rows = w.length;
        float[] out = new float[rows];
        int bound = SPECIES.loopBound(cols);
        for (int i = 0; i < rows; i++) {
            float[] row = w[i];
            FloatVector acc = FloatVector.zero(SPECIES);
            int j = 0;
            for (; j < bound; j += SPECIES.length()) {
                FloatVector vr = FloatVector.fromArray(SPECIES, row, j);
                FloatVector vx = FloatVector.fromArray(SPECIES, x,   j);
                acc = acc.add(vr.mul(vx));
            }
            float sum = acc.reduceLanes(VectorOperators.ADD);
            for (; j < cols; j++) sum += row[j] * x[j];
            out[i] = sum;
        }
        return out;
    }

    /** Scalar matrix-vector multiply with sigmoid. */
    private static float[] scalarMatMulSigmoid(float[][] w, float[] x, int cols) {
        int rows = w.length;
        float[] out = new float[rows];
        for (int i = 0; i < rows; i++) {
            float sum = 0f;
            for (int j = 0; j < cols; j++) sum += w[i][j] * x[j];
            out[i] = sigmoid(sum);
        }
        return out;
    }

    /** Scalar matrix-vector multiply (no activation). */
    private static float[] scalarMatMul(float[][] w, float[] x, int cols) {
        int rows = w.length;
        float[] out = new float[rows];
        for (int i = 0; i < rows; i++) {
            float sum = 0f;
            for (int j = 0; j < cols; j++) sum += w[i][j] * x[j];
            out[i] = sum;
        }
        return out;
    }

    private static float sigmoid(float x) {
        return 1f / (1f + (float) Math.exp(-x));
    }

    private static float[] softmax(float[] x) {
        float max = x[0];
        for (float v : x) if (v > max) max = v;
        float sum = 0f;
        float[] out = new float[x.length];
        for (int i = 0; i < x.length; i++) { out[i] = (float) Math.exp(x[i] - max); sum += out[i]; }
        for (int i = 0; i < out.length; i++) out[i] /= sum;
        return out;
    }

    private static float[] normalize(float[] x) {
        float mean = 0f; for (float v : x) mean += v; mean /= x.length;
        float var  = 0f; for (float v : x) { float d = v - mean; var += d * d; } var /= x.length;
        float std  = (float) Math.sqrt(var + 1e-8f);
        float[] out = new float[x.length];
        for (int i = 0; i < x.length; i++) out[i] = (x[i] - mean) / std;
        return out;
    }

    private static int argmax(float[] v) {
        int best = 0;
        for (int i = 1; i < v.length; i++) if (v[i] > v[best]) best = i;
        return best;
    }

    // -----------------------------------------------------------------------
    // JSON parsing (no external library)
    // -----------------------------------------------------------------------

    /**
     * Parse {"features":[f0,...,f15]} from a JSON body string.
     * Returns exactly INPUT_DIM floats or throws IllegalArgumentException.
     */
    private static float[] parseFeatures(String body) {
        // locate "features" key
        int keyIdx = body.indexOf("\"features\"");
        if (keyIdx < 0) throw new IllegalArgumentException("missing \"features\" key");
        int colon = body.indexOf(':', keyIdx);
        if (colon < 0) throw new IllegalArgumentException("malformed JSON: no colon after features");
        int arrStart = body.indexOf('[', colon);
        if (arrStart < 0) throw new IllegalArgumentException("malformed JSON: features must be an array");
        int arrEnd = body.indexOf(']', arrStart);
        if (arrEnd < 0) throw new IllegalArgumentException("malformed JSON: unclosed array");
        String inner = body.substring(arrStart + 1, arrEnd).trim();
        if (inner.isEmpty()) throw new IllegalArgumentException("features array is empty");
        String[] parts = inner.split(",");
        if (parts.length != INPUT_DIM)
            throw new IllegalArgumentException(
                    "expected " + INPUT_DIM + " features, got " + parts.length);
        float[] out = new float[INPUT_DIM];
        for (int i = 0; i < INPUT_DIM; i++) {
            try {
                out[i] = Float.parseFloat(parts[i].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid float at index " + i + ": " + parts[i].trim());
            }
        }
        return out;
    }

    // -----------------------------------------------------------------------
    // HTTP utilities
    // -----------------------------------------------------------------------

    private static void json(HttpExchange ex, int status, String body) throws IOException {
        bytes(ex, status, "application/json", body);
    }

    private static void bytes(HttpExchange ex, int status, String contentType, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, data.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(data); }
    }

    private void log(String event, String fields) {
        System.out.println("{\"event\":\"" + event + "\",\"benchmark\":\"" + benchmark + "\"," + fields + "}");
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.4f", v);
    }

    private static String escape(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"'  -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default   -> { if (c < 0x20) out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c)); else out.append(c); }
            }
        }
        return out.toString();
    }
}
