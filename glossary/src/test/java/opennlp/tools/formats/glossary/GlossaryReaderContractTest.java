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

package opennlp.tools.formats.glossary;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import opennlp.tools.glossary.GlossaryEntry;
import opennlp.tools.util.InvalidFormatException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Checks stream ownership and I/O errors for glossary readers. */
public class GlossaryReaderContractTest {

  /**
   * Supplies each reader with valid and malformed input.
   *
   * @return Readers and input text.
   */
  private static Stream<Arguments> readers() {
    return Stream.of(
        Arguments.of(new CsvGlossaryReader(), "Q1,term", "Q1,\"term"),
        Arguments.of(new TbxGlossaryReader("en"),
            "<martif><text><body><termEntry id=\"Q1\"><langSet xml:lang=\"en\">"
                + "<tig><term>term</term></tig></langSet></termEntry></body></text></martif>",
            "<martif><text>"));
  }

  /**
   * Does not close the input after a successful read.
   *
   * @param reader The configured reader.
   * @param valid The valid input.
   * @param invalid The malformed input.
   * @throws IOException If reading fails.
   */
  @ParameterizedTest
  @MethodSource("readers")
  void testSuccessfulReadRetainsStreamOwnership(GlossaryReader reader, String valid,
      String invalid) throws IOException {
    final TrackingInputStream input = new TrackingInputStream(valid);
    assertEquals(List.of(new GlossaryEntry("Q1", "term")), reader.read(input));
    assertFalse(input.closed, "reader closed the supplied input");
  }

  /**
   * Does not close the input after a parsing error.
   *
   * @param reader The configured reader.
   * @param valid The valid input.
   * @param invalid The malformed input.
   */
  @ParameterizedTest
  @MethodSource("readers")
  void testInvalidReadRetainsStreamOwnership(GlossaryReader reader, String valid,
      String invalid) {
    final TrackingInputStream input = new TrackingInputStream(invalid);
    assertThrows(InvalidFormatException.class, () -> reader.read(input));
    assertFalse(input.closed, "reader closed the supplied input");
  }

  /**
   * Preserves an I/O failure instead of classifying it as malformed content.
   *
   * @param reader The configured reader.
   * @param valid The valid input.
   * @param invalid The malformed input.
   */
  @ParameterizedTest
  @MethodSource("readers")
  void testReadFailureRemainsIOException(GlossaryReader reader, String valid, String invalid) {
    final IOException failure = new IOException("test read failure");
    final InputStream input = new InputStream() {
      /** {@inheritDoc} */
      @Override
      public int read() throws IOException {
        throw failure;
      }
    };
    assertSame(failure, assertThrows(IOException.class, () -> reader.read(input)));
  }

  /**
   * Preserves an I/O failure after some content has been consumed.
   *
   * @param reader The configured reader.
   * @param valid The valid input.
   * @param invalid The malformed input.
   */
  @ParameterizedTest
  @MethodSource("readers")
  void testLaterReadFailureRemainsIOException(GlossaryReader reader, String valid,
      String invalid) {
    final IOException failure = new IOException("test later read failure");
    final ByteArrayInputStream prefix = new ByteArrayInputStream(
        valid.getBytes(StandardCharsets.UTF_8));
    final InputStream input = new InputStream() {
      /** {@inheritDoc} */
      @Override
      public int read() throws IOException {
        final int value = prefix.read();
        if (value < 0) {
          throw failure;
        }
        return value;
      }
    };
    assertSame(failure, assertThrows(IOException.class, () -> reader.read(input)));
  }

  /**
   * Reads separate streams concurrently with one configured reader.
   *
   * @param reader The configured reader.
   * @param valid The valid input.
   * @param invalid The malformed input.
   * @throws Exception If a worker fails or exceeds the timeout.
   */
  @ParameterizedTest
  @MethodSource("readers")
  void testConcurrentReads(GlossaryReader reader, String valid, String invalid) throws Exception {
    try (var workers = Executors.newFixedThreadPool(4)) {
      final List<Future<List<GlossaryEntry>>> results = new ArrayList<>();
      for (int i = 0; i < 16; i++) {
        results.add(workers.submit(() -> reader.read(new ByteArrayInputStream(
            valid.getBytes(StandardCharsets.UTF_8)))));
      }
      for (Future<List<GlossaryEntry>> result : results) {
        assertEquals(List.of(new GlossaryEntry("Q1", "term")),
            result.get(10, TimeUnit.SECONDS));
      }
    }
  }

  /** Records whether a reader closes the supplied stream. */
  private static final class TrackingInputStream extends ByteArrayInputStream {

    private boolean closed;

    /**
     * Creates an input with a close flag.
     *
     * @param text The UTF-8 source text.
     */
    private TrackingInputStream(String text) {
      super(text.getBytes(StandardCharsets.UTF_8));
    }

    /** {@inheritDoc} */
    @Override
    public void close() throws IOException {
      closed = true;
      super.close();
    }
  }
}
