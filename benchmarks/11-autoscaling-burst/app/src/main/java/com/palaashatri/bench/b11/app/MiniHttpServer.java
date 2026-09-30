package com.palaashatri.bench.b11.app;

import com.palaashatri.bench.common.ChildJvm;
import com.palaashatri.bench.common.Json;
import com.palaashatri.bench.common.Routes;
import com.palaashatri.bench.common.RuntimeInfo;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Queue-driven local process scaling, with bounded admission and two workers per replica. */
public final class MiniHttpServer implements AutoCloseable {
    private record Job(String query, long workMs) { }
    private static final class Replica {
        final ChildJvm child;
        int busy;
        Replica(ChildJvm child) { this.child = child; }
    }
    private static final class TokenBucket {
        double tokens = 200;
        long at = System.nanoTime();
        synchronized boolean consume() {
            long now = System.nanoTime();
            tokens = Math.min(200, tokens + (now - at) / 1_000_000.0);
            at = now;
            if (tokens < 1) return false;
            tokens--; return true;
        }
    }
    private final String benchmark;
    private final boolean gateway = RuntimeInfo.role().equals("gateway");
    private final Object lock = new Object();
    private final ArrayList<Replica> replicas = new ArrayList<>();
    private final ArrayBlockingQueue<Job> queue = new ArrayBlockingQueue<>(1_000);
    private final TokenBucket tokens = new TokenBucket();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong scaleUps = new AtomicLong();
    private final AtomicLong scaleDowns = new AtomicLong();
    private final java.util.concurrent.ExecutorService http = Executors.newCachedThreadPool();
    private final java.util.concurrent.ExecutorService dispatchers = Executors.newFixedThreadPool(8);
    private final java.util.concurrent.ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private volatile boolean stopping;
    private volatile String scaleError = "";
    private long lastWork = System.nanoTime();
    private HttpServer server;

