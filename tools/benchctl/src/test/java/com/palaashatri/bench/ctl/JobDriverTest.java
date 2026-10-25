package com.palaashatri.bench.ctl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The shared job driver, against a stub with the 08 job endpoints: completed, failed and rejected jobs. */
class JobDriverTest {
    private HttpServer server;
    private String base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/etl/run", exchange -> reply(exchange, 200, "{\"job_id\":\"j1\",\"status\":\"accepted\"}"));
        server.createContext("/api/v1/etl/run-failing", exchange -> reply(exchange, 200, "{\"job_id\":\"j2\",\"status\":\"accepted\"}"));
        server.createContext("/api/v1/reject", exchange -> reply(exchange, 503, "{\"error\":\"queue full\"}"));
        server.createContext("/api/v1/etl/j1/status",
                exchange -> reply(exchange, 200, "{\"job_id\":\"j1\",\"status\":\"completed\",\"digest_match\":true}"));
        server.createContext("/api/v1/etl/j2/status",
                exchange -> reply(exchange, 200, "{\"job_id\":\"j2\",\"status\":\"failed\",\"digest_match\":false}"));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void aCompletedJobIsTimedFromItsIntendedSubmission() {
        long intended = System.nanoTime();
        Map<String, Object> record = JobDriver.scheduledJob(base, JobDriver.ETL, intended, 10);
        assertEquals("completed", record.get("status"));
        assertEquals(Boolean.TRUE, record.get("digest_match"));
        assertNotNull(record.get("sojourn_ms"));
        assertTrue(((Number) record.get("sojourn_ms")).doubleValue() >= ((Number) record.get("submission_lag_ms")).doubleValue(),
                "a job's sojourn includes its submission lag");
    }

    @Test
    void aFailedJobIsRecordedWithItsFailedState() {
        JobDriver.Contract failing = new JobDriver.Contract("/api/v1/etl/run-failing", "/api/v1/etl/{job_id}/status",
                "job_id", "status", "completed", "failed");
        Map<String, Object> record = JobDriver.scheduledJob(base, failing, System.nanoTime(), 10);
        assertEquals("failed", record.get("status"));
        assertEquals(Boolean.FALSE, record.get("digest_match"));
    }

    @Test
    void aRejectedSubmissionIsARecordNotAnException() {
        JobDriver.Contract rejecting = new JobDriver.Contract("/api/v1/reject", "/api/v1/etl/{job_id}/status",
                "job_id", "status", "completed", "failed");
        Map<String, Object> record = JobDriver.scheduledJob(base, rejecting, System.nanoTime(), 10);
        assertEquals("rejected", record.get("status"));
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
