package com.palaashatri.bench.b08.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;

public final class MiniHttpServer {
    private final String benchmark;
    private final String title;
    private final AtomicLong jobIds = new AtomicLong(1);
    private final ConcurrentHashMap<String, JobState> jobs = new ConcurrentHashMap<>();

    // Metrics (hand-rolled Prometheus format)
    private final AtomicLong recordsProcessedTotal = new AtomicLong();
    private final AtomicLong ioBytesWrittenTotal = new AtomicLong();
    private final AtomicLong batchDurationSum = new AtomicLong(); // in ms
    private final AtomicLong batchCount = new AtomicLong();

    record CsvRecord(long id, String category, double value, long timestamp) {}

    enum JobStatus { ACCEPTED, RUNNING, COMPLETED, FAILED }

    static final class JobState {
        final String jobId;
        volatile JobStatus status;
        volatile long recordsProcessed;
        volatile double recordsPerSecond;
        volatile double ioThroughputMbS;
        volatile long elapsedMs;
        final long startedAt;

        JobState(String jobId) {
            this.jobId = jobId;
            this.status = JobStatus.ACCEPTED;
            this.startedAt = System.currentTimeMillis();
        }
    }

    public MiniHttpServer(String benchmark, String title) {
        this.benchmark = benchmark;
        this.title = title;
    }

