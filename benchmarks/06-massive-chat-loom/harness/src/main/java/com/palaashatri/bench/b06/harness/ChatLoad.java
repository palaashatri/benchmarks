package com.palaashatri.bench.b06.harness;

import com.palaashatri.bench.common.Json;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Open-loop chat load over persistent TCP connections, with client-side delivery latency.
 *
 * <p>The driver starts the application itself, opens subscriber and publisher connections, and publishes
 * on a fixed schedule. Latency runs from each message's intended send time, so a slow publisher shows up
 * as latency rather than being hidden. Every message frame a connection receives is counted against the
 * {@code queued_deliveries} the server reports for each publish, so the run checks that nothing was lost
 * or duplicated. Application CPU and resident memory are sampled from {@code /proc/<pid>} for the
 * application process only.
 */
public final class ChatLoad {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static final long DRAIN_TIMEOUT_NANOS = Duration.ofSeconds(20).toNanos();
    private static final long SAMPLE_INTERVAL_MILLIS = 250;

    /** Settings for one run, read from the command line. */
    record Config(String appClasspath, String appMain, int subscribers, int rooms, int publishers,
                  double rate, double warmupSeconds, double measureSeconds, Path out,
                  String attachBaseUrl, String runToken, long appPid) {
        static Config from(Map<String, String> options) {
            var config = new Config(
                    options.getOrDefault("app-classpath", "../app/build/run-sh/classes"),
                    options.getOrDefault("app-main", "com.palaashatri.bench.b06.app.BenchmarkApp"),
                    positive(options, "subscribers", 200), positive(options, "rooms", 4),
                    positive(options, "publishers", 8), Double.parseDouble(options.getOrDefault("rate", "500")),
                    Double.parseDouble(options.getOrDefault("warmup-seconds", "3")),
                    Double.parseDouble(options.getOrDefault("measure-seconds", "10")),
                    Path.of(require(options, "out")),
                    options.get("attach-base-url"), options.get("run-token"),
                    Long.parseLong(options.getOrDefault("app-pid", "-1")));
            if (config.rate <= 0 || config.warmupSeconds < 0 || config.measureSeconds <= 0) {
                throw new IllegalArgumentException("rate must be positive and the windows must be valid");
            }
            if (config.attachBaseUrl != null && (config.runToken == null || config.appPid < 1)) {
                throw new IllegalArgumentException("attaching to an application needs --run-token and --app-pid");
            }
            return config;
        }
    }

    /** One persistent TCP connection, subscribed to one room, optionally also publishing. */
    static final class Connection {
        final String room;
        final String user;
        final boolean publisher;
        final Socket socket;
        final BufferedWriter writer;
        final ConcurrentLinkedQueue<long[]> arrivals = new ConcurrentLinkedQueue<>();  // [intended, received]
        final List<long[]> sends = new ArrayList<>();                                  // [intended, sent]; publisher thread only
        final AtomicLong framesReceived = new AtomicLong();
        final AtomicLong publishAcks = new AtomicLong();
        final AtomicLong queuedAcks = new AtomicLong();
        final AtomicLong errors = new AtomicLong();

        Connection(String room, String user, boolean publisher, int port) throws IOException {
            this.room = room;
            this.user = user;
            this.publisher = publisher;
            this.socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
            socket.setTcpNoDelay(true);
            this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            writeLine("{\"op\":\"subscribe\",\"room\":\"" + room + "\",\"user\":\"" + user + "\"}");
        }

        synchronized void writeLine(String line) throws IOException {
            writer.write(line);
            writer.write('\n');
            writer.flush();
        }

        /** Reads frames until the socket closes. Only this thread reads the socket. */
        void readLoop() {
            try (var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    long received = System.nanoTime();
                    Map<String, Object> frame = Json.parseObject(line);
                    if ("message".equals(frame.get("op"))) {
                        arrivals.add(new long[]{intendedOf(String.valueOf(frame.get("content"))), received});
                        framesReceived.incrementAndGet();
                    } else if (frame.get("queued_deliveries") instanceof Number queued) {
                        queuedAcks.addAndGet(queued.longValue());
                        publishAcks.incrementAndGet();
                    } else if (frame.containsKey("error")) {
                        errors.incrementAndGet();
                    }
                }
            } catch (IOException closed) {
                // The driver closes sockets when the run ends.
            }
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
    }

