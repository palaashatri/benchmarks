package com.palaashatri.bench.b12.app;

import java.io.IOException;

/**
 * Optional gRPC listener for the gateway.
 *
 * <p>Implementations live in the Maven-built gRPC lane (`src/grpc/java`) and are
 * found with {@link java.util.ServiceLoader}. The dependency-free smoke build
 * never sees a provider, so `/health` reports `grpc_active=false` there.
 */
public interface GrpcEndpointProvider {
    /** Order operations every transport shares; the same book backs HTTP, TCP and gRPC. */
    interface Operations {
        String submit(String symbol, String side, long quantity, long priceNanos);

        String cancel(String orderId);

        String status(String orderId);
    }

    /** A bound listener. {@link #port()} is only meaningful while the endpoint is open. */
    interface Endpoint extends AutoCloseable {
        int port();

        @Override
        void close();
    }

    Endpoint start(Operations operations) throws IOException;
}
