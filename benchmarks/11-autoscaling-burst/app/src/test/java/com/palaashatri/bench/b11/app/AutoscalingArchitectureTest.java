package com.palaashatri.bench.b11.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.palaashatri.bench.common.Json;
import com.palaashatri.bench.lifecycle.Eventually;
import com.palaashatri.bench.lifecycle.Liveness;
import com.palaashatri.bench.lifecycle.OwnedApp;
import com.palaashatri.bench.lifecycle.ProcessLifecycle;
import com.palaashatri.bench.testkit.AppProcess;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** External correctness checks for autoscaling: real replicas, lossless bursts, failure accounting and admission. */
class AutoscalingArchitectureTest {
    private static final String APP = "com.palaashatri.bench.b11.app.BenchmarkApp";
    private static final String SEARCH = "/api/v1/catalog/search";

    @TempDir
    Path temporary;

    @Test
    void failedGatewayBindReapsChildrenStartedBeforeTheFailure() throws Exception {
        ProcessLifecycle.failedBindReapsChildren(System.getProperty("java.class.path"), APP, temporary);
    }

    @Test
    void forcedParentExitClosesOwnedChildren() {
        try (OwnedApp owned = OwnedApp.start(APP)) {
            List<Long> children = ProcessLifecycle.pids(owned.json("/api/v1/metrics/scaling"), "replicas", "pid");
            owned.trackAll(children);
            ProcessLifecycle.forcedParentExitClosesChildren(owned.app(), children);
        }
    }

    @Test
    void burstSpawnsAndRetiresRealReplicasWithoutLosingWork() throws Exception {
        try (OwnedApp owned = OwnedApp.start(APP)) {
            Map<String, Object> initial = owned.json("/api/v1/metrics/scaling");
            assertEquals(1L, number(initial, "external_replicas"));
            owned.trackAll(ProcessLifecycle.pids(initial, "replicas", "pid"));

            List<AppProcess.Response> responses = runConcurrently(16, 60, n ->
                    owned.app().exchange("POST", SEARCH, "{\"query\":\"item-" + n + "\",\"work_ms\":150}"));
            for (AppProcess.Response response : responses) {
                assertEquals(202, response.status(), response.body());
                assertEquals(Boolean.FALSE, Json.parseObject(response.body()).get("completed"));
            }

            Map<String, Object> state = Eventually.until(() -> {
                Map<String, Object> scaling = owned.json("/api/v1/metrics/scaling");
                owned.trackAll(ProcessLifecycle.pids(scaling, "replicas", "pid"));
                return number(scaling, "external_replicas") > 1 ? scaling : null;
            }, Duration.ofSeconds(25));
            assertEquals("local-process-replicas", state.get("scaling_model"));
            assertTrue(number(state, "external_replicas") <= 4);
            assertEquals(number(state, "external_replicas"),
                    new HashSet<Long>(ProcessLifecycle.pids(state, "replicas", "pid")).size());

            Eventually.until(() -> number(owned.json("/api/v1/metrics/scaling"), "completed") == 60,
                    Duration.ofSeconds(25));
            Map<String, Object> retired = Eventually.until(() -> {
                Map<String, Object> scaling = owned.json("/api/v1/metrics/scaling");
                return number(scaling, "external_replicas") == 1 && number(scaling, "scale_down_count") > 0
                        ? scaling : null;
            }, Duration.ofSeconds(30));
            assertEquals(60L, number(retired, "accepted"));
            assertEquals(0L, number(retired, "failed"));
            assertEquals(0L, number(retired, "queue_depth"));
            assertEquals(405, owned.app().exchange("GET", SEARCH, null).status());
        }
    }

    @Test
    void replicaDeathAccountsFailedInflightWorkAndDrainsTheQueue() throws Exception {
        try (OwnedApp owned = OwnedApp.start(APP)) {
            long initial = number(OwnedApp.objects(owned.json("/api/v1/metrics/scaling"), "replicas").get(0), "pid");
            owned.track(initial);
            List<AppProcess.Response> responses = runConcurrently(16, 40, n ->
                    owned.app().exchange("POST", SEARCH, "{\"query\":\"" + n + "\",\"work_ms\":500}"));
            for (AppProcess.Response response : responses) {
                assertEquals(202, response.status());
            }
            Eventually.until(() -> number(owned.json("/api/v1/metrics/scaling"), "active_workers") >= 2,
                    Duration.ofSeconds(25));
            ProcessHandle.of(initial).orElseThrow().destroyForcibly();

            Map<String, Object> state = Eventually.until(() -> {
                Map<String, Object> scaling = owned.json("/api/v1/metrics/scaling");
                owned.trackAll(ProcessLifecycle.pids(scaling, "replicas", "pid"));
                return number(scaling, "completed") + number(scaling, "failed") == 40 ? scaling : null;
            }, Duration.ofSeconds(30));
            assertTrue(number(state, "failed") > 0);
            assertTrue(number(state, "completed") > 0);
            assertEquals(0L, number(state, "queue_depth"));
            assertTrue(number(state, "external_replicas") >= 1);
            assertTrue(number(state, "external_replicas") <= 4);
        }
    }

    @Test
    void admissionRejectsOverloadAndKeepsTheQueueBounded() throws Exception {
        try (OwnedApp owned = OwnedApp.start(APP)) {
            owned.trackAll(ProcessLifecycle.pids(owned.json("/api/v1/metrics/scaling"), "replicas", "pid"));
            List<AppProcess.Response> responses = runConcurrently(48, 1200, n ->
                    owned.app().exchange("POST", SEARCH, "{\"query\":\"" + n + "\",\"work_ms\":500}"));
            long accepted = 0;
            long rejected = 0;
            for (AppProcess.Response response : responses) {
                assertTrue(response.status() == 202 || response.status() == 429, String.valueOf(response.status()));
                accepted += response.status() == 202 ? 1 : 0;
                rejected += response.status() == 429 ? 1 : 0;
            }
            assertTrue(rejected > 0, "overload was not rejected");
            Map<String, Object> state = owned.json("/api/v1/metrics/scaling");
            owned.trackAll(ProcessLifecycle.pids(state, "replicas", "pid"));
            assertTrue(number(state, "queue_depth") <= 1000);
            assertEquals(accepted, number(state, "accepted"));
            assertEquals(rejected, number(state, "rejected"));
        }
    }

    private static List<AppProcess.Response> runConcurrently(int threads, int count, Task task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<AppProcess.Response>> futures = new ArrayList<Future<AppProcess.Response>>();
            for (int index = 0; index < count; index++) {
                final int n = index;
                futures.add(pool.submit((Callable<AppProcess.Response>) () -> task.run(n)));
            }
            List<AppProcess.Response> responses = new ArrayList<AppProcess.Response>();
            for (Future<AppProcess.Response> future : futures) {
                responses.add(future.get());
            }
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }

    private interface Task {
        AppProcess.Response run(int n);
    }

    private static long number(Map<String, Object> object, String key) {
        return ((Number) object.get(key)).longValue();
    }
}
