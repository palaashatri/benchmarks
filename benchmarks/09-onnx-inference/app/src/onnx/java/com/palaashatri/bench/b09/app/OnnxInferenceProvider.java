package com.palaashatri.bench.b09.app;

/**
 * Builds the bundled classifier graph and opens an ONNX Runtime session over it.
 * A session that fails to load is a startup error, never a silent fallback.
 */
public final class OnnxInferenceProvider implements InferenceEngineProvider {
    public OnnxInferenceProvider() { }

    @Override
    public InferenceEngine create(ClassifierWeights weights) throws Exception {
        return new OnnxInferenceEngine(OnnxModelBuilder.build(weights));
    }
}
