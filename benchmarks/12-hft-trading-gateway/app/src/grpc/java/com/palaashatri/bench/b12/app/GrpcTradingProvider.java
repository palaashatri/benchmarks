package com.palaashatri.bench.b12.app;

import com.palaashatri.bench.b12.app.proto.CancelRequest;
import com.palaashatri.bench.b12.app.proto.OrderAck;
import com.palaashatri.bench.b12.app.proto.OrderRequest;
import com.palaashatri.bench.b12.app.proto.OrderStatus;
import com.palaashatri.bench.b12.app.proto.StatusRequest;
import com.palaashatri.bench.b12.app.proto.TradingGatewayGrpc;
import com.palaashatri.bench.common.Json;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * gRPC listener over HTTP/2 on loopback only. Every RPC delegates to the shared
 * order book, so gRPC, HTTP and framed TCP report the same state.
 *
 * <p>Built and discovered only in the Maven gRPC lane; see {@link GrpcEndpointProvider}.
 */
public final class GrpcTradingProvider implements GrpcEndpointProvider {
    public GrpcTradingProvider() { }

    @Override
    public Endpoint start(Operations operations) throws IOException {
        Server server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
                .addService(new TradingService(operations))
                .build()
                .start();
        return new RunningServer(server);
    }

    private static final class TradingService extends TradingGatewayGrpc.TradingGatewayImplBase {
        private final Operations operations;

        TradingService(Operations operations) {
            this.operations = operations;
        }

        @Override
        public void submitOrder(OrderRequest request, StreamObserver<OrderAck> responder) {
            reply(responder, ack(operations.submit(
                    request.getSymbol(), request.getSide(), request.getQuantity(), request.getPriceNanos())));
        }

        @Override
        public void cancelOrder(CancelRequest request, StreamObserver<OrderAck> responder) {
            reply(responder, ack(operations.cancel(request.getOrderId())));
        }

        @Override
        public void getOrderStatus(StatusRequest request, StreamObserver<OrderStatus> responder) {
            Map<String, Object> body = Json.parseObject(operations.status(request.getOrderId()));
            OrderStatus.Builder status = OrderStatus.newBuilder();
            if (body.get("order_id") instanceof String id) status.setOrderId(id);
            if (body.get("status") instanceof String value) status.setStatus(value);
            if (body.get("remaining_quantity") instanceof Long remaining) status.setRemainingQuantity(remaining);
            reply(responder, status.build());
        }

        private static OrderAck ack(String json) {
            Map<String, Object> body = Json.parseObject(json);
            OrderAck.Builder ack = OrderAck.newBuilder().setAccepted(Boolean.TRUE.equals(body.get("accepted")));
            // Rejections carry an empty order_id; that is "no order", not an order named "".
            if (body.get("order_id") instanceof String id && !id.isEmpty()) ack.setOrderId(id);
            if (body.get("reason") instanceof String reason) ack.setReason(reason);
            return ack.build();
        }

        private static <T> void reply(StreamObserver<T> responder, T message) {
            responder.onNext(message);
            responder.onCompleted();
        }
    }

    private record RunningServer(Server server) implements Endpoint {
        @Override
        public int port() {
            return server.getPort();
        }

        @Override
        public void close() {
            server.shutdown();
            try {
                if (!server.awaitTermination(5, TimeUnit.SECONDS)) server.shutdownNow();
            } catch (InterruptedException interrupted) {
                server.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
