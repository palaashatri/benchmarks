package com.palaashatri.bench.lifecycle;

import com.palaashatri.bench.common.Json;
import com.palaashatri.bench.testkit.AppProcess;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A workload application under test, together with the child processes the test has seen it start. Closing it
 * stops the application and fails the test if any of those children is still running afterwards.
 */
public final class OwnedApp implements AutoCloseable {
    private final AppProcess app;
    private final Set<Long> children = new LinkedHashSet<Long>();

    private OwnedApp(AppProcess app) {
        this.app = app;
    }

    public static OwnedApp start(String mainClass) {
        return new OwnedApp(AppProcess.builder(mainClass).start());
    }

    public AppProcess app() {
        return app;
    }

    public void track(long pid) {
        children.add(pid);
    }

    public void trackAll(Iterable<Long> pids) {
        for (long pid : pids) {
            children.add(pid);
        }
    }

    public Set<Long> children() {
        return children;
    }

    /** A JSON object from a GET of the given path. */
    public Map<String, Object> json(String path) {
        return Json.parseObject(app.get(path));
    }

    /** A JSON object from a POST of the given body. */
    public Map<String, Object> post(String path, String body) {
        return Json.parseObject(app.request("POST", path, body));
    }

    /** The elements of an array field, each as a JSON object. */
    public static List<Map<String, Object>> objects(Map<String, Object> document, String field) {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (Object item : (List<?>) document.get(field)) {
            items.add(Json.parseObject(Json.stringify(item)));
        }
        return items;
    }

    @Override
    public void close() {
        app.close();
        for (long pid : children) {
            if (Liveness.alive(pid)) {
                Eventually.until(() -> !Liveness.alive(pid), Duration.ofSeconds(5));
            }
        }
    }
}