    public void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 256);
        server.createContext("/api/v1/etl/run", this::handleEtlRun);
        server.createContext("/api/v1/etl/jobs", this::handleEtlJobs);
        server.createContext("/api/v1/etl/", this::handleEtlStatus);
        server.createContext("/health", this::handleHealth);
        server.createContext("/metrics", this::handleMetrics);
        server.createContext("/actuator/health", this::handleHealth);
        server.createContext("/actuator/prometheus", this::handleMetrics);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        log("started", "\"port\":" + port);
    }

    private void handleEtlRun(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            json(ex, 405, "{\"error\":\"method not allowed\"}");
            return;
        }
        ex.getRequestBody().readAllBytes(); // drain
        String jobId = "job-" + jobIds.getAndIncrement();
        JobState state = new JobState(jobId);
        jobs.put(jobId, state);
        Thread.ofVirtual().start(() -> runBatchJob(state));
        json(ex, 200, "{\"job_id\":\"" + jobId + "\",\"status\":\"accepted\"}");
    }

    private void handleEtlStatus(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        // path is /api/v1/etl/{jobId}/status
        String[] parts = path.split("/");
        if (parts.length < 5) {
            json(ex, 404, "{\"error\":\"not found\"}");
            return;
        }
        String jobId = parts[4];
        JobState state = jobs.get(jobId);
        if (state == null) {
            json(ex, 404, "{\"error\":\"job not found\",\"job_id\":\"" + escape(jobId) + "\"}");
            return;
        }
        json(ex, 200, jobStateJson(state));
    }

    private void handleEtlJobs(HttpExchange ex) throws IOException {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (JobState state : jobs.values()) {
            if (!first) sb.append(",");
            sb.append(jobStateJson(state));
            first = false;
        }
        sb.append("]");
        json(ex, 200, sb.toString());
    }

    private void handleHealth(HttpExchange ex) throws IOException {
        long active = jobs.values().stream().filter(j -> j.status == JobStatus.RUNNING || j.status == JobStatus.ACCEPTED).count();
        json(ex, 200, "{\"status\":\"UP\",\"benchmark\":\"" + benchmark + "\",\"active_jobs\":" + active + "}");
    }

    private void handleMetrics(HttpExchange ex) throws IOException {
        Runtime rt = Runtime.getRuntime();
        long heapUsed = rt.totalMemory() - rt.freeMemory();
        long gcCount = java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(java.lang.management.GarbageCollectorMXBean::getCollectionCount).sum();
        String body = "# TYPE etl_records_processed_total counter\n"
            + "etl_records_processed_total " + recordsProcessedTotal.get() + "\n"
            + "# TYPE etl_batch_duration_seconds summary\n"
            + "etl_batch_duration_seconds_sum " + (batchDurationSum.get() / 1000.0) + "\n"
            + "etl_batch_duration_seconds_count " + batchCount.get() + "\n"
            + "# TYPE etl_io_bytes_written_total counter\n"
            + "etl_io_bytes_written_total " + ioBytesWrittenTotal.get() + "\n"
            + "# TYPE jvm_memory_used_bytes gauge\n"
            + "jvm_memory_used_bytes " + heapUsed + "\n"
            + "# TYPE jvm_gc_collection_count counter\n"
            + "jvm_gc_collection_count " + gcCount + "\n";
        bytes(ex, 200, "text/plain; version=0.0.4", body);
    }

    private void runBatchJob(JobState state) {
        state.status = JobStatus.RUNNING;
        long start = System.nanoTime();
        try {
            // Generate 50_000 CsvRecords deterministically
            long seed = state.jobId.hashCode() ^ 424242L;
            List<CsvRecord> records = generateRecords(50_000, seed);

            // Write to temp file as CSV
            Path tempInput = Files.createTempFile("etl-input-" + state.jobId + "-", ".csv");
            long bytesWritten = writeCsv(tempInput, records);
            ioBytesWrittenTotal.addAndGet(bytesWritten);

            // Read back and transform
            List<CsvRecord> read = readCsv(tempInput);

            // Filter value > 0.5, map enriched, aggregate by category
            Map<String, DoubleSummaryStatistics> aggregated = read.stream()
                .filter(r -> r.value() > 0.5)
                .collect(Collectors.groupingBy(CsvRecord::category,
                    Collectors.summarizingDouble(CsvRecord::value)));

            // Write output
            Path tempOutput = Files.createTempFile("etl-output-" + state.jobId + "-", ".csv");
            long outBytes = writeAggregated(tempOutput, aggregated);
            ioBytesWrittenTotal.addAndGet(outBytes);

            // Cleanup temp files
            Files.deleteIfExists(tempInput);
            Files.deleteIfExists(tempOutput);

            long elapsedMs = Math.max(1L, (System.nanoTime() - start) / 1_000_000L);
            double throughput = records.size() * 1000.0 / elapsedMs;
            double ioMbS = (bytesWritten + outBytes) / 1024.0 / 1024.0 / (elapsedMs / 1000.0);

            state.recordsProcessed = records.size();
            state.recordsPerSecond = throughput;
            state.ioThroughputMbS = ioMbS;
            state.elapsedMs = elapsedMs;
            state.status = JobStatus.COMPLETED;

            recordsProcessedTotal.addAndGet(records.size());
            batchDurationSum.addAndGet(elapsedMs);
            batchCount.incrementAndGet();

            log("job_completed", "\"job_id\":\"" + state.jobId + "\",\"records\":" + records.size() + ",\"throughput\":" + (long) throughput);
        } catch (Exception e) {
            state.status = JobStatus.FAILED;
            log("job_failed", "\"job_id\":\"" + state.jobId + "\",\"error\":\"" + escape(e.getMessage() == null ? "unknown" : e.getMessage()) + "\"");
        }
    }

    private List<CsvRecord> generateRecords(int count, long seed) {
        String[] categories = {"alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta"};
        List<CsvRecord> out = new ArrayList<>(count);
        long state = seed;
        for (int i = 0; i < count; i++) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            double value = ((state >>> 33) & 0xFFFFFFL) / (double) 0xFFFFFFL;
            String category = categories[(int) (Math.abs(state) % categories.length)];
            out.add(new CsvRecord(i + 1, category, value, Instant.now().toEpochMilli() + i));
        }
        return out;
    }

    private long writeCsv(Path path, List<CsvRecord> records) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            w.write("id,category,value,timestamp\n");
            for (CsvRecord r : records) {
                w.write(r.id() + "," + r.category() + "," + String.format(Locale.ROOT, "%.6f", r.value()) + "," + r.timestamp() + "\n");
            }
        }
        return Files.size(path);
    }

    private List<CsvRecord> readCsv(Path path) throws IOException {
        List<CsvRecord> out = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line = r.readLine(); // skip header
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] parts = line.split(",");
                if (parts.length < 4) continue;
                try {
                    out.add(new CsvRecord(
                        Long.parseLong(parts[0].trim()),
                        parts[1].trim(),
                        Double.parseDouble(parts[2].trim()),
                        Long.parseLong(parts[3].trim())
                    ));
                } catch (NumberFormatException ignored) {}
            }
        }
        return out;
    }

    private long writeAggregated(Path path, Map<String, DoubleSummaryStatistics> aggregated) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            w.write("category,count,sum,avg\n");
            for (Map.Entry<String, DoubleSummaryStatistics> e : aggregated.entrySet()) {
                DoubleSummaryStatistics s = e.getValue();
                w.write(e.getKey() + "," + s.getCount() + "," + String.format(Locale.ROOT, "%.6f", s.getSum()) + "," + String.format(Locale.ROOT, "%.6f", s.getAverage()) + "\n");
            }
        }
        return Files.size(path);
    }

    private static String jobStateJson(JobState s) {
        return "{\"job_id\":\"" + s.jobId + "\",\"status\":\"" + s.status.name().toLowerCase() + "\",\"records_processed\":" + s.recordsProcessed + ",\"records_per_second\":" + String.format(Locale.ROOT, "%.2f", s.recordsPerSecond) + ",\"io_throughput_mb_s\":" + String.format(Locale.ROOT, "%.2f", s.ioThroughputMbS) + ",\"elapsed_ms\":" + s.elapsedMs + "}";
    }

    private static String escape(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> { if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c)); else out.append(c); }
            }
        }
        return out.toString();
    }

    private static void json(HttpExchange ex, int status, String body) throws IOException { bytes(ex, status, "application/json", body); }
    private static void bytes(HttpExchange ex, int status, String contentType, String body) throws IOException { byte[] data = body.getBytes(StandardCharsets.UTF_8); ex.getResponseHeaders().set("Content-Type", contentType); ex.sendResponseHeaders(status, data.length); try (OutputStream out = ex.getResponseBody()) { out.write(data); } }
    private void log(String event, String fields) { System.out.println("{\"event\":\"" + event + "\",\"benchmark\":\"" + benchmark + "\"," + fields + "}"); }
}
