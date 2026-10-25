package com.palaashatri.bench.b09.app;

/** Reference implementation of the classifier in plain Java. Used when no ONNX lane is present. */
public final class JavaFallbackEngine implements InferenceEngine {
    private final ClassifierWeights weights;

    public JavaFallbackEngine(ClassifierWeights weights) {
        this.weights = weights;
    }

    @Override
    public String mode() {
        return "java-fallback";
    }

    @Override
    public float[] infer(float[] input) {
        float[] hidden = new float[ClassifierWeights.HIDDEN];
        for (int row = 0; row < hidden.length; row++) {
            float value = 0;
            for (int column = 0; column < ClassifierWeights.INPUTS; column++) {
                value += weights.hidden()[row][column] * input[column];
            }
            hidden[row] = Math.max(0, value);
        }
        float[] output = new float[ClassifierWeights.CLASSES];
        for (int row = 0; row < output.length; row++) {
            for (int column = 0; column < hidden.length; column++) {
                output[row] += weights.output()[row][column] * hidden[column];
            }
        }
        float max = Math.max(output[0], Math.max(output[1], output[2]));
        float sum = 0;
        for (int i = 0; i < output.length; i++) {
            output[i] = (float) Math.exp(output[i] - max);
            sum += output[i];
        }
        for (int i = 0; i < output.length; i++) output[i] /= sum;
        return output;
    }
}
