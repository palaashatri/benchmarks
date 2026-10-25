package com.palaashatri.bench.b03.harness;

import com.palaashatri.bench.smoke.SmokeHarness;
import com.palaashatri.bench.smoke.SmokeHarness.Request;

/** Functional client for the 03-streaming-analytics routes. Load and telemetry are not measured by this harness. */
public final class BenchmarkHarness {
    private BenchmarkHarness() { }

    public static void main(String[] args) throws Exception {
        SmokeHarness.run("03-streaming-analytics", args,
                Request.post("/api/v1/events", "{\"key\":\"smoke\",\"value\":1.5}"),
                Request.get("/health"),
                Request.get("/api/v1/lag"));
    }
}
