package com.palaashatri.bench.b05.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

public final class MiniHttpServer {
    private final String benchmark;
    private final Map<String, Script> scriptCache = new ConcurrentHashMap<>();
    private final AtomicLong compileTimeMs = new AtomicLong();
    private final AtomicLong execTimeMs = new AtomicLong();
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong polyglotRequests = new AtomicLong();
    private final AtomicLong totalRequests = new AtomicLong();

    private static final String RULE_1 = "function score(data) { return data.amount > 1000 ? 0.9 : data.amount / 1000.0 * 0.5; } score(data);";
    private static final String RULE_2 = "function eligible(data) { return data.age >= 18 && data.income > 50000 ? \"approved\" : \"rejected\"; } eligible(data);";
    private static final String RULE_3 = "function classify(data) { var s = data.text.toLowerCase(); return s.indexOf(\"urgent\") >= 0 ? \"high\" : \"normal\"; } classify(data);";

    public MiniHttpServer(String benchmark, String title) {
        this.benchmark = benchmark;
        precompileRules();
    }

    private void precompileRules() {
        compileAndCache("rule-1", RULE_1);
        compileAndCache("rule-2", RULE_2);
        compileAndCache("rule-3", RULE_3);
    }

    private void compileAndCache(String key, String source) {
        long start = System.currentTimeMillis();
        Context cx = Context.enter();
        try {
            cx.setOptimizationLevel(9);
            Script script = cx.compileString(source, key, 1, null);
            scriptCache.put(key, script);
        } finally {
            Context.exit();
        }
        compileTimeMs.addAndGet(System.currentTimeMillis() - start);
        // also pre-cache by source text so evalScript lookup by source works
        compileAndCacheBySource(source);
    }

    private void compileAndCacheBySource(String source) {
        if (scriptCache.containsKey(source)) return;
        long start = System.currentTimeMillis();
        Context cx = Context.enter();
        try {
            cx.setOptimizationLevel(9);
            Script script = cx.compileString(source, "<inline>", 1, null);
            scriptCache.put(source, script);
        } finally {
            Context.exit();
        }
        compileTimeMs.addAndGet(System.currentTimeMillis() - start);
    }

