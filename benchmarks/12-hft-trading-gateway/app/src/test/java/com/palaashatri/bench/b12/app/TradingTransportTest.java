package com.palaashatri.bench.b12.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.palaashatri.bench.common.Json;
import com.palaashatri.bench.lifecycle.OwnedApp;
import com.palaashatri.bench.lifecycle.Session;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * External correctness checks for the trading gateway: HTTP and framed TCP share one order book, price-time priority
 * holds, and malformed frames are refused without losing the session.
 */
class TradingTransportTest {
    private static final String APP = "com.palaashatri.bench.b12.app.BenchmarkApp";

    @Test
    void persistentTransportSharesTheOrderBookWithHttp() {
        try (OwnedApp owned = OwnedApp.start(APP)) {
            Map<String, Object> health = owned.json("/health");
            int port = ((Number) health.get("tcp_port")).intValue();
            assertEquals("http-framed-tcp-and-grpc", health.get("transport"));
            assertEquals(Boolean.TRUE, health.get("grpc_active"));
            Map<String, Object> buy = owned.post("/orders",
                    "{\"symbol\":\"ALPHA\",\"side\":\"BUY\",\"quantity\":100,\"price_nanos\":150}");
            try (Session session = new Session(port)) {
                session.send("{\"op\":\"SubmitOrder\",\"symbol\":\"BETA\",\"side\":\"SELL\",\"quantity\":100,\"price_nanos\":100}");
                Map<String, Object> other = Json.parseObject(session.read());
                assertEquals(Boolean.TRUE, other.get("accepted"));
                assertEquals(100L, number(owned.json("/orders/" + buy.get("order_id")), "remaining_quantity"));

                session.send("{\"op\":\"SubmitOrder\",\"symbol\":\"ALPHA\",\"side\":\"SELL\",\"quantity\":40,\"price_nanos\":140}");
                Map<String, Object> sell = Json.parseObject(session.read());
                assertEquals("FILLED", owned.json("/orders/" + sell.get("order_id")).get("status"));

                session.send("{\"op\":\"GetOrderStatus\",\"order_id\":\"" + buy.get("order_id") + "\"}");
                Map<String, Object> partial = Json.parseObject(session.read());
                assertEquals("PARTIALLY_FILLED", partial.get("status"));
                assertEquals(60L, number(partial, "remaining_quantity"));

                session.send("{\"op\":\"CancelOrder\",\"order_id\":\"" + buy.get("order_id") + "\"}");
                assertEquals(Boolean.TRUE, Json.parseObject(session.read()).get("accepted"));
                assertEquals("CANCELLED", owned.json("/orders/" + buy.get("order_id")).get("status"));

                session.send("{not json}");
                assertEquals("invalid_frame", Json.parseObject(session.read()).get("error"));
                session.send("{\"op\":\"GetOrderStatus\",\"order_id\":\"\\u+123\"}");
                assertEquals("invalid_frame", Json.parseObject(session.read()).get("error"));

                session.send("{\"op\":\"SubmitOrder\",\"symbol\":\"X\",\"side\":\"NOPE\",\"quantity\":1,\"price_nanos\":1}");
                assertEquals(Boolean.FALSE, Json.parseObject(session.read()).get("accepted"));
                session.send("{\"op\":\"GetOrderStatus\",\"order_id\":\"" + other.get("order_id") + "\"}");
                assertEquals("OPEN", Json.parseObject(session.read()).get("status"));

                Map<String, Object> first = owned.post("/orders",
                        "{\"symbol\":\"FIFO\",\"side\":\"BUY\",\"quantity\":30,\"price_nanos\":150}");
                Map<String, Object> second = owned.post("/orders",
                        "{\"symbol\":\"FIFO\",\"side\":\"BUY\",\"quantity\":40,\"price_nanos\":150}");
                session.send("{\"op\":\"SubmitOrder\",\"symbol\":\"FIFO\",\"side\":\"SELL\",\"quantity\":50,\"price_nanos\":150}");
                assertEquals(Boolean.TRUE, Json.parseObject(session.read()).get("accepted"));
                assertEquals("FILLED", owned.json("/orders/" + first.get("order_id")).get("status"));
                assertEquals(20L, number(owned.json("/orders/" + second.get("order_id")), "remaining_quantity"));
            }
            try (Session bad = new Session(port)) {
                byte[] frame = new byte[65_538];
                java.util.Arrays.fill(frame, 0, 65_537, (byte) 'x');
                frame[65_537] = '\n';
                bad.raw(frame);
                assertEquals("frame_too_large", Json.parseObject(bad.read()).get("error"));
            }
        }
    }

    private static long number(Map<String, Object> object, String key) {
        return ((Number) object.get(key)).longValue();
    }
}
