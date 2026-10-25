package com.palaashatri.bench.b10.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.palaashatri.bench.lifecycle.Eventually;
import com.palaashatri.bench.lifecycle.Liveness;
import com.palaashatri.bench.lifecycle.OwnedApp;
import com.palaashatri.bench.lifecycle.ProcessLifecycle;
import com.palaashatri.bench.testkit.AppProcess;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** External correctness checks for the fleet: readiness-gated replacement of one JVM, and owned-process cleanup. */
class FleetArchitectureTest {
    private static final String APP = "com.palaashatri.bench.b10.app.BenchmarkApp";

    @TempDir
    Path temporary;

    @Test
    void failedGatewayBindReapsChildrenStartedBeforeTheFailure() throws Exception {
        ProcessLifecycle.failedBindReapsChildren(Path.of(System.getProperty("java.class.path")), APP, temporary);
    }

    @Test
    void forcedParentExitClosesOwnedChildren() {
        try (OwnedApp owned = OwnedApp.start(APP)) {
            List<Long> children = ProcessLifecycle.pids(owned.json("/api/v1/fleet/status"), "services", "pid");
            owned.trackAll(children);
            ProcessLifecycle.forcedParentExitClosesChildren(owned.app(), children);
        }
    }

    @Test
    void fleetDeployReplacesOnlyTheSelectedJvm() throws Exception {
        try (OwnedApp owned = OwnedApp.start(APP)) {
            Map<String, Object> before = owned.json("/api/v1/fleet/status");
            assertEquals(5L, number(before, "external_processes"));
            Map<Long, Long> old = new HashMap<Long, Long>();
            for (Map<String, Object> service : OwnedApp.objects(before, "services")) {
                old.put(number(service, "id"), number(service, "pid"));
            }
            owned.trackAll(old.values());
            assertEquals(5, new HashSet<Long>(old.values()).size());

            Map<String, Object> value = owned.json("/api/v1/service/0/inventory/item-1");
            Map<String, Object> replacement = owned.post("/api/v1/fleet/deploy/0", "{}");
            long newPid = number(replacement, "new_pid");
            owned.track(newPid);
            assertEquals(Boolean.TRUE, replacement.get("external_process_restarted"));
            assertNotEquals(old.get(0L).longValue(), newPid);
            Eventually.until(() -> !Liveness.alive(old.get(0L)), Duration.ofSeconds(25));

            Map<String, Object> after = owned.json("/api/v1/fleet/status");
            List<Map<String, Object>> services = OwnedApp.objects(after, "services");
            assertEquals(2L, number(services.get(0), "generation"));
            for (int id = 1; id < 5; id++) {
                assertEquals(old.get((long) id).longValue(), number(services.get(id), "pid"));
            }
            assertNotEquals(String.valueOf(value.get("value")),
                    String.valueOf(owned.json("/api/v1/service/0/inventory/item-1").get("value")));

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Future<Map<String, Object>>> deploys = new ArrayList<Future<Map<String, Object>>>();
                for (int index = 0; index < 2; index++) {
                    deploys.add(pool.submit(() -> owned.post("/api/v1/fleet/deploy/0", "{}")));
                }
                int probes = 0;
                boolean allDone = false;
                while (!allDone) {
                    allDone = true;
                    for (Future<Map<String, Object>> deploy : deploys) {
                        allDone &= deploy.isDone();
                    }
                    assertEquals(old.get(1L).longValue(),
                            number(owned.json("/api/v1/service/1/inventory/item-1"), "pid"));
                    probes++;
                    Thread.sleep(10);
                }
                assertTrue(probes > 0);
                for (Future<Map<String, Object>> deploy : deploys) {
                    owned.track(number(deploy.get(60, TimeUnit.SECONDS), "new_pid"));
                }
            } finally {
                pool.shutdownNow();
            }
            assertEquals(4L, number(OwnedApp.objects(owned.json("/api/v1/fleet/status"), "services").get(0), "generation"));
            assertEquals(400, owned.app().exchange("POST", "/api/v1/fleet/deploy/nope", "{}").status());
            assertEquals(404, owned.app().exchange("GET", "/api/v1/service/99/inventory/item-1", null).status());
            assertEquals(405, owned.app().exchange("GET", "/api/v1/fleet/deploy/1", null).status());
        }
    }

    private static long number(Map<String, Object> object, String key) {
        return ((Number) object.get(key)).longValue();
    }
}