    /** One sample of the application process: cumulative CPU ticks and resident set in KiB. */
    record Sample(long nanos, long cpuTicks, long rssKib) { }

    private final Config config;
    private final List<Connection> connections = new CopyOnWriteArrayList<>();
    private final List<Sample> samples = new CopyOnWriteArrayList<>();
    private final AtomicBoolean sampling = new AtomicBoolean(true);
    private long pid;

    private ChatLoad(Config config) {
        this.config = config;
    }

    public static void main(String[] args) throws Exception {
        new ChatLoad(Config.from(parse(args))).run();
    }

    private void run() throws Exception {
        Path work = Files.createTempDirectory("chat-load-");
        Path portFile = work.resolve("ready.port");
        // Attached: the engine started the application and owns its telemetry; this driver only loads it.
        boolean attached = config.attachBaseUrl != null;
        String token = attached ? config.runToken : "chat-load-" + System.nanoTime();
        Process app = attached ? null : startApplication(work, portFile, token);
        try {
            String base = attached ? config.attachBaseUrl : "http://127.0.0.1:" + awaitPort(portFile, app);
            long appPid = attached ? config.appPid : app.pid();
            Map<String, Object> identity = awaitIdentity(base, appPid, token);
            pid = appPid;
            int tcpPort = ((Number) Json.parseObject(get(base + "/health")).get("tcp_port")).intValue();

            for (int index = 0; index < config.subscribers; index++) {
                connect(tcpPort, roomName(index % config.rooms), "sub-" + index, false);
            }
            for (int index = 0; index < config.publishers; index++) {
                connect(tcpPort, roomName(index % config.rooms), "pub-" + index, true);
            }
            awaitSubscriptions(base, config.subscribers + config.publishers);

            Thread sampler = Thread.ofVirtual().start(this::sampleLoop);
            long t0 = System.nanoTime() + Duration.ofMillis(500).toNanos();
            List<Thread> publishing = new ArrayList<>();
            long intervalNanos = Math.round(1e9 * config.publishers / config.rate);
            // The schedule is exact: each publisher takes its share of the total, and the first total % publishers take one more.
            long total = scheduledMessages();
            int publisherIndex = 0;
            for (Connection connection : connections) {
                if (connection.publisher) {
                    long count = total / config.publishers + (publisherIndex < total % config.publishers ? 1 : 0);
                    publisherIndex++;
                    publishing.add(Thread.ofVirtual().start(() -> publish(connection, t0, intervalNanos, count)));
                }
            }
            for (Thread thread : publishing) {
                thread.join();
            }
            long sends = connections.stream().filter(c -> c.publisher).mapToLong(c -> c.sends.size()).sum();
            awaitDrain(sends);
            sampling.set(false);
            sampler.join();
            Map<String, Object> serverStats = Json.parseObject(get(base + "/api/v1/stats"));
            writeEnvelope(identity, t0, sends, serverStats);
        } finally {
            connections.forEach(Connection::close);
            if (app != null) {
                stop(app);
            }
            deleteQuietly(work);
        }
    }

    /** Every message the schedule asks for: the publish rate over the warm-up and measurement windows, rounded once. */
    private long scheduledMessages() {
        return Math.round(config.rate * (config.warmupSeconds + config.measureSeconds));
    }

    private void connect(int tcpPort, String room, String user, boolean publisher) throws IOException {
        Connection connection = new Connection(room, user, publisher, tcpPort);
        connections.add(connection);
        Thread.ofVirtual().start(connection::readLoop);
    }

    /** Publishes on the schedule, whether or not earlier publishes were acknowledged. */
    private static void publish(Connection connection, long t0, long intervalNanos, long count) {
        try {
            for (long k = 0; k < count; k++) {
                long intended = t0 + k * intervalNanos;
                long now;
                while ((now = System.nanoTime()) < intended) {
                    LockSupport.parkNanos(intended - now);
                }
                long sent = System.nanoTime();
                connection.writeLine("{\"op\":\"publish\",\"room\":\"" + connection.room + "\",\"content\":\"t:"
                        + intended + "|s:" + connection.user + "-" + k + "\"}");
                connection.sends.add(new long[]{intended, sent});
            }
        } catch (IOException failed) {
            connection.errors.incrementAndGet();
        }
    }

