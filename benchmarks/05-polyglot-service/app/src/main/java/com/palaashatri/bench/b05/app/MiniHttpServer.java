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
import java.util.concurrent.atomic.LongAdder;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextAction;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

/** Sandboxed embedded-JavaScript workload running entirely on OpenJDK. */
public final class MiniHttpServer {
    private static final int MAX_SCRIPT_CHARS = 1_024;
    private static final int INSTRUCTION_BUDGET = 100_000;
    private static final String RULE_1 =
            "function score(data){return data.amount>1000?0.9:data.amount/1000.0*0.5;}score(data);";
    private static final String RULE_2 =
            "function eligible(data){return data.age>=18&&data.income>50000?'approved':'rejected';}eligible(data);";
    private static final String RULE_3 =
            "function classify(data){var s=data.text.toLowerCase();return s.indexOf('urgent')>=0?'high':'normal';}classify(data);";

    private final String benchmark;
    private final SandboxedContextFactory contextFactory = new SandboxedContextFactory();
    private final ConcurrentHashMap<String, Script> scripts = new ConcurrentHashMap<>();
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();
    private final AtomicLong rejectedScripts = new AtomicLong();
    private final LongAdder compilationNanos = new LongAdder();
    private final LongAdder executionNanos = new LongAdder();

    public MiniHttpServer(String benchmark, String ignoredTitle) {
        this.benchmark = benchmark;
        scripts.put("rule-1", compileUncached("rule-1", RULE_1));
        scripts.put("rule-2", compileUncached("rule-2", RULE_2));
        scripts.put("rule-3", compileUncached("rule-3", RULE_3));
    }

