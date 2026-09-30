package com.palaashatri.bench.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded newline-framed JSON sessions with isolated readers and writers. */
public final class FramedTcpServer implements AutoCloseable {
    public interface Handler {
        Map<String, Object> onFrame(Session session, Map<String, Object> frame);
        default void onClose(Session session) { }
        default void onWritten(Session session, Map<String, Object> frame) { }
    }
    private static final Map<String, Object> END = Map.of();
    private final ServerSocket listener;
    private final ExecutorService executor;
    private final Handler handler;
    private final Set<Session> sessions = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object lifecycle = new Object();
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "tcp-drain-deadline");
        thread.setDaemon(true);
        return thread;
    });

    public FramedTcpServer(ExecutorService executor, Handler handler) throws IOException {
        this.executor = executor; this.handler = handler;
        listener = new ServerSocket();
        try {
            listener.bind(new InetSocketAddress("127.0.0.1", 0), 256);
            executor.execute(this::accept);
        } catch (IOException | RuntimeException failure) {
            listener.close(); executor.shutdownNow(); deadlines.shutdownNow(); throw failure;
        }
    }
    public int port() { return listener.getLocalPort(); }
    public int connections() { return sessions.size(); }
    private void accept() {
        while (!closed.get()) {
            Socket socket = null;
            Session session = null;
            try {
                socket = listener.accept();
                socket.setTcpNoDelay(true);
                session = new Session(socket);
                synchronized (lifecycle) {
                    if (closed.get() || sessions.size() >= 4_096) {
                        socket.close(); continue;
                    }
                    sessions.add(session);
                    executor.execute(session::write);
                    executor.execute(session::read);
                }
            } catch (IOException | RuntimeException failure) {
                if (session != null) session.close();
                else if (socket != null) try { socket.close(); } catch (IOException ignored) { }
                if (!closed.get()) close();
            }
        }
    }

    public final class Session implements AutoCloseable {
        private final Socket socket;
        private final ArrayBlockingQueue<Map<String, Object>> outbound = new ArrayBlockingQueue<>(256);
        private final AtomicBoolean ended = new AtomicBoolean();
        private volatile boolean readingEnded;
        private Session(Socket socket) { this.socket = socket; }
        public boolean isOpen() { return !ended.get(); }

        /** Enqueue a frame; disconnect a slow reader when its queue is full. */
        public boolean offer(Map<String, Object> frame) {
            if (ended.get() || readingEnded) return false;
            if (outbound.offer(frame)) return true;
            close(); return false;
        }
        private void read() {
            try {
                var input = socket.getInputStream();
                while (!ended.get()) {
                    var bytes = new ByteArrayOutputStream();
                    int ch;
                    while ((ch = input.read()) != -1 && ch != '\n') {
                        if (bytes.size() == Json.MAX_BYTES) {
                            offer(Map.of("error", "frame_too_large"));
                            finish(); return;
                        }
                        bytes.write(ch);
                    }
                    if (ch == -1) { close(); return; }
                    try {
                        String text = StandardCharsets.UTF_8.newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
                        Map<String, Object> reply = handler.onFrame(this, Json.parseObject(text));
                        if (reply != null) offer(reply);
                    } catch (IllegalArgumentException | java.nio.charset.CharacterCodingException invalid) {
                        offer(Map.of("error", "invalid_frame"));
                    }
                }
            } catch (IOException failure) { close(); }
            catch (RuntimeException failure) { close(); }
        }
        private void finish() {
            readingEnded = true;
            if (!outbound.offer(END)) { close(); return; }
            // A peer may stop reading while the writer is blocked in the kernel.
            // Give the terminal error a bounded chance to drain, then release it.
            try { deadlines.schedule(this::close, 1, TimeUnit.SECONDS); }
            catch (java.util.concurrent.RejectedExecutionException shutdown) { close(); }
        }
        private void write() {
            try {
                var output = socket.getOutputStream();
                while (!ended.get()) {
                    Map<String, Object> frame = outbound.take();
                    if (frame == END) break;
                    output.write((Json.stringify(frame) + "\n").getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    handler.onWritten(this, frame);
                }
            } catch (IOException failure) { /* Peer disconnected. */ }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { close(); }
        }
        @Override public void close() {
            if (!ended.compareAndSet(false, true)) return;
            sessions.remove(this);
            try { socket.close(); } catch (IOException ignored) { }
            outbound.clear(); outbound.offer(END);
            handler.onClose(this);
        }
    }
    @Override public void close() {
        Session[] remaining;
        synchronized (lifecycle) {
            if (!closed.compareAndSet(false, true)) return;
            try { listener.close(); } catch (IOException ignored) { }
            remaining = sessions.toArray(Session[]::new);
        }
        for (Session session : remaining) session.close();
        deadlines.shutdownNow();
        executor.shutdownNow();
    }
}
