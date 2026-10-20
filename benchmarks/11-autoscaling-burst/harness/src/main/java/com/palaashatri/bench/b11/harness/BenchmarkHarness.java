package com.palaashatri.bench.b11.harness;

import com.palaashatri.bench.smoke.SmokeHarness;
import com.palaashatri.bench.smoke.SmokeHarness.Request;

public final class BenchmarkHarness {
    private BenchmarkHarness() { }
    public static void main(String[] args) throws Exception {
        SmokeHarness.run("11-autoscaling-burst", args,
                Request.get("/health"),
                Request.post("/api/v1/catalog/search", "{\"query\":\"smoke\",\"work_ms\":1}"),
                Request.get("/api/v1/metrics/scaling"));
    }
}