    public void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 256);
        server.createContext("/health", this::health);
        server.createContext("/runtime", this::runtime);
        server.createContext("/metrics", this::metrics);
        server.createContext("/api/v1/scripts", this::scriptStatus);
        server.createContext("/api/v1/score", this::score);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.printf(
                "{\"event\":\"started\",\"benchmark\":\"%s\","
                        + "\"engine\":\"rhino-interpreter\",\"host_class_access\":false,"
                        + "\"port\":%d,\"pid\":%d}%n",
                benchmark, port, ProcessHandle.current().pid());
    }

    private void health(HttpExchange exchange) throws IOException {
        json(exchange, 200,
                "{\"status\":\"UP\",\"engine\":\"rhino-interpreter\","
                        + "\"host_class_access\":false,\"instruction_budget\":"
                        + INSTRUCTION_BUDGET + ",\"cached_scripts\":" + scripts.size() + "}");
    }

    private void runtime(HttpExchange exchange) throws IOException {
        json(exchange, 200,
                "{\"pid\":" + ProcessHandle.current().pid()
                        + ",\"run_token\":\""
                        + escape(System.getenv().getOrDefault("BENCH_RUN_TOKEN", ""))
                        + "\",\"java_version\":\""
                        + escape(System.getProperty("java.version")) + "\"}");
    }

    private void scriptStatus(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            json(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        json(exchange, 200,
                "{\"engine\":\"rhino-interpreter\",\"cached_scripts\":" + scripts.size()
                        + ",\"cache_hits\":" + cacheHits.get()
                        + ",\"cache_misses\":" + cacheMisses.get()
                        + ",\"rejected_scripts\":" + rejectedScripts.get()
                        + ",\"host_class_access\":false}");
    }

    private void score(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            json(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        try {
            Evaluation evaluation;
            if ("/api/v1/score/rule/1".equals(path)) {
                evaluation = evaluate("rule-1", RULE_1, Map.of(
                        "amount", doubleField(body, "amount", 500)));
            } else if ("/api/v1/score/rule/2".equals(path)) {
                evaluation = evaluate("rule-2", RULE_2, Map.of(
                        "age", doubleField(body, "age", 25),
                        "income", doubleField(body, "income", 60_000)));
            } else if ("/api/v1/score/rule/3".equals(path)) {
                evaluation = evaluate("rule-3", RULE_3, Map.of(
                        "text", stringField(body, "text", "normal")));
            } else if ("/api/v1/score".equals(path)) {
                String source = stringField(body, "script", "");
                validateDynamicScript(source);
                evaluation = evaluate("dynamic:" + source, source, parseData(body));
            } else {
                json(exchange, 404, "{\"error\":\"not_found\"}");
                return;
            }
            json(exchange, 200,
                    "{\"result\":\"" + escape(evaluation.result())
                            + "\",\"cache_hit\":" + evaluation.cacheHit()
                            + ",\"compile_ns\":" + evaluation.compileNanos()
                            + ",\"execution_ns\":" + evaluation.executionNanos()
                            + ",\"engine\":\"rhino-interpreter\"}");
        } catch (IllegalArgumentException invalid) {
            rejectedScripts.incrementAndGet();
            json(exchange, 400,
                    "{\"error\":\"invalid_script\",\"message\":\""
                            + escape(invalid.getMessage()) + "\"}");
        } catch (EvaluatorException budget) {
            rejectedScripts.incrementAndGet();
            json(exchange, 422,
                    "{\"error\":\"script_budget_exceeded\",\"message\":\""
                            + escape(budget.getMessage()) + "\"}");
        } catch (RuntimeException failed) {
            json(exchange, 422,
                    "{\"error\":\"script_execution_failed\",\"message\":\""
                            + escape(failed.getMessage()) + "\"}");
        }
    }

    private Evaluation evaluate(String key, String source, Map<String, Object> data) {
        Script script = scripts.get(key);
        boolean hit = script != null;
        long compileNs = 0;
        if (script == null) {
            long started = System.nanoTime();
            Script compiled = compileUncached(key, source);
            compileNs = System.nanoTime() - started;
            Script existing = scripts.putIfAbsent(key, compiled);
            if (existing == null) {
                script = compiled;
                cacheMisses.incrementAndGet();
            } else {
                script = existing;
                hit = true;
                compileNs = 0;
                cacheHits.incrementAndGet();
            }
        } else {
            cacheHits.incrementAndGet();
        }

        Script selected = script;
        long started = System.nanoTime();
        Object result = contextFactory.call((ContextAction<Object>) context -> {
            Scriptable scope = context.initStandardObjects(null, true);
            Scriptable object = context.newObject(scope);
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                ScriptableObject.putProperty(
                        object,
                        entry.getKey(),
                        Context.javaToJS(entry.getValue(), scope));
            }
            ScriptableObject.putProperty(scope, "data", object);
            return selected.exec(context, scope);
        });
        long executionNs = System.nanoTime() - started;
        executionNanos.add(executionNs);
        return new Evaluation(Context.toString(result), hit, compileNs, executionNs);
    }

    private Script compileUncached(String key, String source) {
        long started = System.nanoTime();
        Script script = contextFactory.call((ContextAction<Script>) context ->
                context.compileString(source, key, 1, null));
        compilationNanos.add(System.nanoTime() - started);
        return script;
    }

    private static void validateDynamicScript(String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("script is required");
        }
        if (source.length() > MAX_SCRIPT_CHARS) {
            throw new IllegalArgumentException("script exceeds " + MAX_SCRIPT_CHARS + " characters");
        }
        String lower = source.toLowerCase(java.util.Locale.ROOT);
        for (String token : new String[]{
                "packages", "javapackage", "importclass", "importpackage", "getclass"}) {
            if (lower.contains(token)) {
                throw new IllegalArgumentException("host access token is prohibited: " + token);
            }
        }
    }

    private static Map<String, Object> parseData(String body) {
        Map<String, Object> values = new LinkedHashMap<>();
        int key = body.indexOf("\"data\"");
        int open = key < 0 ? -1 : body.indexOf('{', key);
        int close = open < 0 ? -1 : body.indexOf('}', open + 1);
        if (open < 0 || close < 0) return values;
        String object = body.substring(open + 1, close);
        int position = 0;
        while (position < object.length()) {
            int keyStart = object.indexOf('"', position);
            int keyEnd = keyStart < 0 ? -1 : object.indexOf('"', keyStart + 1);
            if (keyStart < 0 || keyEnd < 0) break;
            String name = object.substring(keyStart + 1, keyEnd);
            int colon = object.indexOf(':', keyEnd + 1);
            if (colon < 0) break;
            int valueStart = colon + 1;
            while (valueStart < object.length() && Character.isWhitespace(object.charAt(valueStart))) {
                valueStart++;
            }
            if (valueStart < object.length() && object.charAt(valueStart) == '"') {
                int valueEnd = object.indexOf('"', valueStart + 1);
                if (valueEnd < 0) break;
                values.put(name, object.substring(valueStart + 1, valueEnd));
                position = valueEnd + 1;
            } else {
                int valueEnd = valueStart;
                while (valueEnd < object.length() && object.charAt(valueEnd) != ',') valueEnd++;
                String raw = object.substring(valueStart, valueEnd).trim();
                try {
                    values.put(name, Double.parseDouble(raw));
                } catch (NumberFormatException ignored) {
                    values.put(name, raw);
                }
                position = valueEnd + 1;
            }
        }
        return values;
    }

    private void metrics(HttpExchange exchange) throws IOException {
        String body = "# TYPE dynamic_script_requests_total counter\n"
                + "dynamic_script_requests_total " + requests.get() + "\n"
                + "# TYPE dynamic_script_cache_hits_total counter\n"
                + "dynamic_script_cache_hits_total " + cacheHits.get() + "\n"
                + "# TYPE dynamic_script_cache_misses_total counter\n"
                + "dynamic_script_cache_misses_total " + cacheMisses.get() + "\n"
                + "# TYPE dynamic_script_rejected_total counter\n"
                + "dynamic_script_rejected_total " + rejectedScripts.get() + "\n"
                + "# TYPE dynamic_script_compilation_seconds_total counter\n"
                + "dynamic_script_compilation_seconds_total "
                + format(compilationNanos.sum() / 1_000_000_000.0) + "\n"
                + "# TYPE dynamic_script_execution_seconds_total counter\n"
                + "dynamic_script_execution_seconds_total "
                + format(executionNanos.sum() / 1_000_000_000.0) + "\n"
                + "# TYPE dynamic_script_cache_size gauge\n"
                + "dynamic_script_cache_size " + scripts.size() + "\n"
                + "# TYPE dynamic_script_host_class_access gauge\n"
                + "dynamic_script_host_class_access 0\n"
                + "# TYPE benchmark_requests_total counter\n"
                + "benchmark_requests_total{benchmark=\"" + benchmark + "\"} "
                + requests.get() + "\n";
        bytes(exchange, 200, "text/plain; version=0.0.4", body);
    }

    private static String stringField(String body, String name, String fallback) {
        String key = "\"" + name + "\"";
        int at = body.indexOf(key);
        int colon = at < 0 ? -1 : body.indexOf(':', at + key.length());
        int start = colon < 0 ? -1 : body.indexOf('"', colon + 1);
        int end = start < 0 ? -1 : body.indexOf('"', start + 1);
        return at < 0 || colon < 0 || start < 0 || end < 0
                ? fallback
                : body.substring(start + 1, end);
    }

    private static double doubleField(String body, String name, double fallback) {
        String key = "\"" + name + "\"";
        int at = body.indexOf(key);
        int colon = at < 0 ? -1 : body.indexOf(':', at + key.length());
        if (colon < 0) return fallback;
        int start = colon + 1;
        while (start < body.length() && Character.isWhitespace(body.charAt(start))) start++;
        int end = start;
        while (end < body.length()) {
            char character = body.charAt(end);
            if (!(Character.isDigit(character) || character == '-' || character == '+'
                    || character == '.' || character == 'e' || character == 'E')) break;
            end++;
        }
        try {
            return Double.parseDouble(body.substring(start, end));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.9f", value);
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\")
                .replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static void json(HttpExchange exchange, int status, String body)
            throws IOException {
        bytes(exchange, status, "application/json", body);
    }

    private static void bytes(HttpExchange exchange, int status, String type, String body)
            throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }

    record Evaluation(String result, boolean cacheHit, long compileNanos, long executionNanos) { }

    static final class SandboxedContextFactory extends ContextFactory {
        @Override
        protected Context makeContext() {
            Context context = super.makeContext();
            context.setOptimizationLevel(-1);
            context.setInstructionObserverThreshold(INSTRUCTION_BUDGET);
            context.setClassShutter(className -> false);
            return context;
        }

        @Override
        protected void observeInstructionCount(Context context, int instructionCount) {
            throw new EvaluatorException("instruction budget exceeded");
        }
    }
}
