package com.palaashatri.bench.ctl;

/** A user-facing controller error. The message is printed without a stack trace. */
public final class BenchError extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public BenchError(String message) {
        super(message);
    }

    public BenchError(String message, Throwable cause) {
        super(message, cause);
    }
}
