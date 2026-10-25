package com.palaashatri.bench.lifecycle;

import java.time.Duration;
import java.util.function.Supplier;

/** Retries a condition until it yields a non-null, non-false value, or fails with the last error seen. */
public final class Eventually {
    private Eventually() {
    }

    public static <T> T until(Supplier<T> check, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        RuntimeException last = null;
        while (System.nanoTime() < deadline) {
            try {
                T value = check.get();
                if (value != null && !Boolean.FALSE.equals(value)) {
                    return value;
                }
            } catch (RuntimeException exception) {
                last = exception;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting", exception);
            }
        }
        throw new AssertionError("condition did not become true within " + timeout
                + (last == null ? "" : ": " + last.getMessage()), last);
    }

    public static void until(Runnable check, Duration timeout) {
        until(() -> {
            check.run();
            return Boolean.TRUE;
        }, timeout);
    }
}
