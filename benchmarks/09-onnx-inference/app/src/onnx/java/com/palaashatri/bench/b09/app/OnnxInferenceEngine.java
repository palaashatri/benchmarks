package com.palaashatri.bench.b09.app;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.nio.FloatBuffer;
import java.util.Collections;

/** The classifier executed by a live ONNX Runtime session over the bundled graph. */
final class OnnxInferenceEngine implements InferenceEngine {
    private final OrtEnvironment environment;
    private final OrtSession session;

    OnnxInferenceEngine(byte[] model) throws OrtException {
        this.environment = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            this.session = environment.createSession(model, options);
        }
    }

    @Override
    public String mode() {
        return "onnx-runtime";
    }

    @Override
    public float[] infer(float[] input) {
        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), new long[]{1, input.length});
             OrtSession.Result result = session.run(Collections.singletonMap(OnnxModelBuilder.INPUT, tensor))) {
            float[][] probabilities = (float[][]) result.get(OnnxModelBuilder.OUTPUT).orElseThrow().getValue();
            return probabilities[0].clone();
        } catch (OrtException exception) {
            throw new IllegalStateException("ONNX Runtime inference failed", exception);
        }
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (OrtException exception) {
            throw new IllegalStateException("ONNX Runtime session did not close cleanly", exception);
        }
    }
}
