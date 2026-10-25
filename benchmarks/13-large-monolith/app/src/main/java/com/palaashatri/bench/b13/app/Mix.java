package com.palaashatri.bench.b13.app;

/** Deterministic mixing used to derive component seeds and to step values through the graph. */
public final class Mix {
    private Mix() { }

    /** Derives a seed from a unique tag and the seeds of up to two dependencies (0 when absent). */
    public static long derive(long tag, long first, long second) {
        long h = tag * 0x9E3779B97F4A7C15L;
        h ^= Long.rotateLeft(first, 21) * 0xC2B2AE3D27D4EB4FL;
        h ^= Long.rotateLeft(second, 42) * 0x165667B19E3779F9L;
        return h ^ (h >>> 29);
    }

    /** One step of the per-request pipeline. */
    public static long step(long value, long seed) {
        long x = value ^ seed;
        x *= 0xFF51AFD7ED558CCDL;
        x ^= x >>> 33;
        return x + seed;
    }
}