    public void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 256);
        server.createContext("/health", this::health);
        server.createContext("/metrics", this::metrics);
        server.createContext("/actuator/health", this::health);
        server.createContext("/actuator/prometheus", this::metrics);
        server.createContext("/", this::route);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        log("started", "\"port\":" + port);
    }

    private String evalScript(String scriptSource, Map<String, Object> data) {
        boolean cached = scriptCache.containsKey(scriptSource);
        if (cached) {
            cacheHits.incrementAndGet();
        } else {
            // Compile and cache
            long compStart = System.currentTimeMillis();
            Context cx = Context.enter();
            try {
                cx.setOptimizationLevel(9);
                Script script = cx.compileString(scriptSource, "<dynamic>", 1, null);
                scriptCache.put(scriptSource, script);
            } finally {
                Context.exit();
            }
            compileTimeMs.addAndGet(System.currentTimeMillis() - compStart);
        }

        Script script = scriptCache.get(scriptSource);

        long execStart = System.currentTimeMillis();
        Context cx = Context.enter();
        Object result;
        try {
            Scriptable scope = cx.initStandardObjects();
            // Create a JS object for data
            Scriptable dataObj = cx.newObject(scope);
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                Object val = entry.getValue();
                if (val instanceof Double) {
                    ScriptableObject.putProperty(dataObj, entry.getKey(), val);
                } else if (val instanceof Long) {
                    ScriptableObject.putProperty(dataObj, entry.getKey(), ((Long) val).doubleValue());
                } else if (val instanceof Integer) {
                    ScriptableObject.putProperty(dataObj, entry.getKey(), ((Integer) val).doubleValue());
                } else {
                    ScriptableObject.putProperty(dataObj, entry.getKey(), Context.javaToJS(val, scope));
                }
            }
            ScriptableObject.putProperty(scope, "data", dataObj);
            result = script.exec(cx, scope);
        } finally {
            Context.exit();
        }
        execTimeMs.addAndGet(System.currentTimeMillis() - execStart);
        return Context.toString(result);
    }

    private void health(HttpExchange ex) throws IOException {
        json(ex, 200, "{\"status\":\"UP\",\"cached_scripts\":" + scriptCache.size() + "}");
    }

    private void metrics(HttpExchange ex) throws IOException {
        long reqs = totalRequests.get();
        long compilMs = compileTimeMs.get();
        long execMs = execTimeMs.get();
        long hits = cacheHits.get();
        long cacheSize = scriptCache.size();
        String body = "polyglot_requests_total " + reqs + "\n"
                + "polyglot_compile_ms_total " + compilMs + "\n"
                + "polyglot_exec_ms_total " + execMs + "\n"
                + "polyglot_cache_hits_total " + hits + "\n"
                + "script_cache_size " + cacheSize + "\n"
                + "benchmark_requests_total{benchmark=\"" + benchmark + "\"} " + reqs + "\n";
        bytes(ex, 200, "text/plain; version=0.0.4", body);
    }

    private void route(HttpExchange ex) throws IOException {
        totalRequests.incrementAndGet();
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        try {
            String response = handle(method, path, body);
            json(ex, 200, response);
        } catch (IllegalArgumentException e) {
            json(ex, 404, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
        } catch (Exception e) {
            json(ex, 500, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
        }
    }

    private String handle(String method, String path, String body) {
        if ("GET".equals(method) && path.equals("/api/v1/scripts")) {
            return "{\"cached_scripts\":" + scriptCache.size()
                    + ",\"compile_ms_total\":" + compileTimeMs.get()
                    + ",\"exec_ms_total\":" + execTimeMs.get()
                    + ",\"cache_hits\":" + cacheHits.get() + "}";
        }
        if ("POST".equals(method) && path.equals("/api/v1/score")) {
            return handleCustomScript(body);
        }
        if ("POST".equals(method) && path.equals("/api/v1/score/rule/1")) {
            double amount = numberDouble(body, "amount", 500.0);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("amount", amount);
            boolean wasCached = scriptCache.containsKey(RULE_1);
            String result = evalScript(RULE_1, data);
            return "{\"result\":\"" + escape(result) + "\",\"exec_ms\":" + execTimeMs.get() + ",\"cached\":" + wasCached + "}";
        }
        if ("POST".equals(method) && path.equals("/api/v1/score/rule/2")) {
            double age = numberDouble(body, "age", 25.0);
            double income = numberDouble(body, "income", 60000.0);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("age", age);
            data.put("income", income);
            boolean wasCached = scriptCache.containsKey(RULE_2);
            String result = evalScript(RULE_2, data);
            return "{\"result\":\"" + escape(result) + "\",\"exec_ms\":" + execTimeMs.get() + ",\"cached\":" + wasCached + "}";
        }
        if ("POST".equals(method) && path.equals("/api/v1/score/rule/3")) {
            String text = field(body, "text", "normal");
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("text", text);
            boolean wasCached = scriptCache.containsKey(RULE_3);
            String result = evalScript(RULE_3, data);
            return "{\"result\":\"" + escape(result) + "\",\"exec_ms\":" + execTimeMs.get() + ",\"cached\":" + wasCached + "}";
        }
        throw new IllegalArgumentException("no route for " + path);
    }

    private String handleCustomScript(String body) {
        // Parse "script" field
        String script = field(body, "script", "");
        if (script.isEmpty()) {
            throw new IllegalArgumentException("missing 'script' field");
        }

        // Parse "data" object: find "data":{...}
        Map<String, Object> data = parseDataObject(body);

        boolean wasCached = scriptCache.containsKey(script);
        String result = evalScript(script, data);
        long execMs = execTimeMs.get();
        return "{\"result\":\"" + escape(result) + "\",\"exec_ms\":" + execMs + ",\"cached\":" + wasCached + "}";
    }

    private Map<String, Object> parseDataObject(String body) {
        Map<String, Object> data = new LinkedHashMap<>();
        // Find "data": { ... }
        String dataKey = "\"data\"";
        int keyIdx = body.indexOf(dataKey);
        if (keyIdx < 0) return data;
        int colon = body.indexOf(':', keyIdx + dataKey.length());
        if (colon < 0) return data;
        int braceOpen = body.indexOf('{', colon + 1);
        if (braceOpen < 0) return data;
        int braceClose = body.indexOf('}', braceOpen + 1);
        if (braceClose < 0) return data;
        String inner = body.substring(braceOpen + 1, braceClose).trim();
        if (inner.isEmpty()) return data;

        // Parse key-value pairs: "key": value
        int pos = 0;
        while (pos < inner.length()) {
            // Skip whitespace and commas
            while (pos < inner.length() && (inner.charAt(pos) == ',' || Character.isWhitespace(inner.charAt(pos)))) pos++;
            if (pos >= inner.length()) break;
            // Expect "key"
            if (inner.charAt(pos) != '"') break;
            int keyStart = pos + 1;
            int keyEnd = inner.indexOf('"', keyStart);
            if (keyEnd < 0) break;
            String key = inner.substring(keyStart, keyEnd);
            pos = keyEnd + 1;
            // Skip whitespace and colon
            while (pos < inner.length() && (inner.charAt(pos) == ':' || Character.isWhitespace(inner.charAt(pos)))) pos++;
            if (pos >= inner.length()) break;
            // Parse value
            char valChar = inner.charAt(pos);
            if (valChar == '"') {
                // String value
                int valStart = pos + 1;
                int valEnd = inner.indexOf('"', valStart);
                if (valEnd < 0) break;
                data.put(key, inner.substring(valStart, valEnd));
                pos = valEnd + 1;
            } else {
                // Numeric value
                int valStart = pos;
                while (pos < inner.length() && inner.charAt(pos) != ',' && inner.charAt(pos) != '}') pos++;
                String numStr = inner.substring(valStart, pos).trim();
                try {
                    data.put(key, Double.parseDouble(numStr));
                } catch (NumberFormatException e) {
                    data.put(key, numStr);
                }
            }
        }
        return data;
    }

    private static String field(String body, String name, String fallback) {
        String quoted = "\"" + name + "\"";
        int key = body.indexOf(quoted);
        if (key < 0) return fallback;
        int colon = body.indexOf(':', key + quoted.length());
        if (colon < 0) return fallback;
        int firstQuote = body.indexOf('"', colon + 1);
        if (firstQuote < 0) return fallback;
        int secondQuote = body.indexOf('"', firstQuote + 1);
        if (secondQuote < 0) return fallback;
        return body.substring(firstQuote + 1, secondQuote);
    }

    private static double numberDouble(String body, String name, double fallback) {
        String quoted = "\"" + name + "\"";
        int key = body.indexOf(quoted);
        if (key < 0) return fallback;
        int colon = body.indexOf(':', key + quoted.length());
        if (colon < 0) return fallback;
        int start = colon + 1;
        while (start < body.length() && Character.isWhitespace(body.charAt(start))) start++;
        int end = start;
        while (end < body.length() && (Character.isDigit(body.charAt(end)) || body.charAt(end) == '-' || body.charAt(end) == '.')) end++;
        if (end == start) return fallback;
        try { return Double.parseDouble(body.substring(start, end)); } catch (NumberFormatException e) { return fallback; }
    }

    private static String escape(String raw) {
        if (raw == null) return "";
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private static void json(HttpExchange ex, int status, String body) throws IOException {
        bytes(ex, status, "application/json", body);
    }

    private static void bytes(HttpExchange ex, int status, String contentType, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, data.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(data); }
    }

    private void log(String event, String fields) {
        System.out.println("{\"event\":\"" + event + "\",\"benchmark\":\"" + benchmark + "\"," + fields + "}");
    }
}