    private void sampleLoop() {
        long ticksPerSecond = clockTicks();
        while (sampling.get()) {
            try {
                String stat = Files.readString(Path.of("/proc/" + pid + "/stat"));
                String[] fields = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
                long cpuTicks = Long.parseLong(fields[11]) + Long.parseLong(fields[12]);
                long rssKib = 0;
                for (String line : Files.readAllLines(Path.of("/proc/" + pid + "/status"))) {
                    if (line.startsWith("VmRSS:")) {
                        rssKib = Long.parseLong(line.replaceAll("[^0-9]", ""));
                    }
                }
                samples.add(new Sample(System.nanoTime(), cpuTicks, rssKib));
            } catch (IOException | RuntimeException gone) {
                // The process may exit at the end; the last good sample stands.
            }
            LockSupport.parkNanos(Duration.ofMillis(SAMPLE_INTERVAL_MILLIS).toNanos());
        }
    }

    /** Waits until every publish is acknowledged and every queued delivery has arrived, or times out. */
    private void awaitDrain(long sends) throws InterruptedException {
        long deadline = System.nanoTime() + DRAIN_TIMEOUT_NANOS;
        while (System.nanoTime() < deadline) {
            long acks = connections.stream().mapToLong(c -> c.publishAcks.get()).sum();
            long queued = connections.stream().mapToLong(c -> c.queuedAcks.get()).sum();
            long received = connections.stream().mapToLong(c -> c.framesReceived.get()).sum();
            if (acks == sends && received == queued) {
                return;
            }
            Thread.sleep(20);
        }
    }

    private void writeEnvelope(Map<String, Object> identity, long t0, long sends, Map<String, Object> serverStats)
            throws IOException {
        long measureStart = t0 + (long) (config.warmupSeconds * 1e9);
        long measureEnd = measureStart + (long) (config.measureSeconds * 1e9);

        List<Double> latencies = new ArrayList<>();
        long deliveredInWindow = 0;
        long framesReceived = 0, queued = 0, acks = 0, errors = 0;
        for (Connection connection : connections) {
            framesReceived += connection.framesReceived.get();
            queued += connection.queuedAcks.get();
            acks += connection.publishAcks.get();
            errors += connection.errors.get();
            for (long[] arrival : connection.arrivals) {
                if (arrival[0] >= measureStart && arrival[0] < measureEnd) {
                    latencies.add((arrival[1] - arrival[0]) / 1e6);
                    deliveredInWindow++;
                }
            }
        }
        List<Double> publishLag = new ArrayList<>();
        long sentInWindow = 0;
        for (Connection connection : connections) {
            for (long[] send : connection.sends) {
                if (send[0] >= measureStart && send[0] < measureEnd) {
                    publishLag.add((send[1] - send[0]) / 1e6);
                    sentInWindow++;
                }
            }
        }
        var telemetry = applicationTelemetry(measureStart, measureEnd);
        boolean conserved = acks == sends && framesReceived == queued;

        var results = new java.util.LinkedHashMap<String, Object>();
        results.put("messages_scheduled", scheduledMessages());
        results.put("messages_scheduled_in_window", Math.round(config.rate * config.measureSeconds));
        results.put("series", perSecondSeries(t0));
        results.put("messages_sent", sends);
        results.put("messages_sent_in_window", sentInWindow);
        results.put("publish_rate_per_second", sentInWindow / config.measureSeconds);
        results.put("deliveries_in_window", deliveredInWindow);
        results.put("delivery_rate_per_second", deliveredInWindow / config.measureSeconds);
        results.put("publish_lag_ms", percentiles(publishLag));
        results.put("delivery_latency_ms", percentiles(latencies));
        results.put("publish_acknowledged", acks);
        results.put("deliveries_queued", queued);
        results.put("frames_received", framesReceived);
        results.put("conserved", conserved);
        results.put("error_frames", errors);
        results.put("application", telemetry);
        results.put("server_stats", serverStats);

        var envelope = new java.util.LinkedHashMap<String, Object>();
        envelope.put("schema_version", "1.0.0");
        envelope.put("benchmark", "06-massive-chat-loom");
        envelope.put("run_kind", "calibration");
        envelope.put("implementation_tier", "tier-1");
        envelope.put("measurement_valid", false);
        envelope.put("invalid_reasons", List.of(
                "driver is not the audited Tier 2 engine: no host preflight and no statistical gates",
                "driver and application share a host that is not controlled",
                "delivery latency is measured by the driver, not the application"));
        envelope.put("warnings", List.of(
                "CPU percent assumes the clock-tick rate reported by getconf CLK_TCK",
                "messages are scheduled open-loop; publish lag shows how far behind schedule each publisher ran"));
        envelope.put("application_runtime", identity);
        envelope.put("parameters", Map.of("subscribers", config.subscribers, "rooms", config.rooms,
                "publishers", config.publishers, "rate", config.rate, "warmup_seconds", config.warmupSeconds,
                "measure_seconds", config.measureSeconds));
        envelope.put("results", results);
        if (config.out.getParent() != null) {
            Files.createDirectories(config.out.getParent());
        }
        Files.writeString(config.out, Json.stringify(envelope) + System.lineSeparator());
        System.out.println(Json.stringify(envelope));
    }

