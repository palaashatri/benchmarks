package com.palaashatri.bench.b09.app;

import java.util.Random;

/**
 * Seeded weights for the 4 → 64 → 3 classifier. The Java fallback and the ONNX graph
 * are built from these same arrays, so their outputs can be compared directly.
 *
 * @param hidden  64 rows of 4 weights (first layer, row-major: [hidden][input])
 * @param output  3 rows of 64 weights (second layer, row-major: [class][hidden])
 */
public record ClassifierWeights(float[][] hidden, float[][] output) {
    public static final int INPUTS = 4;
    public static final int HIDDEN = 64;
    public static final int CLASSES = 3;

    public static ClassifierWeights seeded() {
        Random random = new Random(42);
        float[][] hidden = new float[HIDDEN][INPUTS];
        for (float[] row : hidden) for (int i = 0; i < row.length; i++) row[i] = (float) (random.nextGaussian() * .3);
        float[][] output = new float[CLASSES][HIDDEN];
        for (float[] row : output) for (int i = 0; i < row.length; i++) row[i] = (float) (random.nextGaussian() * .3);
        return new ClassifierWeights(hidden, output);
    }
}
