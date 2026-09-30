package com.palaashatri.bench.b10.harness;

import com.palaashatri.bench.smoke.SmokeHarness;
import com.palaashatri.bench.smoke.SmokeHarness.Request;

public final class BenchmarkHarness {
    private BenchmarkHarness() { }
    public static void main(String[] args) throws Exception {
        SmokeHarness.run("10-microservices-fleet", args,
                Request.get("/health"),
                Request.get("/api/v1/service/0/inventory/item-1"),
                Request.get("/api/v1/fleet/status"));
    }
}
