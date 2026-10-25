package com.palaashatri.bench.b13.app;

import com.palaashatri.bench.b13.app.graph.Manifest;

public final class BenchmarkApp {
    private BenchmarkApp() { }

    public static void main(String[] args) throws Exception {
        long startedNanos = System.nanoTime();
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", args.length > 0 ? args[0] : "8080"));
        Container container = Container.wire(Manifest.BEAN_CLASSES);
        new MonolithServer("13-large-monolith", container, Manifest.MODULES, Manifest.DEPTH,
                Manifest.EXPECTED_RESULT, startedNanos).start(port);
    }
}
