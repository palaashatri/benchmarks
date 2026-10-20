package com.palaashatri.bench.common;

/** Malformed wire escapes must never become valid control messages. */
public final class JsonTest {
    public static void main(String[] args) {
        for (String text : new String[]{"{\"x\":\"\\u+123\"}", "{\"x\":\"\\u-123\"}"}) {
            try { Json.parseObject(text); throw new AssertionError("accepted malformed Unicode escape: " + text); }
            catch (IllegalArgumentException expected) { }
        }
        System.out.println("malformed Unicode escapes rejected");
    }
}
