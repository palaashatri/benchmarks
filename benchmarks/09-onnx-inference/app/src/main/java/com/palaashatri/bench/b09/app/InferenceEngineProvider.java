package com.palaashatri.bench.b09.app;

/**
 * Supplies an engine that replaces the Java fallback. Implementations live in the
 * Maven ONNX lane (`src/onnx/java`) and are found with {@link java.util.ServiceLoader};
 * the dependency-free smoke build never sees one.
 */
public interface InferenceEngineProvider {
    InferenceEngine create(ClassifierWeights weights) throws Exception;
}
