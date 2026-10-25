package com.palaashatri.bench.b09.app;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Writes the classifier as an ONNX graph: MatMul → Relu → MatMul → Softmax, with the
 * same seeded weights as the Java reference engine. The encoding is the protobuf wire
 * format with the field numbers from onnx.proto, so no ONNX library is needed to build
 * the model; ONNX Runtime validates it when the session is created.
 */
final class OnnxModelBuilder {
    static final String INPUT = "input";
    static final String OUTPUT = "probabilities";

    private static final long IR_VERSION = 8;
    private static final long OPSET = 13;
    private static final long FLOAT = 1;
    private static final long INT_ATTRIBUTE = 2;

    private OnnxModelBuilder() { }

    static byte[] build(ClassifierWeights weights) {
        long inputs = ClassifierWeights.INPUTS, hidden = ClassifierWeights.HIDDEN, classes = ClassifierWeights.CLASSES;
        // MatMul takes row-major inputs on the left, so the stored weights are transposed.
        byte[] hiddenWeights = tensor("hidden_weights", new long[]{inputs, hidden}, transpose(weights.hidden()));
        byte[] outputWeights = tensor("output_weights", new long[]{hidden, classes}, transpose(weights.output()));

        Proto graph = new Proto().string(2, "classifier");
        graph.bytes(1, node("matmul_hidden", "MatMul", "pre_activation", INPUT, "hidden_weights"));
        graph.bytes(1, node("relu", "Relu", "hidden", "pre_activation"));
        graph.bytes(1, node("matmul_output", "MatMul", "logits", "hidden", "output_weights"));
        graph.bytes(1, softmax("softmax", "logits", OUTPUT));
        graph.bytes(5, hiddenWeights);
        graph.bytes(5, outputWeights);
        graph.bytes(11, valueInfo(INPUT, 1, inputs));
        graph.bytes(12, valueInfo(OUTPUT, 1, classes));

        Proto opset = new Proto().string(1, "").varint(2, OPSET);
        return new Proto()
                .varint(1, IR_VERSION)
                .string(2, "palaashatri-benchmarks")
                .bytes(7, graph.toByteArray())
                .bytes(8, opset.toByteArray())
                .toByteArray();
    }

    private static float[][] transpose(float[][] matrix) {
        float[][] result = new float[matrix[0].length][matrix.length];
        for (int row = 0; row < matrix.length; row++) {
            for (int column = 0; column < matrix[row].length; column++) {
                result[column][row] = matrix[row][column];
            }
        }
        return result;
    }

    private static byte[] tensor(String name, long[] dims, float[][] values) {
        int count = Math.toIntExact(dims[0] * dims[1]);
        ByteBuffer raw = ByteBuffer.allocate(count * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float[] row : values) for (float value : row) raw.putFloat(value);
        Proto tensor = new Proto();
        for (long dim : dims) tensor.varint(1, dim);
        return tensor.varint(2, FLOAT).string(8, name).bytes(9, raw.array()).toByteArray();
    }

    private static byte[] valueInfo(String name, long... dims) {
        Proto shape = new Proto();
        for (long dim : dims) shape.message(1, new Proto().varint(1, dim));
        Proto tensorType = new Proto().varint(1, FLOAT).message(2, shape);
        Proto type = new Proto().message(1, tensorType);
        return new Proto().string(1, name).message(2, type).toByteArray();
    }

    private static byte[] node(String name, String operator, String output, String... inputs) {
        Proto node = new Proto();
        for (String input : inputs) node.string(1, input);
        return node.string(2, output).string(3, name).string(4, operator).toByteArray();
    }

    private static byte[] softmax(String name, String input, String output) {
        Proto axis = new Proto().string(1, "axis").varint(3, 1).varint(20, INT_ATTRIBUTE);
        Proto node = new Proto().string(1, input).string(2, output).string(3, name).string(4, "Softmax");
        return node.message(5, axis).toByteArray();
    }

    /** Minimal protobuf encoder: varints and length-delimited fields. */
    private static final class Proto {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Proto varint(int field, long value) {
            key(field, 0);
            writeVarint(value);
            return this;
        }

        Proto string(int field, String value) {
            return bytes(field, value.getBytes(StandardCharsets.UTF_8));
        }

        Proto bytes(int field, byte[] value) {
            key(field, 2);
            writeVarint(value.length);
            out.writeBytes(value);
            return this;
        }

        Proto message(int field, Proto nested) {
            return bytes(field, nested.toByteArray());
        }

        byte[] toByteArray() {
            return out.toByteArray();
        }

        private void key(int field, int wireType) {
            writeVarint(((long) field << 3) | wireType);
        }

        private void writeVarint(long value) {
            long remaining = value;
            while ((remaining & ~0x7FL) != 0) {
                out.write((int) ((remaining & 0x7F) | 0x80));
                remaining >>>= 7;
            }
            out.write((int) remaining);
        }
    }
}
