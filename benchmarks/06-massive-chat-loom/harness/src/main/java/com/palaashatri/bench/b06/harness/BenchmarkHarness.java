package com.palaashatri.bench.b06.harness;

import com.palaashatri.bench.smoke.SmokeHarness;
import com.palaashatri.bench.smoke.SmokeHarness.Request;

public final class BenchmarkHarness {
    private BenchmarkHarness() { }
    public static void main(String[] args) throws Exception {
        SmokeHarness.run("06-massive-chat-loom", args,
                Request.get("/health"),
                Request.post("/rooms/smoke/messages", "{\"sender\":\"smoke\",\"content\":\"functional check\"}"),
                Request.get("/api/v1/stats"));
    }
}
