package com.palaashatri.bench.b02.harness;

import com.palaashatri.bench.smoke.SmokeHarness;
import com.palaashatri.bench.smoke.SmokeHarness.Request;

public final class BenchmarkHarness {
    private BenchmarkHarness() { }
    public static void main(String[] args) throws Exception {
        SmokeHarness.run("02-microservices-mesh", args,
                Request.get("/health"),
                Request.get("/api/v1/users/1001"),
                Request.post("/api/v1/orders", "{\"from_id\":\"1001\",\"item\":\"widget\",\"amount\":100}"));
    }
}
