package com.palaashatri.bench.b12.harness;

import com.palaashatri.bench.smoke.SmokeHarness;
import com.palaashatri.bench.smoke.SmokeHarness.Request;

public final class BenchmarkHarness {
    private BenchmarkHarness() { }
    public static void main(String[] args) throws Exception {
        SmokeHarness.run("12-hft-trading-gateway", args,
                Request.get("/health"),
                Request.post("/orders", "{\"symbol\":\"SMOKE\",\"side\":\"BUY\",\"quantity\":1,\"price_nanos\":100}"),
                Request.get("/health"));
    }
}
