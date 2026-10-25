package com.palaashatri.bench.b08.app;

import com.palaashatri.bench.common.Json;
import com.palaashatri.bench.common.RuntimeInfo;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
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
        volatile Long recordsProcessed;
        volatile Double recordsPerSecond;
        volatile Double ioThroughputMbS;
        volatile Long elapsedMs;
        volatile String outputSha256;
        volatile String referenceSha256;
        volatile String failure;
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
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 256);
        server.createContext("/api/v1/etl/run", this::handleEtlRun);
        server.createContext("/api/v1/etl/jobs", this::handleEtlJobs);
        server.createContext("/api/v1/etl/", this::handleEtlStatus);
        server.createContext("/health", this::handleHealth);
        server.createContext("/metrics", this::handleMetrics);
        server.createContext("/runtime", this::handleRuntime);
        server.createContext("/actuator/health", this::handleHealth);
        server.createContext("/actuator/prometheus", this::handleMetrics);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        RuntimeInfo.publishPort(server.getAddress().getPort());
        log("started", "\"port\":" + server.getAddress().getPort());
    }

    private void handleRuntime(HttpExchange ex) throws IOException {
        Json.bytes(ex, 200, "application/json", RuntimeInfo.json());
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
        StringBuilder sb = new StringBuilder("{\"jobs\":[");
        boolean first = true;
        for (JobState state : jobs.values()) {
            if (!first) sb.append(",");
            sb.append(jobStateJson(state));
            first = false;
        }
        sb.append("]}");
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
        Path tempInput = null;
        Path tempOutput = null;
        try {
            long seed = state.jobId.hashCode() ^ 424242L;
            List<CsvRecord> records = generateRecords(50_000, seed);

            tempInput = Files.createTempFile("etl-input-" + state.jobId + "-", ".csv");
            long bytesWritten = writeCsv(tempInput, records);
            ioBytesWrittenTotal.addAndGet(bytesWritten);

            List<CsvRecord> read = readCsv(tempInput);
            if (read.size() != records.size()) {
                throw new IllegalStateException("record_count_mismatch: wrote " + records.size() + ", read " + read.size());
            }

            tempOutput = Files.createTempFile("etl-output-" + state.jobId + "-", ".csv");
            Files.writeString(tempOutput, aggregate(read), StandardCharsets.UTF_8);
            long outBytes = Files.size(tempOutput);
            ioBytesWrittenTotal.addAndGet(outBytes);

            // The reference comes from the in-memory records, never from the files. The only thing they share
            // is the rounding the CSV applies, so the digests agree only if the file round trip, the parsing and
            // the aggregation all do.
            String reference = aggregate(records.stream().map(MiniHttpServer::asWritten).toList());
            String outputDigest = sha256(Files.readAllBytes(tempOutput));
            String referenceDigest = sha256(reference.getBytes(StandardCharsets.UTF_8));
            state.outputSha256 = outputDigest;
            state.referenceSha256 = referenceDigest;
            if (!outputDigest.equals(referenceDigest)) {
                throw new IllegalStateException("digest_mismatch");
            }

            long elapsedMs = Math.max(1L, (System.nanoTime() - start) / 1_000_000L);
            double throughput = records.size() * 1000.0 / elapsedMs;
            double ioMbS = (bytesWritten + outBytes) / 1024.0 / 1024.0 / (elapsedMs / 1000.0);

            state.recordsProcessed = (long) records.size();
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
            state.failure = e.getMessage() == null ? "unknown" : e.getMessage();
            log("job_failed", "\"job_id\":\"" + state.jobId + "\",\"error\":\"" + escape(state.failure) + "\"");
        } finally {
            deleteQuietly(tempInput);
            deleteQuietly(tempOutput);
        }
    }

    /** Filters value > 0.5 and aggregates by category, in sorted category order. Output and reference use this. */
    static String aggregate(List<CsvRecord> records) {
        Map<String, double[]> sums = new TreeMap<>();
        for (CsvRecord record : records) {
            if (!(record.value() > 0.5)) continue;
            double[] accumulator = sums.computeIfAbsent(record.category(), key -> new double[2]);
            accumulator[0] += 1;
            accumulator[1] += record.value();
        }
        StringBuilder out = new StringBuilder("category,count,sum,avg\n");
        for (Map.Entry<String, double[]> entry : sums.entrySet()) {
            double count = entry.getValue()[0];
            double sum = entry.getValue()[1];
            out.append(entry.getKey()).append(',').append((long) count).append(',')
                    .append(String.format(Locale.ROOT, "%.6f", sum)).append(',')
                    .append(String.format(Locale.ROOT, "%.6f", sum / count)).append('\n');
        }
        return out.toString();
    }

    /** The value as the CSV carries it: six decimal places. */
    static CsvRecord asWritten(CsvRecord record) {
        double rounded = Double.parseDouble(String.format(Locale.ROOT, "%.6f", record.value()));
        return new CsvRecord(record.id(), record.category(), rounded, record.timestamp());
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required on every Java platform", unavailable);
        }
    }

    /** Temporary files are removed on every path. A failed removal cannot change a job's result. */
    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Left for the operating system's temporary-file cleanup.
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

    /** Values a job has not produced yet are null, never zero. */
    private static String jobStateJson(JobState s) {
        String digestMatch = s.outputSha256 == null || s.referenceSha256 == null
                ? "null" : Boolean.toString(s.outputSha256.equals(s.referenceSha256));
        return "{\"job_id\":\"" + s.jobId + "\",\"status\":\"" + s.status.name().toLowerCase(Locale.ROOT) + "\""
                + ",\"records_processed\":" + nullable(s.recordsProcessed)
                + ",\"records_per_second\":" + nullableFixed(s.recordsPerSecond)
                + ",\"io_throughput_mb_s\":" + nullableFixed(s.ioThroughputMbS)
                + ",\"elapsed_ms\":" + nullable(s.elapsedMs)
                + ",\"output_sha256\":" + nullableText(s.outputSha256)
                + ",\"reference_sha256\":" + nullableText(s.referenceSha256)
                + ",\"digest_match\":" + digestMatch
                + ",\"failure\":" + nullableText(s.failure) + "}";
    }

    private static String nullable(Object value) {
        return value == null ? "null" : value.toString();
    }

    private static String nullableFixed(Double value) {
        return value == null ? "null" : String.format(Locale.ROOT, "%.2f", value);
    }

    private static String nullableText(String value) {
        return value == null ? "null" : "\"" + escape(value) + "\"";
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
