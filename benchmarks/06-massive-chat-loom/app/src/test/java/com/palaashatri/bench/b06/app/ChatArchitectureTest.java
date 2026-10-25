package com.palaashatri.bench.b06.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.palaashatri.bench.common.Json;
import com.palaashatri.bench.lifecycle.Eventually;
import com.palaashatri.bench.lifecycle.OwnedApp;
import com.palaashatri.bench.lifecycle.ProcessLifecycle;
import com.palaashatri.bench.lifecycle.Session;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** External correctness checks for chat: persistent fan-out, room isolation, disconnect cleanup and backpressure. */
class ChatArchitectureTest {
    private static final String APP = "com.palaashatri.bench.b06.app.BenchmarkApp";

    @TempDir
    Path temporary;

    @Test
    void persistentFanoutIsolatesRoomsAndCleansUpDisconnects() {
        try (OwnedApp owned = OwnedApp.start(APP)) {
            Map<String, Object> health = owned.json("/health");
            assertEquals(Boolean.TRUE, health.get("persistent_connections"));
            int port = ((Number) health.get("tcp_port")).intValue();
            try (Session alice = new Session(port); Session bob = new Session(port); Session outsider = new Session(port)) {
                subscribe(alice, "alice", "team");
                subscribe(bob, "bob", "team");
                subscribe(outsider, "carol", "other");

                for (int n = 0; n < 2; n++) {
                    String content = "hello " + n + " \"\\\n";
                    Map<String, Object> result = owned.post("/rooms/team/messages",
                            Json.stringify(Map.of("sender", "alice", "content", content)));
                    assertEquals(2L, ((Number) result.get("queued_deliveries")).longValue());
                    assertEquals(content, Json.parseObject(alice.read()).get("content"));
                    assertEquals(result.get("message_id"), Json.parseObject(bob.read()).get("message_id"));
                }
                outsider.send("{\"op\":\"ping\"}");
                assertEquals("pong", Json.parseObject(outsider.read()).get("op"));
                alice.send("{\"op\":\"invalid\"}");
                assertEquals("unknown_operation", Json.parseObject(alice.read()).get("error"));
                alice.send("{\"op\":\"ping\"}");
                assertEquals("pong", Json.parseObject(alice.read()).get("op"));
                assertEquals(3L, ((Number) owned.json("/api/v1/stats").get("persistent_connections")).longValue());
                Eventually.until(() -> ((Number) owned.json("/api/v1/stats").get("socket_deliveries")).longValue() == 4,
                        Duration.ofSeconds(10));
            }
            Eventually.until(() -> ((Number) owned.json("/api/v1/stats").get("persistent_connections")).longValue() == 0,
                    Duration.ofSeconds(10));
            assertEquals(0L, ((Number) owned.json("/api/v1/stats").get("subscribers")).longValue());
            assertEquals(0L, ((Number) owned.post("/rooms/team/messages",
                    "{\"content\":\"no readers\"}").get("queued_deliveries")).longValue());
            try (Session bad = new Session(port)) {
                bad.raw(frameOf(65_537));
                assertEquals("frame_too_large", Json.parseObject(bad.read()).get("error"));
            }
        }
    }

    @Test
    void disconnectDuringSubscribeLeavesNoMemberships() {
        try (OwnedApp owned = OwnedApp.start(APP)) {
            int port = ((Number) owned.json("/health").get("tcp_port")).intValue();
            for (int round = 0; round < 150; round++) {
                Session peer = new Session(port);
                peer.send("{\"op\":\"subscribe\",\"room\":\"race\",\"user\":\"one\"}");
                peer.read();
                peer.send("{\"op\":\"subscribe\",\"room\":\"other-race\",\"user\":\"one\"}");
                try {
                    peer.abort();
                } catch (java.io.IOException exception) {
                    throw new IllegalStateException(exception);
                }
            }
            Eventually.until(() -> ((Number) owned.json("/api/v1/stats").get("persistent_connections")).longValue() == 0,
                    Duration.ofSeconds(10));
            Eventually.until(() -> ((Number) owned.json("/api/v1/stats").get("subscribers")).longValue() == 0,
                    Duration.ofSeconds(5));
        }
    }

    @Test
    void oversizedInputDisconnectsAStalledChatReader() throws Exception {
        try (OwnedApp owned = OwnedApp.start(APP)) {
            int port = ((Number) owned.json("/health").get("tcp_port")).intValue();
            try (java.net.Socket peer = new java.net.Socket()) {
                peer.setReceiveBufferSize(1024);
                peer.setSoTimeout(5000);
                peer.connect(new java.net.InetSocketAddress("127.0.0.1", port), 5000);
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
                peer.getOutputStream().write(
                        "{\"op\":\"subscribe\",\"room\":\"stall\",\"user\":\"slow\"}\n".getBytes(StandardCharsets.UTF_8));
                assertEquals(Boolean.TRUE, Json.parseObject(reader.readLine()).get("subscribed"));
                String payload = "z".repeat(16_384);
                for (int message = 0; message < 200; message++) {
                    owned.post("/rooms/stall/messages", Json.stringify(Map.of("content", payload)));
                }
                assertEquals(1L, ((Number) owned.json("/api/v1/stats").get("persistent_connections")).longValue());
                peer.getOutputStream().write(frameOf(65_537));
                Eventually.until(() -> ((Number) owned.json("/api/v1/stats").get("persistent_connections")).longValue() == 0,
                        Duration.ofSeconds(5));
                assertEquals(0L, ((Number) owned.json("/api/v1/stats").get("subscribers")).longValue());
                try (Session healthy = new Session(port)) {
                    healthy.send("{\"op\":\"ping\"}");
                    assertEquals("pong", Json.parseObject(healthy.read()).get("op"));
                }
            }
        }
    }

    private static void subscribe(Session session, String user, String room) {
        session.send(Json.stringify(Map.of("op", "subscribe", "room", room, "user", user)));
        assertEquals(Boolean.TRUE, Json.parseObject(session.read()).get("subscribed"));
    }

    /** A frame of {@code bytes} x characters followed by a newline, which the server must refuse as too large. */
    private static byte[] frameOf(int payload) {
        byte[] frame = new byte[payload + 1];
        java.util.Arrays.fill(frame, 0, payload, (byte) 'x');
        frame[payload] = '\n';
        return frame;
    }
}
