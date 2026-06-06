package com.palaashatri.bench.b06.app;

public final class BenchmarkApp {
    private BenchmarkApp() { }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", args.length > 0 ? args[0] : "8080"));
        new MiniHttpServer("06-massive-chat-loom", "Massive Chat Loom Server").start(port);
    }
}
