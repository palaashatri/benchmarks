package com.palaashatri.bench.b12.app;

import org.junit.jupiter.api.Test;

/**
 * The gRPC lane: real gRPC calls to the gateway's loopback listener, read back over HTTP. The checks are in
 * {@link GrpcGatewayCheck}, which throws on the first failed expectation.
 */
class GrpcGatewayTest {
    @Test
    void grpcCallsAndHttpShareOneOrderBook() throws Exception {
        GrpcGatewayCheck.main(new String[0]);
    }
}
