package com.palaashatri.bench.b07.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.palaashatri.bench.common.Json;
import com.palaashatri.bench.testkit.AppProcess;
import com.palaashatri.bench.testkit.Jars;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Functional gate: each start mode runs in a child JVM with the expected class-sharing flag. AppCDS needs a JAR
 * classpath, so the application is started from a JAR packaged from the compiled classes.
 */
class ColdStartSmokeTest {
    private static final String APP = "com.palaashatri.bench.b07.app.BenchmarkApp";

    @Test
    void everyStartModeIsMeasuredInAChildJvm() {
        Path jar = Jars.fromDirectory(Paths.get("target", "classes").toAbsolutePath(),
                Paths.get("target", "test-jars", "coldstart-app.jar").toAbsolutePath());
        try (AppProcess app = AppProcess.builder(APP).classpath(jar.toString()).start()) {
            Map<String, Object> modes = Json.parseObject(app.get("/api/v1/coldstart/modes"));
            List<String> ids = new java.util.ArrayList<String>();
            for (Object entry : (List<?>) modes.get("modes")) {
                Map<String, Object> mode = Json.parseObject(Json.stringify(entry));
                ids.add(String.valueOf(mode.get("id")));
                assertEquals(Boolean.TRUE, mode.get("available"), String.valueOf(mode));
            }
            assertEquals(java.util.Set.of("default", "no-cds", "appcds"), new java.util.HashSet<String>(ids));

            Map<String, String> expected = Map.of("default", "-Xshare:auto", "no-cds", "-Xshare:off",
                    "appcds", "-Xshare:on");
            for (String name : List.of("default", "no-cds", "appcds")) {
                AppProcess.Response response = app.exchange("POST", "/api/v1/coldstart/measure",
                        "{\"mode\":\"" + name + "\"}");
                assertEquals(200, response.status(), name + ": " + response.body());
                Map<String, Object> result = Json.parseObject(response.body());
                assertEquals(name, result.get("mode"));
                assertTrue(((Number) result.get("time_to_health_ms")).doubleValue() > 0, response.body());
                assertEquals("process_to_health", result.get("measurement_kind"), response.body());
                assertNotEquals(((Number) result.get("child_pid")).longValue(), app.process().pid(), response.body());
                Map<String, Object> child = Json.parseObject(Json.stringify(result.get("child_runtime")));
                List<String> childArguments = new java.util.ArrayList<String>();
                for (Object argument : (List<?>) child.get("jvm_args")) {
                    childArguments.add(String.valueOf(argument));
                }
                assertTrue(childArguments.contains(expected.get(name)), name + " " + childArguments);
                if (name.equals("appcds")) {
                    assertTrue(childArguments.stream().anyMatch(argument -> argument.startsWith("-XX:SharedArchiveFile=")),
                            childArguments.toString());
                }
            }

            assertEquals(400, app.exchange("POST", "/api/v1/coldstart/measure", "{\"mode\":\"bogus\"}").status());

            Map<String, Object> samples = Json.parseObject(app.get("/api/v1/coldstart/samples"));
            Map<String, Object> bySample = Json.parseObject(Json.stringify(samples.get("samples")));
            for (String name : List.of("default", "no-cds", "appcds")) {
                Map<String, Object> entry = Json.parseObject(Json.stringify(bySample.get(name)));
                assertEquals(1, ((List<?>) entry.get("time_to_health_ms")).size(), String.valueOf(entry));
                assertEquals(0L, ((Number) entry.get("failures")).longValue(), String.valueOf(entry));
            }
        }
    }
}
