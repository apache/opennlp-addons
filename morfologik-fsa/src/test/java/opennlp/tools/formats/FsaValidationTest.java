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

package opennlp.tools.formats;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Checks automaton structure, traversal depth and independent traversals. */
class FsaValidationTest {

  private enum Format {
    FSA5, CFSA2
  }

  /**
   * Node addresses may share the final arcs of an outgoing-arc list.
   *
   * @param format The format.
   * @throws IOException If loading fails.
   */
  @ParameterizedTest
  @EnumSource(Format.class)
  void testSharedArcTail(Format format) throws IOException {
    final byte[] data = format == Format.FSA5
        ? new byte[] {'\\', 'f', 's', 'a', 5, '_', '+', 1,
            0, 2, 0, 6, 'a', 0x40, 'b', 0x52, 'x', 1, 'y', 3}
        : new byte[] {'\\', 'f', 's', 'a', (byte) 0xc6, 0, 7, 0, (byte) 0xc0, '^',
            0, 'a', 8, 0x40, 'b', 11, 0x20, 'x', 0, 0x60, 'y', 0};
    assertEquals(List.of("ax", "ay", "by"), sequences(read(format, data)).stream()
        .map(bytes -> new String(bytes, StandardCharsets.UTF_8)).toList());
  }

  /**
   * An empty automaton has no accepted sequences.
   *
   * @param format The format.
   * @throws IOException If loading fails.
   */
  @ParameterizedTest
  @EnumSource(Format.class)
  void testEmptyAutomaton(Format format) throws IOException {
    final byte[] data = format == Format.FSA5
        ? new byte[] {'\\', 'f', 's', 'a', 5, '_', '+', 1, 0, 2, 0, 2}
        : new byte[] {'\\', 'f', 's', 'a', (byte) 0xc6, 0, 7, 0, 0x40, '^', 0};
    assertEquals(List.of(), sequences(read(format, data)));
  }

  /** {@return truncations of complete automata} */
  private static Stream<Arguments> truncations() {
    return Arrays.stream(Format.values()).flatMap(format -> {
      final int length = fixture(format, 8).length;
      return IntStream.range(0, length).mapToObj(cut -> Arguments.of(format, cut));
    });
  }

  /**
   * A truncated automaton is rejected before returning a reader.
   *
   * @param format The format.
   * @param length The retained byte count.
   */
  @ParameterizedTest
  @MethodSource("truncations")
  void testTruncatedData(Format format, int length) {
    final byte[] data = Arrays.copyOf(fixture(format, 8), length);
    assertThrows(IOException.class, () -> read(format, data));
    assertThrows(IOException.class, () -> FsaSequenceReader.read(new ByteArrayInputStream(data)));
  }

  /** {@return valid path lengths near and above 8192 bytes} */
  private static Stream<Arguments> depths() {
    return Arrays.stream(Format.values()).flatMap(format ->
        IntStream.of(8191, 8192, 8193, 16384, 32768)
            .mapToObj(length -> Arguments.of(format, length)));
  }

  /**
   * A valid path does not depend on the Java stack size.
   *
   * @param format The format.
   * @param length The sequence length.
   * @throws IOException If loading fails.
   */
  @ParameterizedTest
  @MethodSource("depths")
  void testLongPath(Format format, int length) throws IOException {
    final FsaSequenceReader reader = read(format, fixture(format, length));
    final List<byte[]> result = sequences(reader);
    assertEquals(1, result.size());
    final byte[] expected = new byte[length];
    Arrays.fill(expected, (byte) 'a');
    assertArrayEquals(expected, result.getFirst());
  }

  /**
   * A cycle is rejected during loading.
   *
   * @param format The format.
   */
  @ParameterizedTest
  @EnumSource(Format.class)
  void testCycle(Format format) {
    final byte[] data = fixture(format, 1);
    data[data.length - 1] = (byte) (format == Format.FSA5 ? (4 << 3) | 3 : 2);
    assertThrows(IOException.class, () -> read(format, data));
  }

  /**
   * Targets must identify a node within the automaton.
   *
   * @param format The format.
   */
  @ParameterizedTest
  @EnumSource(Format.class)
  void testTargetOutsideData(Format format) {
    final byte[] data = fixture(format, 1);
    data[data.length - 1] = (byte) (format == Format.FSA5 ? 0xfb : 0x7f);
    assertThrows(IOException.class, () -> read(format, data));
  }

  /**
   * An outgoing-arc list must have a final list entry.
   *
   * @param format The format.
   */
  @ParameterizedTest
  @EnumSource(Format.class)
  void testMissingLastArc(Format format) {
    final byte[] data = fixture(format, 1);
    if (format == Format.FSA5) {
      data[data.length - 1] = 1;
    } else {
      data[10] = 0x20;
    }
    assertThrows(IOException.class, () -> read(format, data));
  }

