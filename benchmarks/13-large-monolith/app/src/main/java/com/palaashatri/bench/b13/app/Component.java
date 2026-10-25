package com.palaashatri.bench.b13.app;

/** Contract every generated component implements. Components hold no mutable state. */
public interface Component {
    /** Seed derived from the component's tag and the seeds of its dependencies. */
    long seed();

    /** Passes a value down the component's primary chain and returns the result. */
    long apply(long value);
}
