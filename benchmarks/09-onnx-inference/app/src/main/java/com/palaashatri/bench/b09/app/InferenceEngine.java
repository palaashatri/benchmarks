package com.palaashatri.bench.b09.app;

/**
 * A classifier that actually runs. {@link #mode()} names the engine that computed the
 * result, so reports can never attribute ONNX Runtime work to the Java fallback or the
 * reverse.
 */
public interface InferenceEngine extends AutoCloseable {
    /** {@code "onnx-runtime"} for a live ONNX Runtime session, {@code "java-fallback"} otherwise. */
    String mode();

    /** Returns the class probabilities for one 4-feature input. */
    float[] infer(float[] input);

    /** Releases native resources held by the engine; a no-op for pure Java. */
    @Override
    default void close() { }
}
