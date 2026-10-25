package com.palaashatri.bench.b01.harness;

import com.palaashatri.bench.smoke.SmokeHarness;
import com.palaashatri.bench.smoke.SmokeHarness.Request;

/** Functional client for the ledger routes. Load and telemetry come from the Tier 2 engine. */
public final class BenchmarkHarness {
    private BenchmarkHarness() { }

    public static void main(String[] args) throws Exception {
        SmokeHarness.run("01-fintech-ledger", args,
                Request.post("/transfers", "{\"from\":\"1001\",\"to\":\"1002\",\"amount_cents\":1}"),
                Request.get("/accounts/1001/balance"),
                Request.get("/health"));
    }
}