    /** Application-process CPU and resident memory over the measured window. */
    private Map<String, Object> applicationTelemetry(long measureStart, long measureEnd) {
        List<Sample> window = new ArrayList<>();
        for (Sample sample : samples) {
            if (sample.nanos() >= measureStart && sample.nanos() <= measureEnd) {
                window.add(sample);
            }
        }
        var telemetry = new java.util.LinkedHashMap<String, Object>();
        telemetry.put("pid", pid);
        telemetry.put("samples_in_window", window.size());
        if (window.size() < 2) {
            telemetry.put("cpu_percent_mean", null);
            telemetry.put("rss_mib_max", null);
            return telemetry;
        }
        Sample first = window.get(0);
        Sample last = window.get(window.size() - 1);
        double seconds = (last.nanos() - first.nanos()) / 1e9;
        telemetry.put("cpu_percent_mean", 100.0 * (last.cpuTicks() - first.cpuTicks()) / clockTicks() / seconds);
        telemetry.put("rss_mib_max", window.stream().mapToLong(Sample::rssKib).max().orElse(0) / 1024.0);
        return telemetry;
    }

    /** Per-second series from the run's own timestamps: scheduled and received counts, and p50 latency by intended second. */
    private Map<String, Object> perSecondSeries(long t0) {
        int buckets = (int) Math.ceil(config.warmupSeconds + config.measureSeconds);
        long[] scheduled = new long[buckets];
        long[] received = new long[buckets];
        List<List<Double>> latency = new ArrayList<>();
        for (int bucket = 0; bucket < buckets; bucket++) {
            latency.add(new ArrayList<>());
        }
        for (Connection connection : connections) {
            for (long[] send : connection.sends) {
                int bucket = bucketOf(send[0], t0, buckets);
                if (bucket >= 0) {
                    scheduled[bucket]++;
                }
            }
            for (long[] arrival : connection.arrivals) {
                int done = bucketOf(arrival[1], t0, buckets);
                if (done >= 0) {
                    received[done]++;
                }
                int start = bucketOf(arrival[0], t0, buckets);
                if (start >= 0) {
                    latency.get(start).add((arrival[1] - arrival[0]) / 1e6);
                }
            }
        }
        List<Object> scheduledSeries = new ArrayList<>();
        List<Object> receivedSeries = new ArrayList<>();
        List<Object> p50Series = new ArrayList<>();
        for (int bucket = 0; bucket < buckets; bucket++) {
            scheduledSeries.add(scheduled[bucket]);
            receivedSeries.add(received[bucket]);
            p50Series.add(percentiles(latency.get(bucket)).get("p50"));
        }
        var series = new java.util.LinkedHashMap<String, Object>();
        series.put("bucket_seconds", 1);
        series.put("scheduled", scheduledSeries);
        series.put("scheduled_bucketed_by", "intended_send_time");
        series.put("completed", receivedSeries);
        series.put("completed_bucketed_by", "receive_time");
        series.put("p50_ms", p50Series);
        series.put("p50_bucketed_by", "intended_send_time");
        return series;
    }

    /** The second of the run a timestamp falls in, or -1 outside the run. */
    private static int bucketOf(long nanos, long t0, int buckets) {
        if (nanos < t0) {
            return -1;
        }
        long second = (nanos - t0) / 1_000_000_000L;
        return second < buckets ? (int) second : -1;
    }

