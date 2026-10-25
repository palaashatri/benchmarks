package com.palaashatri.bench.ctl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader and writer for the controller. Objects become {@link LinkedHashMap}, arrays become
 * {@link List}, integers become {@link Long} and other numbers become {@link Double}. Non-finite numbers are
 * written as null: an unknown value is never written as a fake number.
 */
public final class Json {
    private final String text;
    private int position;

    private Json(String text) {
        this.text = text;
    }

    /** Builds a map from alternating keys and values, keeping insertion order. */
    public static Map<String, Object> map(Object... keysAndValues) {
        if (keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("map needs alternating keys and values");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            map.put((String) keysAndValues[index], keysAndValues[index + 1]);
        }
        return map;
    }

    public static Object parse(String text) {
        Json parser = new Json(text);
        parser.skipWhitespace();
        Object value = parser.value();
        parser.skipWhitespace();
        if (parser.position != text.length()) {
            throw parser.error("trailing characters");
        }
        return value;
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(out, value, -1, 0);
        return out.toString();
    }

    /** Two-space indented JSON with a trailing newline, as result files are written. */
    public static String pretty(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(out, value, 2, 0);
        return out.append('\n').toString();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    public static List<Object> asList(Object value) {
        return value instanceof List ? (List<Object>) value : List.of();
    }

    /** Number value as a double, or null when the field is absent or not numeric. */
    public static Double number(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : null;
    }

    private Object value() {
        if (position >= text.length()) {
            throw error("unexpected end of input");
        }
        char next = text.charAt(position);
        return switch (next) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<>();
        position++;
        skipWhitespace();
        if (peek() == '}') {
            position++;
            return map;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw error("expected object key");
            }
            String key = string();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            map.put(key, value());
            skipWhitespace();
            char next = peek();
            position++;
            if (next == '}') {
                return map;
            }
            if (next != ',') {
                throw error("expected ',' or '}'");
            }
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        position++;
        skipWhitespace();
        if (peek() == ']') {
            position++;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(value());
            skipWhitespace();
            char next = peek();
            position++;
            if (next == ']') {
                return list;
            }
            if (next != ',') {
                throw error("expected ',' or ']'");
            }
        }
    }

    private String string() {
        position++;
        StringBuilder out = new StringBuilder();
        while (true) {
            if (position >= text.length()) {
                throw error("unterminated string");
            }
            char current = text.charAt(position++);
            if (current == '"') {
                return out.toString();
            }
            if (current != '\\') {
                out.append(current);
                continue;
            }
            char escape = text.charAt(position++);
            switch (escape) {
                case '"', '\\', '/' -> out.append(escape);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    out.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                    position += 4;
                }
                default -> throw error("invalid escape");
            }
        }
    }

    private Object number() {
        int start = position;
        if (peek() == '-') {
            position++;
        }
        boolean fractional = false;
        while (position < text.length()) {
            char current = text.charAt(position);
            if (Character.isDigit(current)) {
                position++;
            } else if (current == '.' || current == 'e' || current == 'E' || current == '+' || current == '-') {
                fractional = true;
                position++;
            } else {
                break;
            }
        }
        String token = text.substring(start, position);
        if (token.isEmpty() || "-".equals(token)) {
            throw error("expected a value");
        }
        try {
            if (!fractional) {
                return Long.parseLong(token);
            }
            return Double.parseDouble(token);
        } catch (NumberFormatException exception) {
            throw error("invalid number " + token);
        }
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, position)) {
            throw error("expected " + word);
        }
        position += word.length();
        return value;
    }

    private void expect(char expected) {
        if (peek() != expected) {
            throw error("expected '" + expected + "'");
        }
        position++;
    }

    private char peek() {
        if (position >= text.length()) {
            throw error("unexpected end of input");
        }
        return text.charAt(position);
    }

    private void skipWhitespace() {
        while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
            position++;
        }
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException("invalid JSON at character " + position + ": " + message);
    }

    private static void writeValue(StringBuilder out, Object value, int indent, int depth) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String string) {
            writeString(out, string);
        } else if (value instanceof Boolean flag) {
            out.append(flag);
        } else if (value instanceof Double || value instanceof Float) {
            double number = ((Number) value).doubleValue();
            if (Double.isFinite(number)) {
                out.append(number);
            } else {
                out.append("null");
            }
        } else if (value instanceof Number number) {
            out.append(number.longValue());
        } else if (value instanceof Map<?, ?> map) {
            writeObject(out, map, indent, depth);
        } else if (value instanceof Iterable<?> items) {
            writeArray(out, items, indent, depth);
        } else {
            writeString(out, value.toString());
        }
    }

    private static void writeObject(StringBuilder out, Map<?, ?> map, int indent, int depth) {
        if (map.isEmpty()) {
            out.append("{}");
            return;
        }
        out.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            newline(out, indent, depth + 1);
            writeString(out, String.valueOf(entry.getKey()));
            out.append(indent < 0 ? ":" : ": ");
            writeValue(out, entry.getValue(), indent, depth + 1);
        }
        newline(out, indent, depth);
        out.append('}');
    }

    private static void writeArray(StringBuilder out, Iterable<?> items, int indent, int depth) {
        boolean empty = true;
        out.append('[');
        for (Object item : items) {
            if (!empty) {
                out.append(',');
            }
            empty = false;
            newline(out, indent, depth + 1);
            writeValue(out, item, indent, depth + 1);
        }
        if (!empty) {
            newline(out, indent, depth);
        }
        out.append(']');
    }

    private static void newline(StringBuilder out, int indent, int depth) {
        if (indent < 0) {
            return;
        }
        out.append('\n');
        out.append(" ".repeat(indent * depth));
    }

    private static void writeString(StringBuilder out, String value) {
        out.append('"');
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (current < 0x20) {
                        out.append(String.format("\\u%04x", (int) current));
                    } else {
                        out.append(current);
                    }
                }
            }
        }
        out.append('"');
    }
}
