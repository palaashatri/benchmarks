package com.palaashatri.bench.b13.harness;

import com.palaashatri.bench.smoke.SmokeHarness;
import com.palaashatri.bench.smoke.SmokeHarness.Request;

/** Functional client for the monolith's routes. Load and telemetry come from the Tier 2 engine. */
public final class BenchmarkHarness {
    private BenchmarkHarness() { }

    public static void main(String[] args) throws Exception {
        SmokeHarness.run("13-large-monolith", args,
                Request.get("/api/v1/monolith/graph"),
                Request.get("/api/v1/monolith/work"),
                Request.get("/api/v1/monolith/warmup/status"));
    }
}
