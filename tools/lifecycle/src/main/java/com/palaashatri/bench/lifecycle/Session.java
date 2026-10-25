package com.palaashatri.bench.lifecycle;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** A newline-framed TCP session to a workload's persistent-connection endpoint. */
public final class Session implements AutoCloseable {
    private final Socket socket;
    private final BufferedReader reader;
    private final OutputStream writer;

    public Session(int port) {
        try {
            socket = new Socket();
            socket.connect(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                    (int) Duration.ofSeconds(5).toMillis());
            socket.setSoTimeout((int) Duration.ofSeconds(5).toMillis());
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            writer = socket.getOutputStream();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Sends one JSON frame followed by a newline. */
    public void send(String json) {
        raw((json + "\n").getBytes(StandardCharsets.UTF_8));
    }

    /** Sends bytes as they are, for malformed or oversized frames. */
    public void raw(byte[] bytes) {
        try {
            writer.write(bytes);
            writer.flush();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Reads one frame. Fails if the peer closed the connection. */
    public String read() {
        try {
            String line = reader.readLine();
            if (line == null) {
                throw new IllegalStateException("session unexpectedly closed");
            }
            return line;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Abortive close: SO_LINGER with a zero timeout, as a client that disappears mid-conversation does. */
    public void abort() throws IOException {
        socket.setSoLinger(true, 0);
        socket.close();
    }

    public Socket socket() {
        return socket;
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Closing a session that the peer already dropped is not a test failure.
        }
    }
}
