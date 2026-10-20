package com.palaashatri.bench.common;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;

/** Real child-process tests, using deliberately broken readiness publishers. */
public final class LifecycleTest {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("java.io.tmpdir"));
        try (ChildJvm one = ChildJvm.start(Fixture.class.getName(), "test", "root", Map.of());
             ChildJvm two = ChildJvm.start(Fixture.class.getName(), "test", "root", Map.of())) {
            require(one.pid() != two.pid() && one.port() != two.port(), "distinct processes/ports");
            require(one.alive() && two.alive(), "children are alive");
            require(!one.token().equals(two.token()), "readiness tokens are unique");
            Path directory = one.directory();
            long pid = one.pid();
            one.close();
            one.close();
            require(!Files.exists(directory), "readiness artifacts deleted");
            require(!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "child reaped");
        }
        for (String failure : new String[]{"timeout", "bad-port", "wrong-token", "wrong-pid", "exit"}) {
            try {
                ChildJvm.start(Fixture.class.getName(), "test", "root", Map.of("FIXTURE_MODE", failure), Duration.ofMillis(800));
                throw new AssertionError("accepted broken readiness: " + failure);
            } catch (java.io.IOException expected) {
                require(expected.getMessage().contains("test"), "error identifies role");
            }
        }
        try (var stream = Files.list(root)) {
            var paths = stream.toList();
            require(paths.stream().noneMatch(p -> p.getFileName().toString().startsWith("bench-child-")), "failed startup removed artifacts: " + paths);
        }
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> ChildJvm.start(Fixture.class.getName(), "test", "root", Map.of()));
            var second = executor.submit(() -> ChildJvm.start(Fixture.class.getName(), "test", "root", Map.of()));
            try (ChildJvm one = first.get(); ChildJvm two = second.get()) {
                require(one.pid() != two.pid() && one.port() != two.port(), "concurrent launch isolation");
            }
        } finally { executor.shutdownNow(); }
        // Maven exec:java loads application classes through a context loader,
        // rather than putting target/classes in java.class.path.
        Path isolated = Files.createTempDirectory(root, "isolated-classpath-");
        Path source = isolated.resolve("ExternalFixture.java");
        Files.writeString(source, "public class ExternalFixture { public static void main(String[] args) throws Exception { "
                + "com.palaashatri.bench.common.LifecycleTest.Fixture.main(args); } }");
        int compiled = javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "17", "-classpath", System.getProperty("java.class.path"), "-d", isolated.toString(), source.toString());
        require(compiled == 0, "isolated fixture compiled");
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{isolated.toUri().toURL()}, original)) {
            Thread.currentThread().setContextClassLoader(loader);
            try (ChildJvm child = ChildJvm.start("ExternalFixture", "test", "root", Map.of(), Duration.ofSeconds(4))) {
                require(child.alive(), "context-classloader application starts in a child JVM");
            }
        } finally {
            Thread.currentThread().setContextClassLoader(original);
            try (var files = Files.walk(isolated)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
        require(Json.parseObject("{\"content\":\"quote\\\" newline\\n snowman \\u2603\",\"n\":12}").get("n").equals(12L), "JSON integer parse");
        var document = Map.<String,Object>of("text", "a\"\\\n\t\u0000☃", "n", 12L, "ready", true);
        require(Json.parseObject(Json.stringify(document)).equals(document), "JSON escaping roundtrip");
        for (String broken : new String[]{"{bad}", "{}garbage", "{\"n\":01}", "{\"n\":1,\"n\":2}", "{\"x\":\"\n\"}"}) {
            try { Json.parseObject(broken); throw new AssertionError("invalid JSON accepted: " + broken); }
            catch (IllegalArgumentException expected) { }
        }
        System.out.println("child readiness, PORT isolation, cleanup, concurrent launch and JSON checks passed");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static final class Fixture {
        public static void main(String[] args) throws Exception {
            RuntimeInfo.watchParent();
            String mode = System.getenv().getOrDefault("FIXTURE_MODE", "good");
            if (mode.equals("exit")) System.exit(4);
            if (mode.equals("timeout")) { Thread.sleep(30_000); return; }
            if (mode.equals("bad-port")) {
                Files.writeString(Path.of(System.getenv("BENCH_PORT_FILE")), "70000");
                Thread.sleep(30_000); return;
            }
            var server = com.sun.net.httpserver.HttpServer.create(
                    new java.net.InetSocketAddress("127.0.0.1", Integer.parseInt(System.getenv("PORT"))), 10);
            server.createContext("/runtime", exchange -> {
                var identity = Json.parseObject(RuntimeInfo.json());
                if (mode.equals("wrong-token")) identity.put("run_token", "not-owned");
                if (mode.equals("wrong-pid")) identity.put("pid", 1L);
                byte[] bytes = Json.stringify(identity).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            server.start();
            RuntimeInfo.publishPort(server.getAddress().getPort());
        }
    }
}
