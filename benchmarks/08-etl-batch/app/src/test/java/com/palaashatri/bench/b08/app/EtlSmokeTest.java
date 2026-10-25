package com.palaashatri.bench.b08.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.palaashatri.bench.common.Json;
import com.palaashatri.bench.testkit.AppProcess;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Functional gate: a job's output must match its in-memory reference digest; unfinished jobs report null, not zero. */
class EtlSmokeTest {
    private static final String APP = "com.palaashatri.bench.b08.app.BenchmarkApp";

    @Test
    void jobOutputMatchesItsReferenceDigest() throws InterruptedException {
        try (AppProcess app = AppProcess.builder(APP).start()) {
            String job = String.valueOf(Json.parseObject(app.request("POST", "/api/v1/etl/run", "{}")).get("job_id"));
            Map<String, Object> status = Json.parseObject(app.get("/api/v1/etl/" + job + "/status"));
            if (!"completed".equals(status.get("status"))) {
                for (String field : List.of("records_processed", "records_per_second", "io_throughput_mb_s",
                        "elapsed_ms", "output_sha256", "reference_sha256", "digest_match")) {
                    assertNull(status.get(field), field + " must be null until the job completes: " + status);
                }
            }
            long deadline = System.currentTimeMillis() + 120_000;
            while (true) {
                status = Json.parseObject(app.get("/api/v1/etl/" + job + "/status"));
                if ("completed".equals(status.get("status")) || "failed".equals(status.get("status"))) {
                    break;
                }
                assertTrue(System.currentTimeMillis() < deadline, "job did not finish: " + status);
                Thread.sleep(200);
            }
            assertEquals("completed", status.get("status"), String.valueOf(status));
            assertEquals(50_000L, ((Number) status.get("records_processed")).longValue(), String.valueOf(status));
            assertEquals(Boolean.TRUE, status.get("digest_match"), String.valueOf(status));
            String output = String.valueOf(status.get("output_sha256"));
            assertEquals(output, status.get("reference_sha256"));
            assertEquals(64, output.length());
            assertTrue(((Number) status.get("records_per_second")).doubleValue() > 0, String.valueOf(status));
            assertTrue(((Number) status.get("elapsed_ms")).doubleValue() > 0, String.valueOf(status));
            assertNull(status.get("failure"), String.valueOf(status));

            Map<String, Object> jobs = Json.parseObject(app.get("/api/v1/etl/jobs"));
            boolean listed = false;
            for (Object entry : (List<?>) jobs.get("jobs")) {
                Map<String, Object> item = Json.parseObject(Json.stringify(entry));
                if (job.equals(item.get("job_id"))) {
                    listed = true;
                    assertEquals(Boolean.TRUE, item.get("digest_match"));
                }
            }
            assertTrue(listed, "the completed job is not listed: " + jobs);
        }
    }
}
