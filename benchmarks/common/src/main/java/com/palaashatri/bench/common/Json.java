package com.palaashatri.bench.common;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Dependency-free JSON for bounded workload control messages. */
public final class Json {
    public static final int MAX_BYTES = 65_536;
    private Json() { }

    public static Map<String, Object> read(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("request_too_large");
        return parseObject(new String(bytes, StandardCharsets.UTF_8));
    }

    public static Map<String, Object> parseObject(String text) {
        Parser parser = new Parser(text);
        Object result = parser.value(0);
        parser.space();
        if (parser.at != text.length() || !(result instanceof Map)) throw new IllegalArgumentException("invalid_json");
        @SuppressWarnings("unchecked") Map<String, Object> object = (Map<String, Object>) result;
        return object;
    }

    public static String string(Map<String, Object> object, String key, String fallback) {
        return object.get(key) instanceof String value ? value : fallback;
    }

    public static long number(Map<String, Object> object, String key, long fallback) {
        return object.get(key) instanceof Long value ? value : fallback;
    }

    public static void reply(HttpExchange exchange, int status, Object value) throws IOException {
        bytes(exchange, status, "application/json", stringify(value));
    }

    public static void bytes(HttpExchange exchange, int status, String type, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    public static String stringify(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) {
            StringBuilder out = new StringBuilder("\"");
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                switch (ch) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (ch < 32 || Character.isSurrogate(ch)) out.append(String.format("\\u%04x", (int) ch));
                        else out.append(ch);
                    }
                }
            }
            return out.append('"').toString();
        }
        if (value instanceof Boolean || value instanceof Number) {
            if (value instanceof Double d && !Double.isFinite(d)) throw new IllegalArgumentException("non_finite_number");
            return value.toString();
        }
        if (value instanceof Map<?, ?> map) {
            var values = new ArrayList<String>();
            map.forEach((key, item) -> values.add(stringify(key.toString()) + ":" + stringify(item)));
            return "{" + String.join(",", values) + "}";
        }
        if (value instanceof Collection<?> list) return "[" + String.join(",", list.stream().map(Json::stringify).toList()) + "]";
        throw new IllegalArgumentException("unsupported_json_value");
    }

    private static final class Parser {
        final String text;
        int at;
        Parser(String text) { this.text = text; }
        void space() { while (at < text.length() && " \n\r\t".indexOf(text.charAt(at)) >= 0) at++; }
        boolean take(char ch) { space(); if (at < text.length() && text.charAt(at) == ch) { at++; return true; } return false; }
        void need(char ch) { if (!take(ch)) throw new IllegalArgumentException("invalid_json"); }
        Object value(int depth) {
            if (depth > 32) throw new IllegalArgumentException("json_too_deep");
            space();
            if (at == text.length()) throw new IllegalArgumentException("invalid_json");
            char ch = text.charAt(at);
            if (ch == '"') return string();
            if (take('{')) {
                Map<String, Object> result = new LinkedHashMap<>();
                if (take('}')) return result;
                do {
                    space();
                    String key = string();
                    need(':');
                    if (result.containsKey(key)) throw new IllegalArgumentException("duplicate_json_key");
                    result.put(key, value(depth + 1));
                } while (take(','));
                need('}'); return result;
            }
            if (take('[')) {
                var result = new ArrayList<Object>();
                if (take(']')) return result;
                do { result.add(value(depth + 1)); } while (take(','));
                need(']'); return result;
            }
            for (String literal : new String[]{"true", "false", "null"}) {
                if (text.startsWith(literal, at)) {
                    at += literal.length();
                    return literal.equals("null") ? null : Boolean.valueOf(literal);
                }
            }
            int start = at;
            if (text.charAt(at) == '-') at++;
            if (at >= text.length() || !digit(text.charAt(at))) throw new IllegalArgumentException("invalid_json");
            if (text.charAt(at++) != '0') while (at < text.length() && digit(text.charAt(at))) at++;
            boolean decimal = false;
            if (at < text.length() && text.charAt(at) == '.') { decimal = true; at++; digits(); }
            if (at < text.length() && "eE".indexOf(text.charAt(at)) >= 0) {
                decimal = true; at++;
                if (at < text.length() && "+-".indexOf(text.charAt(at)) >= 0) at++;
                digits();
            }
            try {
                String number = text.substring(start, at);
                if (!decimal) return Long.valueOf(number);
                double value = Double.parseDouble(number);
                if (!Double.isFinite(value)) throw new IllegalArgumentException("non_finite_number");
                return value;
            } catch (NumberFormatException invalid) { throw new IllegalArgumentException("invalid_json", invalid); }
        }
        boolean digit(char ch) { return ch >= '0' && ch <= '9'; }
        void digits() {
            int start = at;
            while (at < text.length() && digit(text.charAt(at))) at++;
            if (start == at) throw new IllegalArgumentException("invalid_json");
        }
        String string() {
            if (at >= text.length() || text.charAt(at++) != '"') throw new IllegalArgumentException("invalid_json");
            StringBuilder out = new StringBuilder();
            while (at < text.length()) {
                char ch = text.charAt(at++);
                if (ch == '"') return out.toString();
                if (ch < 32) throw new IllegalArgumentException("invalid_json");
                if (ch != '\\') { out.append(ch); continue; }
                if (at >= text.length()) throw new IllegalArgumentException("invalid_json");
                char escape = text.charAt(at++);
                switch (escape) {
                    case '"', '\\', '/' -> out.append(escape);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (at + 4 > text.length()) throw new IllegalArgumentException("invalid_json");
                        int code = 0;
                        for (int end = at + 4; at < end; at++) {
                            char hex = text.charAt(at);
                            int digit = hex >= '0' && hex <= '9' ? hex - '0'
                                    : hex >= 'a' && hex <= 'f' ? hex - 'a' + 10
                                    : hex >= 'A' && hex <= 'F' ? hex - 'A' + 10 : -1;
                            if (digit < 0) throw new IllegalArgumentException("invalid_json");
                            code = code * 16 + digit;
                        }
                        out.append((char) code);
                    }
                    default -> throw new IllegalArgumentException("invalid_json");
                }
            }
            throw new IllegalArgumentException("invalid_json");
        }
    }
}