    public MiniHttpServer(String benchmark, String ignoredTitle) { this.benchmark = benchmark; }
    private Replica launch() throws IOException {
        return new Replica(ChildJvm.start(BenchmarkApp.class.getName(), "worker", RuntimeInfo.token(), Map.of()));
    }
    public void start(int port) throws IOException {
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "scaler-shutdown"));
        try {
            if (gateway) {
                replicas.add(launch());
                for (int i = 0; i < 8; i++) dispatchers.execute(this::dispatch);
                monitor.scheduleWithFixedDelay(this::scale, 100, 100, TimeUnit.MILLISECONDS);
            } else if (!RuntimeInfo.role().equals("worker")) throw new IllegalArgumentException("invalid autoscaling role");
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 256);
            server.setExecutor(http);
            server.createContext("/health", Routes.guard(this::health));
            server.createContext("/runtime", Routes.guard(e -> Json.bytes(e, 200, "application/json", RuntimeInfo.json())));
            server.createContext("/metrics", Routes.guard(this::metrics));
            if (gateway) {
                server.createContext("/api/v1/catalog/search", Routes.guard(this::search));
                server.createContext("/api/v1/catalog/health", Routes.guard(this::health));
                server.createContext("/api/v1/metrics/scaling", Routes.guard(this::scaling));
            } else server.createContext("/work", Routes.guard(this::work));
            server.start(); RuntimeInfo.publishPort(server.getAddress().getPort());
            System.out.println(Json.stringify(Map.of("event", "started", "benchmark", benchmark, "role", RuntimeInfo.role(), "port", server.getAddress().getPort())));
        } catch (IOException | RuntimeException failure) { close(); throw failure; }
    }
    private void search(HttpExchange exchange) throws IOException {
        if (!Routes.method(exchange, "POST")) return;
        Map<String, Object> body = Json.read(exchange);
        long workMs = Json.number(body, "work_ms", 25);
        String query = Json.string(body, "query", "unknown");
        if (workMs < 1 || workMs > 500 || query.length() > 1_024) throw new IllegalArgumentException("invalid work");
        if (!tokens.consume()) { reject(exchange, "rate_limited"); return; }
        synchronized (lock) {
            if (stopping || !queue.offer(new Job(query, workMs))) { reject(exchange, stopping ? "shutting_down" : "queue_full"); return; }
            accepted.incrementAndGet(); lastWork = System.nanoTime(); lock.notifyAll();
        }
        Json.reply(exchange, 202, Map.of("accepted", true, "completed", false, "execution_model", "queued-process-work", "query", query));
    }
    private void reject(HttpExchange exchange, String reason) throws IOException {
        rejected.incrementAndGet(); Json.reply(exchange, 429, Map.of("accepted", false, "reason", reason));
    }
    private void work(HttpExchange exchange) throws IOException {
        if (!Routes.method(exchange, "POST")) return;
        var body = Json.read(exchange);
        long workMs = Json.number(body, "work_ms", -1);
        if (workMs < 1 || workMs > 500) throw new IllegalArgumentException("invalid work");
        try { Thread.sleep(workMs); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); Json.reply(exchange, 503, Map.of("completed", false)); return; }
        completed.incrementAndGet();
        Json.reply(exchange, 200, Map.of("completed", true, "query", Json.string(body, "query", ""), "pid", ProcessHandle.current().pid()));
    }
    private void dispatch() {
        while (!stopping) {
            Replica target = null;
            Job job = null;
            synchronized (lock) {
                if (!queue.isEmpty()) {
                    for (Replica candidate : replicas) if (candidate.child.alive() && candidate.busy < 2) { target = candidate; break; }
                    if (target != null) { job = queue.poll(); target.busy++; }
                }
                if (target == null) {
                    try { lock.wait(100); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                    continue;
                }
            }
            try {
                var response = Routes.call(client, target.child.port(), "POST", "/work", Json.stringify(Map.of("query", job.query, "work_ms", job.workMs)));
                var result = Json.parseObject(response.body());
                if (response.statusCode() != 200 || !Boolean.TRUE.equals(result.get("completed")) || Json.number(result, "pid", -1) != target.child.pid())
                    throw new IOException("replica did not complete owned work");
                completed.incrementAndGet();
            } catch (IOException | RuntimeException unavailable) { failed.incrementAndGet(); }
            finally { synchronized (lock) { target.busy--; lastWork = System.nanoTime(); lock.notifyAll(); } }
        }
    }
    private void scale() {
        if (stopping) return;
        Replica retire = null;
        boolean grow;
        synchronized (lock) {
            replicas.removeIf(replica -> {
                if (replica.child.alive()) return false;
                replica.child.close(); return true;
            });
            grow = replicas.isEmpty() || (queue.size() > 4 && replicas.size() < 4);
            if (!grow && queue.isEmpty() && replicas.size() > 1
                    && replicas.stream().allMatch(r -> r.busy == 0) && System.nanoTime() - lastWork > 1_000_000_000L) {
                retire = replicas.remove(replicas.size() - 1);
                scaleDowns.incrementAndGet();
            }
        }
        if (retire != null) retire.child.close();
        if (grow) {
            try {
                Replica replica = launch();
                synchronized (lock) {
                    if (stopping) replica.child.close();
                    else { replicas.add(replica); scaleUps.incrementAndGet(); scaleError = ""; lock.notifyAll(); }
                }
            } catch (IOException failure) { scaleError = "replica_start_failed"; }
        }
    }
    private Map<String, Object> state() {
        synchronized (lock) {
            var children = new ArrayList<Map<String, Object>>();
            for (Replica replica : replicas) children.add(Map.of("pid", replica.child.pid(), "port", replica.child.port(), "alive", replica.child.alive(), "active_workers", replica.busy));
            var state = new java.util.LinkedHashMap<String, Object>();
            long live = replicas.stream().filter(r -> r.child.alive()).count();
            state.put("scaling_model", "local-process-replicas"); state.put("external_replicas", live);
            state.put("replicas", children); state.put("worker_capacity", live * 2); state.put("queue_depth", queue.size());
            state.put("active_workers", replicas.stream().mapToInt(r -> r.busy).sum());
            state.put("scale_up_count", scaleUps.get()); state.put("scale_down_count", scaleDowns.get());
            state.put("accepted", accepted.get()); state.put("completed", completed.get()); state.put("failed", failed.get());
            state.put("rejected", rejected.get()); state.put("scale_error", scaleError.isEmpty() ? null : scaleError);
            return state;
        }
    }
    private void scaling(HttpExchange exchange) throws IOException {
        if (Routes.method(exchange, "GET")) Json.reply(exchange, 200, state());
    }
    private void health(HttpExchange exchange) throws IOException {
        var state = state();
        boolean up = !gateway || ((Long) state.get("external_replicas")) > 0;
        Json.reply(exchange, up ? 200 : 503, Map.of("status", up ? "UP" : "DEGRADED", "scaling_model", "local-process-replicas", "external_replicas", state.get("external_replicas")));
    }
    private void metrics(HttpExchange exchange) throws IOException {
        var state = state();
        Json.bytes(exchange, 200, "text/plain; version=0.0.4", "capacity_external_replicas " + state.get("external_replicas") + "\n"
                + "capacity_requests_accepted_total " + accepted.get() + "\n" + "capacity_requests_completed_total " + completed.get() + "\n"
                + "capacity_requests_failed_total " + failed.get() + "\n" + "capacity_queue_depth " + state.get("queue_depth") + "\n");
    }
    @Override public void close() {
        synchronized (lock) { if (stopping) return; stopping = true; lock.notifyAll(); }
        if (server != null) server.stop(0);
        monitor.shutdownNow(); dispatchers.shutdownNow(); http.shutdownNow();
        synchronized (lock) { replicas.forEach(r -> r.child.close()); }
    }
}
