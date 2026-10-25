package com.palaashatri.bench.ctl;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Submits jobs and times each one to completion. The jobs lane and the Tier 2 engine share this driver, so a job is
 * measured the same way in both: from its intended submission time, through the completion the status endpoint reports.
 * Time spent queued behind a slow application therefore counts toward the job's sojourn.
 */
final class JobDriver {
    /** A workload's job endpoints. {@code statusPath} carries a {@code {job_id}} placeholder. */
    record Contract(String submitPath, String statusPath, String jobIdField, String stateField,
                    String completedState, String failedState) {
    }

    /** The 08 contract. {@code benchmarks/08-etl-batch/tier2.json} declares the same values for the Tier 2 engine. */
    static final Contract ETL = new Contract("/api/v1/etl/run", "/api/v1/etl/{job_id}/status", "job_id", "status",
            "completed", "failed");

    /** How often a job's status is polled while it runs. Each sojourn therefore has this much resolution. */
    static final long POLL_MILLIS = 100;

    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private JobDriver() {
    }

    /** One job, started at its intended time (or as soon as a worker is free) and timed to completion. */
    static Map<String, Object> scheduledJob(String base, Contract contract, long intended, int timeoutSeconds) {
        sleepUntil(intended);
        long submitted = System.nanoTime();
        Map<String, Object> status = runJob(base, contract, timeoutSeconds);
        long finished = System.nanoTime();
        Map<String, Object> record = new LinkedHashMap<>(status);
        record.put("submission_lag_ms", (submitted - intended) / 1e6);
        record.put("sojourn_ms", (finished - intended) / 1e6);
        return record;
    }

    /**
     * Submits one job and polls its status until the contract's completed or failed state appears. A rejected submission,
     * a transport error and a job that does not finish in time each produce a record with a non-completed status.
     */
    static Map<String, Object> runJob(String base, Contract contract, int timeoutSeconds) {
        HttpResponse<String> accepted;
        try {
            accepted = send(HttpRequest.newBuilder(URI.create(base + contract.submitPath()))
                    .timeout(Duration.ofSeconds(180)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build());
        } catch (IOException failure) {
            return Json.map("status", "failed", "failure", failure.getMessage());
        }
        if (accepted.statusCode() != 200) {
            return Json.map("status", "rejected", "failure", accepted.body());
        }
        Object job = Json.asMap(Json.parse(accepted.body())).get(contract.jobIdField());
        String path = contract.statusPath().replace("{job_id}", String.valueOf(job));
        long deadline = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();
        while (true) {
            Map<String, Object> snapshot;
            try {
                snapshot = Json.asMap(Json.parse(send(HttpRequest.newBuilder(URI.create(base + path))
                        .timeout(Duration.ofSeconds(60)).GET().build()).body()));
            } catch (IOException failure) {
                return Json.map("job_id", job, "status", "failed", "failure", failure.getMessage());
            }
            Object state = snapshot.get(contract.stateField());
            if (contract.completedState().equals(state) || contract.failedState().equals(state)) {
                return snapshot;
            }
            if (System.nanoTime() > deadline) {
                return Json.map("job_id", job, "status", "timeout", "failure", "job did not finish in time");
            }
            sleepMillis(POLL_MILLIS);
        }
    }

    private static HttpResponse<String> send(HttpRequest request) throws IOException {
        try {
            return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while calling " + request.uri(), interrupted);
        }
    }

    static void sleepUntil(long target) {
        long remaining;
        while ((remaining = target - System.nanoTime()) > 0) {
            sleepNanos(remaining);
        }
    }

    private static void sleepMillis(long millis) {
        sleepNanos(millis * 1_000_000L);
    }

    private static void sleepNanos(long nanos) {
        try {
            Thread.sleep(Math.max(1L, nanos / 1_000_000L));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
