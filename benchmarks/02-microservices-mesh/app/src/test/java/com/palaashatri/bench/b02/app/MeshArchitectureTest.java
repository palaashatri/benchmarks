package com.palaashatri.bench.b02.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.palaashatri.bench.common.Json;
import com.palaashatri.bench.lifecycle.Eventually;
import com.palaashatri.bench.lifecycle.Liveness;
import com.palaashatri.bench.lifecycle.OwnedApp;
import com.palaashatri.bench.lifecycle.ProcessLifecycle;
import com.palaashatri.bench.lifecycle.Session;
import com.palaashatri.bench.testkit.AppProcess;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** External correctness checks for the mesh: separate service JVMs, real calls, and owned-process cleanup. */
class MeshArchitectureTest {
    private static final String APP = "com.palaashatri.bench.b02.app.BenchmarkApp";

    @TempDir
    Path temporary;

    @Test
    void failedGatewayBindReapsChildrenStartedBeforeTheFailure() throws Exception {
        ProcessLifecycle.failedBindReapsChildren(classpath(), APP, temporary);
    }

    @Test
    void forcedParentExitClosesOwnedChildren() {
        try (OwnedApp owned = OwnedApp.start(APP)) {
            List<Long> children = ProcessLifecycle.pids(owned.json("/health"), "services", "pid");
            owned.trackAll(children);
            ProcessLifecycle.forcedParentExitClosesChildren(owned.app(), children);
        }
    }

    @Test
    void meshHasDistinctServiceProcessesAndRealCalls() throws Exception {
        try (OwnedApp owned = OwnedApp.start(APP)) {
            Map<String, Object> health = owned.json("/health");
            assertEquals(3L, number(health, "external_processes"));
            assertEquals("multi-jvm-mesh", health.get("process_model"));
            List<Map<String, Object>> services = OwnedApp.objects(health, "services");
            HashSet<Long> pids = new HashSet<Long>();
            for (Map<String, Object> service : services) {
                pids.add(number(service, "pid"));
            }
            owned.trackAll(pids);
            assertEquals(3, pids.size());
            assertTrue(!pids.contains(owned.app().process().pid()));
            for (long pid : pids) {
                assertTrue(Liveness.alive(pid), "service " + pid + " is not running");
            }

            Map<String, Object> user = owned.json("/api/v1/users/1001");
            assertEquals("1001", ((Map<?, ?>) user.get("account")).get("id"));
            Map<String, Object> order = owned.post("/api/v1/orders", "{\"from_id\":\"1001\",\"item\":\"demo\"}");
            assertEquals("RECORDED", ((Map<?, ?>) order.get("transaction")).get("status"));

            Map<String, Object> transaction = service(services, "transaction");
            Map<String, Object> notification = service(services, "notification");
            assertEquals(1L, number(childGet(transaction, "/state"), "transactions_retained"));
            Eventually.until(() -> number(childGet(notification, "/state"), "notifications_accepted") >= 1,
                    Duration.ofSeconds(25));
            assertEquals(number(transaction, "pid"), number(childGet(transaction, "/runtime"), "pid"));

            ProcessHandle.of(number(transaction, "pid")).orElseThrow().destroy();
            Eventually.until(() -> owned.app().exchange("GET", "/health", null).status() == 503, Duration.ofSeconds(25));
            assertEquals(503, owned.app().exchange("POST", "/api/v1/orders", "{\"from_id\":\"1001\"}").status());
        }
    }

    private static Map<String, Object> service(List<Map<String, Object>> services, String role) {
        for (Map<String, Object> service : services) {
            if (role.equals(service.get("role"))) {
                return service;
            }
        }
        throw new AssertionError("no " + role + " service in " + services);
    }

    private static Map<String, Object> childGet(Map<String, Object> service, String path) {
        try {
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL(
                    "http://127.0.0.1:" + number(service, "port") + path).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            try (java.io.InputStream in = connection.getInputStream()) {
                return Json.parseObject(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            } finally {
                connection.disconnect();
            }
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("GET " + path + " on a service failed: " + exception.getMessage(), exception);
        }
    }

    static String classpath() {
        return System.getProperty("java.class.path");
    }

    private static long number(Map<String, Object> object, String key) {
        return ((Number) object.get(key)).longValue();
    }
}
