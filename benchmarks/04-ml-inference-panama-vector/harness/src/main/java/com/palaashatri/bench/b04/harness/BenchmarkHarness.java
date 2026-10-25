package com.palaashatri.bench.b04.harness;

import com.palaashatri.bench.smoke.SmokeHarness;
import com.palaashatri.bench.smoke.SmokeHarness.Request;

/** Functional client for the inference routes. The Vector and FFM paths run only inside the application. */
public final class BenchmarkHarness {
    private BenchmarkHarness() { }

    private static final String FEATURES = "{\"features\":[0.1,0.2,0.3,0.4,0.5,0.6,0.7,0.8,0.9,1.0,0.1,0.2,0.3,0.4,0.5,0.6]}";

    public static void main(String[] args) throws Exception {
        SmokeHarness.run("04-ml-inference-panama-vector", args,
                Request.post("/api/v1/inference", FEATURES),
                Request.post("/api/v1/inference/scalar", FEATURES),
                Request.get("/health"));
    }
}
