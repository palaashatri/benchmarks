package com.palaashatri.bench.ctl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdkFetchTest {
    @TempDir
    Path temporary;

    @Test
    void selectsTheHotspotJdkOfTheRequestedFeatureRelease() {
        Map<String, Object> wanted = asset(21, "jdk", "hotspot", "jdk-21.0.1.zip");
        Map<String, Object> other = asset(17, "jdk", "hotspot", "jdk-17.zip");
        Map<String, Object> jre = asset(21, "jre", "hotspot", "jre-21.zip");
        assertEquals(wanted, JdkFetch.selectAsset(List.of(other, jre, wanted), 21));
        assertThrows(BenchError.class, () -> JdkFetch.selectAsset(List.of(other), 25));
    }

    @Test
    void installsAVerifiedArchiveAndLeavesNoStagingDirectory() throws Exception {
        byte[] archive = zipOf("jdk-21.0.1/bin/java", "fake");
        Path jdks = temporary.resolve("jdks");
        try (Fixture fixture = new Fixture(archive, sha256(archive))) {
            Path home = new JdkFetch(fixture.api()).install(21, jdks);
            assertEquals(jdks.toAbsolutePath().resolve("jdk-21.0.1"), home);
            assertTrue(Files.isRegularFile(home.resolve("bin").resolve("java")));
            try (Stream<Path> children = Files.list(jdks)) {
                assertTrue(children.noneMatch(path -> path.getFileName().toString().startsWith(".staging-")));
            }
        }
    }

    @Test
    void rejectsAnArchiveWhoseDigestDoesNotMatchThePublishedChecksum() throws Exception {
        byte[] archive = zipOf("jdk-21.0.1/bin/java", "fake");
        byte[] other = "something else".getBytes(StandardCharsets.UTF_8);
        try (Fixture fixture = new Fixture(archive, sha256(other))) {
            BenchError failure = assertThrows(BenchError.class,
                    () -> new JdkFetch(fixture.api()).install(21, temporary.resolve("jdks")));
            assertTrue(failure.getMessage().contains("SHA-256 mismatch"), failure.getMessage());
        }
    }

    private static Map<String, Object> asset(int major, String imageType, String jvm, String name) {
        return Json.map(
                "version", Json.map("major", major),
                "binary", Json.map("image_type", imageType, "jvm_impl", jvm,
                        "package", Json.map("name", name, "link", "http://127.0.0.1/" + name, "checksum", "00")));
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static byte[] zipOf(String entryName, String content) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    /** Serves the metadata response and one archive, as the Adoptium API does. */
    private static final class Fixture implements AutoCloseable {
        private final HttpServer server;
        private final String api;

        Fixture(byte[] archive, String checksum) throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            String metadata = Json.write(List.of(Json.map(
                    "version", Json.map("major", 21),
                    "binary", Json.map("image_type", "jdk", "jvm_impl", "hotspot",
                            "package", Json.map("name", "jdk-21.zip", "link", base + "/jdk-21.zip",
                                    "checksum", checksum)))));
            server.createContext("/v3/assets", exchange -> reply(exchange, metadata.getBytes(StandardCharsets.UTF_8)));
            server.createContext("/jdk-21.zip", exchange -> reply(exchange, archive));
            server.start();
            api = base + "/v3";
        }

        String api() {
            return api;
        }

        private static void reply(HttpExchange exchange, byte[] body) throws IOException {
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
