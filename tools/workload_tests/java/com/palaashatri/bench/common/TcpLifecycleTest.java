package com.palaashatri.bench.common;

import java.net.Socket;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Accepted sockets must be released even when shutdown rejects their tasks. */
public final class TcpLifecycleTest {
    public static void main(String[] args) throws Exception {
        AtomicInteger submissions = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        var executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>()) {
            @Override public void execute(Runnable task) {
                if (submissions.incrementAndGet() > 1) throw new RejectedExecutionException("simulated shutdown");
                super.execute(task);
            }
        };
        try (var server = new FramedTcpServer(executor, new FramedTcpServer.Handler() {
            public Map<String, Object> onFrame(FramedTcpServer.Session session, Map<String, Object> frame) { return frame; }
            public void onClose(FramedTcpServer.Session session) { closes.incrementAndGet(); }
        }); Socket client = new Socket("127.0.0.1", server.port())) {
            client.setSoTimeout(2_000);
            if (client.getInputStream().read() != -1) throw new AssertionError("accepted socket leaked after task rejection");
            server.close();
            if (server.connections() != 0 || closes.get() != 1) throw new AssertionError("session cleanup was not exactly once");
        }
        System.out.println("TCP rejected-task shutdown cleanup passed");
    }
}
