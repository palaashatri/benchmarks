package com.palaashatri.bench.b05.harness;

import com.palaashatri.bench.smoke.SmokeHarness;
import com.palaashatri.bench.smoke.SmokeHarness.Request;

/** Functional client for the 05-polyglot-service routes. Load and telemetry are not measured by this harness. */
public final class BenchmarkHarness {
    private BenchmarkHarness() { }

    public static void main(String[] args) throws Exception {
        SmokeHarness.run("05-polyglot-service", args,
                Request.post("/api/v1/score", "{\"script\":\"function score(data){return data.value*2.0;}score(data);\",\"data\":{\"value\":21}}"),
                Request.get("/api/v1/scripts"));
    }
}
