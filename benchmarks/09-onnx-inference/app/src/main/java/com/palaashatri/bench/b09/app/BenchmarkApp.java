package com.palaashatri.bench.b09.app;

public final class BenchmarkApp {
    private BenchmarkApp() { }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", args.length > 0 ? args[0] : "8080"));
        new MiniHttpServer("09-onnx-inference", "ONNX Inference Service").start(port);
    }
}
