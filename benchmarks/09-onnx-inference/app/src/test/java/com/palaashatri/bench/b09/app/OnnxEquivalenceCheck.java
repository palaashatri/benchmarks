package com.palaashatri.bench.b09.app;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.Random;
import java.util.ServiceLoader;

/**
 * Maven ONNX-lane check. The ONNX Runtime session must match the Java reference engine
 * on random inputs, and the HTTP service must name the session as the engine that ran.
 * Any failed check throws, so `mvn test` fails.
 */
public final class OnnxEquivalenceCheck {
    private static final double TOLERANCE = 1e-5;
    private static final int SAMPLES = 1000;

    private OnnxEquivalenceCheck() { }

    public static void main(String[] args) throws Exception {
        ClassifierWeights weights = ClassifierWeights.seeded();
        InferenceEngine reference = new JavaFallbackEngine(weights);
        Optional<InferenceEngineProvider> provider = ServiceLoader.load(InferenceEngineProvider.class).findFirst();
        check(provider.isPresent(), "the ONNX lane must register an InferenceEngineProvider");

        try (InferenceEngine onnx = provider.get().create(weights)) {
            check("onnx-runtime".equals(onnx.mode()), "the ONNX engine must report onnx-runtime");
            Random random = new Random(7);
            double worst = 0;
            int disagreements = 0;
            for (int sample = 0; sample < SAMPLES; sample++) {
                float[] input = new float[ClassifierWeights.INPUTS];
                for (int i = 0; i < input.length; i++) input[i] = (float) (random.nextGaussian() * 2);
                float[] expected = reference.infer(input);
                float[] actual = onnx.infer(input);
                for (int k = 0; k < expected.length; k++) worst = Math.max(worst, Math.abs(expected[k] - actual[k]));
                if (argmax(expected) != argmax(actual)) disagreements++;
            }
            check(worst < TOLERANCE, "ONNX output deviates from the Java reference by " + worst);
            check(disagreements == 0, disagreements + " class disagreements over " + SAMPLES + " samples");
            System.out.printf("onnx equivalence: max abs deviation %.3g over %d samples%n", worst, SAMPLES);
        }

        MiniHttpServer service = new MiniHttpServer();
        service.start(0);
        try {
            check("onnx-runtime".equals(service.mode()), "the HTTP service must run the ONNX engine");
            HttpClient http = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + service.port();
            String health = send(http, HttpRequest.newBuilder(URI.create(base + "/api/v1/inference/health")).build());
            check(health.contains("\"mode\":\"onnx-runtime\"") && health.contains("\"onnx_session_active\":true"),
                    "health must report the live session: " + health);
            String classify = send(http, HttpRequest.newBuilder(URI.create(base + "/api/v1/inference/classify"))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"features\":[5.1,3.5,1.4,0.2]}")).build());
            check(classify.contains("\"mode\":\"onnx-runtime\"") && classify.contains("\"onnx_session_active\":true"),
                    "classify must report the live session: " + classify);
        } finally {
            service.close();
        }
        System.out.println("onnx equivalence checks passed");
    }

    private static String send(HttpClient http, HttpRequest request) throws Exception {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        check(response.statusCode() == 200, "unexpected HTTP " + response.statusCode() + " for " + request.uri());
        return response.body();
    }

    private static int argmax(float[] values) {
        int best = 0;
        for (int i = 1; i < values.length; i++) if (values[i] > values[best]) best = i;
        return best;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
