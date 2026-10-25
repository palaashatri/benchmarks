package com.palaashatri.bench.b08.harness;

import com.palaashatri.bench.smoke.SmokeHarness;
import com.palaashatri.bench.smoke.SmokeHarness.Request;

/** Functional client for the 08-etl-batch routes. Load and telemetry are not measured by this harness. */
public final class BenchmarkHarness {
    private BenchmarkHarness() { }

    public static void main(String[] args) throws Exception {
        SmokeHarness.run("08-etl-batch", args,
                Request.post("/api/v1/etl/run", "{}"),
                Request.get("/api/v1/etl/jobs"),
                Request.get("/health"));
    }
}
