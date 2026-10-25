package com.palaashatri.bench.b09.harness;

import com.palaashatri.bench.smoke.SmokeHarness;
import com.palaashatri.bench.smoke.SmokeHarness.Request;

/** Functional client for the classifier routes. Load and telemetry come from the Tier 2 engine. */
public final class BenchmarkHarness {
    private BenchmarkHarness() { }

    public static void main(String[] args) throws Exception {
        SmokeHarness.run("09-onnx-inference", args,
                Request.post("/api/v1/inference/classify", "{\"features\":[5.1,3.5,1.4,0.2]}"),
                Request.get("/api/v1/inference/health"));
    }
}
