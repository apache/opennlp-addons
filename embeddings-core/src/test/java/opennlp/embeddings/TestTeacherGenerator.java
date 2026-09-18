/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package opennlp.embeddings;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Regenerates the two deterministic ONNX teachers without native libraries or trained weights.
 * Field numbers follow the
 * <a href="https://github.com/onnx/onnx/blob/v1.19.0/onnx/onnx.proto">ONNX schema</a>.
 * This writes only the messages needed by these fixtures, not arbitrary ONNX models.
 */
public final class TestTeacherGenerator {

  private static final int FLOAT = 1;
  private static final int INT64 = 7;

  private TestTeacherGenerator() {
  }

  /** Prints Java base64 constants for the two graphs. */
  public static void main(String[] args) {
    print("LOOKUP_TEACHER_ONNX", lookup());
    print("VARIABLE_DIMENSION_ONNX", variableDimension());
  }

  /** Creates the original eight-token, four-dimensional lookup table. */
  static byte[] lookup() {
    final float[] table = {
        0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 1, 0, 0, 0, -1,
        3, 0, 0, 0, 2, 1, 0, 0,
        -1, 2, 0, 0, -1, -2, 0, 0
    };
    final ByteBuffer values = ByteBuffer.allocate(table.length * Float.BYTES)
        .order(ByteOrder.LITTLE_ENDIAN);
    for (float value : table) {
      values.putFloat(value);
    }
    final byte[] tensor = join(number(1, 8), number(1, 4), number(2, FLOAT),
        bytes(4, values.array()), text(8, "table"));
    final byte[] gather = node("Gather", new String[]{"table", "input_ids"},
        "last_hidden_state", attribute("axis", 0));
    return model("LOOKUP_TEACHER_ONNX", new byte[][]{gather}, new byte[][]{tensor}, number(1, 4));
  }

  /** Creates a graph whose hidden dimension is the current batch size. */
  static byte[] variableDimension() {
    final byte[][] nodes = {
        node("Cast", new String[]{"input_ids"}, "as_float", attribute("to", FLOAT)),
        node("Unsqueeze", new String[]{"as_float", "axes"}, "states"),
        node("Shape", new String[]{"input_ids"}, "input_shape"),
        node("Gather", new String[]{"input_shape", "batch_axis"}, "batch_size", attribute("axis", 0)),
        node("Concat", new String[]{"ones", "batch_size"}, "repeats", attribute("axis", 0)),
        node("Tile", new String[]{"states", "repeats"}, "last_hidden_state")
    };
    return model("VARIABLE_DIMENSION_ONNX", nodes,
        new byte[][]{integerTensor("axes", 2), integerTensor("batch_axis", 0),
            integerTensor("ones", 1, 1)}, text(2, "hidden"));
  }

  /** Wraps nodes and initializers in a graph with IR version 8 and opset 13. */
  private static byte[] model(String name, byte[][] nodes, byte[][] tensors, byte[] dimension) {
    final var graph = new ByteArrayOutputStream();
    for (byte[] node : nodes) {
      graph.writeBytes(bytes(1, node));
    }
    graph.writeBytes(text(2, name));
    for (byte[] tensor : tensors) {
      graph.writeBytes(bytes(5, tensor));
    }
    graph.writeBytes(bytes(11, value("input_ids", INT64)));
    graph.writeBytes(bytes(12, value("last_hidden_state", FLOAT, dimension)));
    return join(number(1, 8), bytes(7, graph.toByteArray()),
        bytes(8, join(text(1, ""), number(2, 13))));
  }

  /** Creates a tensor value with symbolic batch and token dimensions. */
  private static byte[] value(String name, int type, byte[]... extraDimensions) {
    final var shape = new ByteArrayOutputStream();
    shape.writeBytes(bytes(1, text(2, "batch")));
    shape.writeBytes(bytes(1, text(2, "tokens")));
    for (byte[] dimension : extraDimensions) {
      shape.writeBytes(bytes(1, dimension));
    }
    return join(text(1, name), bytes(2, bytes(1,
        join(number(1, type), bytes(2, shape.toByteArray())))));
  }

  /** Creates an operator with one output and optional integer attributes. */
  private static byte[] node(String operation, String[] inputs, String output, byte[]... attributes) {
    final var node = new ByteArrayOutputStream();
    for (String input : inputs) {
      node.writeBytes(text(1, input));
    }
    node.writeBytes(text(2, output));
    node.writeBytes(text(4, operation));
    for (byte[] attribute : attributes) {
      node.writeBytes(bytes(5, attribute));
    }
    return node.toByteArray();
  }

  /** Encodes a named INT attribute. */
  private static byte[] attribute(String name, long value) {
    return join(text(1, name), number(3, value), number(20, 2));
  }

  /** Encodes a one-dimensional INT64 initializer with packed values. */
  private static byte[] integerTensor(String name, long... values) {
    final var packed = new ByteArrayOutputStream();
    for (long value : values) {
      packed.writeBytes(varint(value));
    }
    return join(number(1, values.length), number(2, INT64), bytes(7, packed.toByteArray()), text(8, name));
  }

  /** Encodes a UTF-8 string field. */
  private static byte[] text(int field, String value) {
    return bytes(field, value.getBytes(StandardCharsets.UTF_8));
  }

  /** Encodes a length-delimited field. */
  private static byte[] bytes(int field, byte[] value) {
    return join(varint((long) field << 3 | 2), varint(value.length), value);
  }

  /** Encodes an integer field. */
  private static byte[] number(int field, long value) {
    return join(varint((long) field << 3), varint(value));
  }

  /** Encodes an unsigned protobuf varint. */
  private static byte[] varint(long value) {
    final var output = new ByteArrayOutputStream();
    while ((value & ~0x7fL) != 0) {
      output.write((int) (value & 0x7f) | 0x80);
      value >>>= 7;
    }
    output.write((int) value);
    return output.toByteArray();
  }

  /** Joins encoded fields in their schema order. */
  private static byte[] join(byte[]... parts) {
    final var output = new ByteArrayOutputStream();
    for (byte[] part : parts) {
      output.writeBytes(part);
    }
    return output.toByteArray();
  }

  /** Prints one constant in 76-character chunks, matching the original generator. */
  private static void print(String name, byte[] graph) {
    final String encoded = Base64.getEncoder().encodeToString(graph);
    System.out.println("  private static final String " + name + " =");
    for (int offset = 0; offset < encoded.length(); offset += 76) {
      final int end = Math.min(offset + 76, encoded.length());
      System.out.println((offset == 0 ? "      " : "          + ") + '"'
          + encoded.substring(offset, end) + '"' + (end == encoded.length() ? ";" : ""));
    }
  }
}
