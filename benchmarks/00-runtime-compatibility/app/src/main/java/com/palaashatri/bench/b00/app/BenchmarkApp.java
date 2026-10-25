package com.palaashatri.bench.b00.app;

/** Entry point under the workload's standard name; delegates to the Java 8 compatibility application. */
public final class BenchmarkApp {
    private BenchmarkApp() { }

    public static void main(String[] args) throws Exception {
        com.palaashatri.bench.compat.CompatibilityApp.main(args);
    }
}