  /**
   * Zero-extended FSA5 addresses remain valid at any representable header width.
   *
   * @param width The address byte count.
   * @throws IOException If loading fails.
   */
  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4, 5, 8, 9, 15})
  void testWideZeroAddress(int width) throws IOException {
    assertArrayEquals(new byte[] {'a'}, sequences(read(Format.FSA5, wideFsa(width))).getFirst());
  }

  /**
   * High address bits cannot wrap into a small valid target.
   *
   * @param width The address byte count.
   */
  @ParameterizedTest
  @ValueSource(ints = {5, 8, 9, 15})
  void testWideAddressOverflow(int width) {
    final byte[] data = wideFsa(width);
    data[data.length - 1] = 1;
    assertThrows(IOException.class, () -> read(Format.FSA5, data));
  }

  /** {@return unterminated and overflowing variable-length addresses} */
  private static Stream<byte[]> invalidAddresses() {
    return Stream.of(new byte[] {(byte) 0x80},
        new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x10},
        new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0});
  }

  /**
   * Unterminated and overflowing CFSA2 addresses are input errors.
   *
   * @param address The encoded address.
   */
  @ParameterizedTest
  @MethodSource("invalidAddresses")
  void testInvalidVariableAddress(byte[] address) {
    final byte[] valid = fixture(Format.CFSA2, 1);
    final byte[] data = Arrays.copyOf(valid, valid.length - 1 + address.length);
    System.arraycopy(address, 0, data, valid.length - 1, address.length);
    assertThrows(IOException.class, () -> read(Format.CFSA2, data));
  }

  /** An indexed label must have a table entry. */
  @Test
  void testMissingLabelEntry() {
    final byte[] data = fixture(Format.CFSA2, 1);
    data[10] = 0x61;
    assertThrows(IOException.class, () -> read(Format.CFSA2, data));
  }

  /** Unrecognized format flags are rejected. */
  @Test
  void testUnknownFlags() {
    final byte[] data = fixture(Format.CFSA2, 1);
    data[5] = 0x40;
    assertThrows(IOException.class, () -> read(Format.CFSA2, data));
  }

  /**
   * Result arrays and callback failures do not change subsequent traversals.
   *
   * @param format The format.
   * @throws IOException If loading fails.
   */
  @ParameterizedTest
  @EnumSource(Format.class)
  void testIndependentResults(Format format) throws IOException {
    final FsaSequenceReader reader = read(format, fixture(format, 3));
    reader.forEachSequence(bytes -> Arrays.fill(bytes, (byte) 'z'));
    assertThrows(IllegalStateException.class, () -> reader.forEachSequence(bytes -> {
      throw new IllegalStateException("test callback");
    }));
    assertArrayEquals("aaa".getBytes(StandardCharsets.UTF_8), sequences(reader).getFirst());
  }

  /**
   * Concurrent traversals use independent path buffers.
   *
   * @param format The format.
   * @throws Exception If loading or a task fails.
   */
  @ParameterizedTest
  @EnumSource(Format.class)
  void testConcurrentTraversal(Format format) throws Exception {
    final FsaSequenceReader reader = read(format, fixture(format, 300));
    try (var executor = Executors.newFixedThreadPool(4)) {
      final var tasks = IntStream.range(0, 32)
          .mapToObj(i -> executor.submit(() -> sequences(reader).getFirst())).toList();
      final byte[] expected = new byte[300];
      Arrays.fill(expected, (byte) 'a');
      for (final var task : tasks) {
        assertArrayEquals(expected, task.get());
      }
    }
  }

  /**
   * Builds original single-sequence test data.
   *
   * @param format The format.
   * @param length The accepted sequence length.
   * @return The encoded automaton.
   */
  private static byte[] fixture(Format format, int length) {
    final byte[] labels = new byte[length];
    Arrays.fill(labels, (byte) 'a');
    return format == Format.FSA5
        ? FsaTestData.singleSequence(labels) : FsaTestData.singleCfsaSequence(labels);
  }

  /**
   * Builds an FSA5 automaton with a zero-extended terminal address.
   *
   * @param width The address byte count.
   * @return The encoded automaton.
   */
  private byte[] wideFsa(int width) {
    final byte[] data = new byte[12 + width * 2];
    final byte[] header = {'\\', 'f', 's', 'a', 5, '_', '+', (byte) width};
    System.arraycopy(header, 0, data, 0, header.length);
    data[9] = 2;
    data[10 + width] = 6;
    data[11 + width] = 'a';
    data[12 + width] = 3;
    return data;
  }

  /**
   * Loads the format-specific reader.
   *
   * @param format The format.
   * @param data The encoded automaton.
   * @return The reader.
   * @throws IOException If loading fails.
   */
  private FsaSequenceReader read(Format format, byte[] data) throws IOException {
    final ByteArrayInputStream in = new ByteArrayInputStream(data);
    return format == Format.FSA5 ? FSA5Reader.read(in) : CFSA2Reader.read(in);
  }

  /**
   * Collects accepted byte sequences.
   *
   * @param reader The reader.
   * @return The accepted sequences.
   */
  private List<byte[]> sequences(FsaSequenceReader reader) {
    final List<byte[]> result = new ArrayList<>();
    reader.forEachSequence(result::add);
    return result;
  }
}
