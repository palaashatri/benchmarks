package com.palaashatri.bench.lifecycle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Whether an operating-system process is still running. A zombie (exited but not yet reaped) counts as not running,
 * so a child that has exited is never reported as leaked. Zombie detection reads Linux process state; elsewhere the
 * platform's own liveness answer is used.
 */
public final class Liveness {
    private Liveness() {
    }

    public static boolean alive(long pid) {
        boolean running = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        if (!running) {
            return false;
        }
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("linux")) {
            return !zombie(pid);
        }
        return true;
    }

    private static boolean zombie(long pid) {
        Path stat = Path.of("/proc", Long.toString(pid), "stat");
        try {
            String text = new String(Files.readAllBytes(stat), StandardCharsets.UTF_8);
            int close = text.lastIndexOf(')');
            return close >= 0 && text.substring(close + 2).startsWith("Z");
        } catch (IOException exception) {
            return true;
        }
    }
}
