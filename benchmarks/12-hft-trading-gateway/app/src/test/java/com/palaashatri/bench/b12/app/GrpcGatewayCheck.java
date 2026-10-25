package com.palaashatri.bench.b12.app;

import com.palaashatri.bench.b12.app.proto.CancelRequest;
import com.palaashatri.bench.b12.app.proto.OrderAck;
import com.palaashatri.bench.b12.app.proto.OrderRequest;
import com.palaashatri.bench.b12.app.proto.OrderStatus;
import com.palaashatri.bench.b12.app.proto.StatusRequest;
import com.palaashatri.bench.b12.app.proto.TradingGatewayGrpc;
import com.palaashatri.bench.common.Json;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Maven gRPC-lane correctness check. It makes real gRPC calls to a gateway JVM's
 * loopback listener and reads the same orders back over HTTP, which proves the
 * transports share one order book. Any failed check throws, so `mvn test` fails.
 */
public final class GrpcGatewayCheck {
    private GrpcGatewayCheck() { }

    public static void main(String[] args) throws Exception {
        MiniHttpServer gateway = new MiniHttpServer("12-hft-trading-gateway", "HFT Trading Gateway");
        gateway.start(0);
        ManagedChannel channel = null;
        try {
            int grpcPort = gateway.grpcPort().orElseThrow(() -> new AssertionError("gRPC listener did not start"));
            channel = ManagedChannelBuilder.forAddress("127.0.0.1", grpcPort).usePlaintext().build();
            run(gateway, TradingGatewayGrpc.newBlockingStub(channel));
        } finally {
            if (channel != null) channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            gateway.close();
        }
        System.out.println("grpc gateway checks passed");
    }

    private static void run(MiniHttpServer gateway, TradingGatewayGrpc.TradingGatewayBlockingStub trading)
            throws IOException, InterruptedException {
        HttpClient http = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + gateway.port();

        Map<String, Object> health = Json.parseObject(get(http, base + "/health"));
        check(Boolean.TRUE.equals(health.get("grpc_active")), "/health must report grpc_active=true");
        check(health.get("grpc_port") instanceof Long port && port == gateway.grpcPort().getAsInt(),
                "/health must publish the real gRPC port");

        OrderAck sell = trading.submitOrder(OrderRequest.newBuilder()
                .setSymbol("grpc-alpha").setSide("SELL").setQuantity(100).setPriceNanos(150).build());
        check(sell.getAccepted() && sell.hasOrderId(), "gRPC submit must be accepted with an order id");
        String sellId = sell.getOrderId();

        Map<String, Object> viaHttp = Json.parseObject(get(http, base + "/orders/" + sellId));
        check("OPEN".equals(viaHttp.get("status")) && Long.valueOf(100).equals(viaHttp.get("remaining_quantity")),
                "HTTP must see the gRPC-submitted order as OPEN with 100 remaining: " + viaHttp);

        OrderStatus open = trading.getOrderStatus(StatusRequest.newBuilder().setOrderId(sellId).build());
        check("OPEN".equals(open.getStatus()) && open.getRemainingQuantity() == 100,
                "gRPC status must report OPEN with 100 remaining");

        OrderAck buy = trading.submitOrder(OrderRequest.newBuilder()
                .setSymbol("GRPC-ALPHA").setSide("BUY").setQuantity(40).setPriceNanos(150).build());
        check(buy.getAccepted(), "crossing buy must be accepted");
        OrderStatus partial = trading.getOrderStatus(StatusRequest.newBuilder().setOrderId(sellId).build());
        check("PARTIALLY_FILLED".equals(partial.getStatus()) && partial.getRemainingQuantity() == 60,
                "crossing buy must partially fill the sell to 60 remaining: " + partial);

        OrderAck cancelled = trading.cancelOrder(CancelRequest.newBuilder().setOrderId(sellId).build());
        check(cancelled.getAccepted() && cancelled.getOrderId().equals(sellId), "gRPC cancel must be accepted");
        check("CANCELLED".equals(Json.parseObject(get(http, base + "/orders/" + sellId)).get("status")),
                "HTTP must see the gRPC cancellation");

        OrderAck again = trading.cancelOrder(CancelRequest.newBuilder().setOrderId(sellId).build());
        check(!again.getAccepted() && "ALREADY_CANCELLED".equals(again.getReason()),
                "cancelling twice must be refused with ALREADY_CANCELLED");

        OrderAck invalid = trading.submitOrder(OrderRequest.newBuilder()
                .setSymbol("GRPC-ALPHA").setSide("BUY").setQuantity(0).setPriceNanos(150).build());
        check(!invalid.getAccepted() && "INVALID_PARAMS".equals(invalid.getReason()) && !invalid.hasOrderId(),
                "invalid quantity must be rejected without an order id");

        OrderStatus unknown = trading.getOrderStatus(StatusRequest.newBuilder().setOrderId("ord-missing").build());
        check("UNKNOWN".equals(unknown.getStatus()) && !unknown.hasRemainingQuantity(),
                "unknown order must report UNKNOWN with no remaining quantity");

        String metrics = get(http, base + "/metrics");
        check(metrics.contains("gateway_grpc_active 1"), "/metrics must report the gRPC listener as active");
        check(metrics.contains("gateway_grpc_requests_total 8"), "/metrics must count the eight gRPC calls: " + metrics);
    }

    private static String get(HttpClient http, String url) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofString());
        check(response.statusCode() == 200, "unexpected HTTP " + response.statusCode() + " for " + url);
        return response.body();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
