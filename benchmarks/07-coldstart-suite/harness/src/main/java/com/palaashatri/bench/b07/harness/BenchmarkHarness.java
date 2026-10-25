package com.palaashatri.bench.b07.harness;

import com.palaashatri.bench.smoke.SmokeHarness;
import com.palaashatri.bench.smoke.SmokeHarness.Request;

/**
 * Functional client for the cold-start suite. The application measures child-JVM start times itself and
 * serves them from {@code /api/v1/coldstart/samples}; this client only drives each class-sharing mode and
 * checks that every request succeeds.
 */
public final class BenchmarkHarness {
    private BenchmarkHarness() { }

    public static void main(String[] args) throws Exception {
        SmokeHarness.run("07-coldstart-suite", args,
                Request.post("/api/v1/coldstart/measure", "{\"mode\":\"default\"}"),
                Request.post("/api/v1/coldstart/measure", "{\"mode\":\"no-cds\"}"),
                Request.post("/api/v1/coldstart/measure", "{\"mode\":\"appcds\"}"),
                Request.get("/api/v1/coldstart/modes"),
                Request.get("/api/v1/coldstart/samples"));
    }
}