    private static Map<String, Object> percentiles(List<Double> values) {
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("count", values.size());
        if (values.isEmpty()) {
            for (String key : List.of("p50", "p90", "p99", "p999", "max", "mean")) {
                result.put(key, null);
            }
            return result;
        }
        double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        result.put("p50", percentile(sorted, 0.50));
        result.put("p90", percentile(sorted, 0.90));
        result.put("p99", percentile(sorted, 0.99));
        result.put("p999", percentile(sorted, 0.999));
        result.put("max", sorted[sorted.length - 1]);
        result.put("mean", Arrays.stream(sorted).average().orElse(Double.NaN));
        return result;
    }

    private static double percentile(double[] sorted, double quantile) {
        int index = (int) Math.ceil(quantile * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private static long clockTicks() {
        try {
            Process process = new ProcessBuilder("getconf", "CLK_TCK").start();
            String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return Long.parseLong(text);
        } catch (IOException | RuntimeException unavailable) {
            return 100L;
        }
    }

    private Process startApplication(Path work, Path portFile, String token) throws IOException {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var builder = new ProcessBuilder(javaExecutable, "-Xms512m", "-Xmx512m", "-cp", config.appClasspath,
                config.appMain, "0");
        builder.environment().put("BENCH_RUN_TOKEN", token);
        builder.environment().put("BENCH_PORT_FILE", portFile.toString());
        // The application must not inherit injected JVM options or a debug port.
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("JDK_JAVA_OPTIONS");
        builder.environment().remove("_JAVA_OPTIONS");
        builder.redirectErrorStream(true).redirectOutput(work.resolve("app.log").toFile());
        return builder.start();
    }

    private static int awaitPort(Path portFile, Process app) throws InterruptedException, IOException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (!app.isAlive()) {
                throw new IOException("application exited before readiness: " + app.exitValue());
            }
            if (Files.exists(portFile)) {
                String text = Files.readString(portFile).trim();
                if (!text.isEmpty()) {
                    return Integer.parseInt(text);
                }
            }
            Thread.sleep(20);
        }
        throw new IOException("application did not publish a port");
    }

    private static Map<String, Object> awaitIdentity(String base, long appPid, String token) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            Map<String, Object> identity;
            try {
                identity = Json.parseObject(get(base + "/runtime"));
            } catch (IOException notReady) {
                Thread.sleep(50);
                continue;
            }
            // Outside the retry: a wrong process must fail now, not be retried until timeout.
            if (identity.get("run_token") instanceof String value && value.equals(token)
                    && identity.get("pid") instanceof Number pid && pid.longValue() == appPid) {
                return identity;
            }
            throw new IOException("application identity does not match the process this driver started");
        }
        throw new IOException("application identity was never confirmed");
    }

    private void awaitSubscriptions(String base, int expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (((Number) Json.parseObject(get(base + "/api/v1/stats")).get("subscribers")).intValue() == expected) {
                return;
            }
            Thread.sleep(20);
        }
        throw new IOException("subscriptions did not reach " + expected);
    }

    private static String get(String url) throws IOException {
        try {
            var response = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " from " + url);
            }
            return response.body();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", interrupted);
        }
    }

    private void stop(Process app) throws InterruptedException {
        app.destroy();
        if (!app.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
            app.destroyForcibly();
            app.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private static void deleteQuietly(Path directory) {
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort; the directory is under the system temporary directory.
                }
            });
        } catch (IOException ignored) {
            // Nothing to remove.
        }
    }

    /** Intended send time carried in the message text ({@code t:<nanos>|s:<id>}). */
    static long intendedOf(String content) {
        int start = content.indexOf("t:") + 2;
        return Long.parseLong(content.substring(start, content.indexOf('|', start)));
    }

    /** Rooms the application seeds at startup are named room-1 to room-50. */
    private static String roomName(int index) {
        return "room-" + (index + 1);
    }

    private static int positive(Map<String, String> options, String key, int fallback) {
        int value = Integer.parseInt(options.getOrDefault(key, Integer.toString(fallback)));
        if (value < 1) {
            throw new IllegalArgumentException(key + " must be positive");
        }
        return value;
    }

    private static String require(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null) {
            throw new IllegalArgumentException("missing --" + key);
        }
        return value;
    }

    private static Map<String, String> parse(String[] args) {
        var options = new java.util.LinkedHashMap<String, String>();
        for (int index = 0; index < args.length; index += 2) {
            if (!args[index].startsWith("--") || index + 1 == args.length) {
                throw new IllegalArgumentException("expected --name value pairs, got " + args[index]);
            }
            options.put(args[index].substring(2), args[index + 1]);
        }
        return options;
    }
}
