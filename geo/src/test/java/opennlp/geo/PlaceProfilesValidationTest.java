/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
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
package opennlp.geo;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.util.InvalidFormatException;
import opennlp.tools.util.WhitespaceMode;

import static opennlp.geo.PlaceProfilesTestSupport.load;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Tests malformed tables, text decoding, and input-stream ownership. */
class PlaceProfilesValidationTest {

  /**
   * Rejects null and empty result identifiers.
   *
   * @param id The invalid result identifier.
   */
  @ParameterizedTest
  @NullAndEmptySource
  void testNeighborIdentifier(String id) {
    assertThrows(IllegalArgumentException.class, () -> new PlaceProfiles.Neighbor(id, 0.0));
  }

  /**
   * A loaded identifier remains usable after the active whitespace mode changes.
   *
   * @param mode The table whitespace mode.
   * @throws IOException Thrown if the table cannot be loaded.
   */
  @ParameterizedTest
  @EnumSource(WhitespaceMode.class)
  @ResourceLock(WhitespaceMode.MODE_PROPERTY)
  void testNeighborWhitespaceMode(WhitespaceMode mode) throws IOException {
    final WhitespaceMode previous = WhitespaceMode.current();
    try {
      WhitespaceMode.setActive(mode);
      final String id = mode == WhitespaceMode.LEGACY ? "\u0085" : "\u001C";
      final PlaceProfiles profiles = load("id\tv\n" + id + "\t1\nordinary\t2\n");
      assertEquals(1.0, profiles.similarity(id, id));
      WhitespaceMode.setActive(mode == WhitespaceMode.LEGACY
          ? WhitespaceMode.UNICODE : WhitespaceMode.LEGACY);
      assertEquals(id, profiles.mostSimilar("ordinary", 1).getFirst().id());
    } finally {
      WhitespaceMode.setActive(previous);
    }
  }

  /**
   * Rejects non-finite scores and values outside the cosine range.
   *
   * @param score The invalid score.
   */
  @ParameterizedTest
  @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.01, 1.01})
  void testNeighborScore(double score) {
    assertThrows(IllegalArgumentException.class, () -> new PlaceProfiles.Neighbor("a", score));
  }

  /**
   * Accepts finite scores, including both endpoints of the cosine range.
   *
   * @param score The valid score.
   */
  @ParameterizedTest
  @ValueSource(doubles = {-1.0, -0.5, 0.0, 0.5, 1.0})
  void testValidNeighborScore(double score) {
    assertEquals(score, new PlaceProfiles.Neighbor("a", score).similarity());
  }

  /**
   * Rejects repeated identifiers after surrounding whitespace is removed.
   *
   * @param duplicate The repeated identifier.
   */
  @ParameterizedTest
  @ValueSource(strings = {"a", " a ", "\u00A0a\u00A0"})
  void testDuplicatePlaceIds(String duplicate) {
    final InvalidFormatException error = assertThrows(InvalidFormatException.class,
        () -> load("id\tvalue\na\t1\n" + duplicate + "\t2\n"));
    assertEquals("duplicate id in row 3: a", error.getMessage());
  }

  /**
   * Rejects repeated metric names after surrounding whitespace is removed.
   *
   * @param duplicate The repeated metric name.
   */
  @ParameterizedTest
  @ValueSource(strings = {"value", " value ", "\u00A0value\u00A0"})
  void testDuplicateMetricNames(String duplicate) {
    final InvalidFormatException error = assertThrows(InvalidFormatException.class,
        () -> load("id\tvalue\t" + duplicate + "\na\t1\t2\n"));
    assertEquals("duplicate metric name in header column 3: value", error.getMessage());
  }

  /**
   * Identifies an empty metric cell by physical line, column, and metric name.
   *
   * @param cell The empty or whitespace-only cell.
   */
  @ParameterizedTest
  @ValueSource(strings = {"", " ", "\u00A0"})
  void testEmptyMetricCells(String cell) {
    final InvalidFormatException error = assertThrows(InvalidFormatException.class,
        () -> load("# measurements\n\nid\tdensity\tincome\na\t1\t" + cell + "\n"));
    assertEquals("empty value in row 4, column 3 (income)", error.getMessage());
  }

  /**
   * Uses the toolkit's format exception for invalid table contents.
   *
   * @param table The malformed table.
   */
  @ParameterizedTest
  @ValueSource(strings = {"", "# note\n", "id\n", "name\tv\na\t1\n", "id\t \na\t1\n",
      "id\tv\n", "id\tv\na\t1\t2\n", "id\tv\n \t1\n", "id\tv\na\tNaN\n",
      "id\tv\na\t0x1p0\n", "id\tv\na\t2f\n", "id\tv\na\tunknown\n"})
  void testMalformedContentUsesFormatException(String table) {
    assertThrows(InvalidFormatException.class, () -> load(table));
  }

  /**
   * Rejects invalid UTF-8 even in comments instead of inserting replacement characters.
   *
   * @param table The table with a marker for malformed bytes.
   */
  @ParameterizedTest
  @ValueSource(strings = {"id\tv\na?\t1\nb\t2\n", "id\tv?\na\t1\nb\t2\n",
      "# ?\nid\tv\na\t1\nb\t2\n"})
  void testMalformedUtf8(String table) {
    final byte[] bytes = table.getBytes(StandardCharsets.UTF_8);
    bytes[table.indexOf('?')] = (byte) 0xC3;
    final InvalidFormatException error = assertThrows(InvalidFormatException.class,
        () -> PlaceProfiles.load(new ByteArrayInputStream(bytes)));
    assertInstanceOf(CharacterCodingException.class, error.getCause());
  }

  /**
   * Accepts the supported line endings and preserves data-line numbering.
   *
   * @param ending The line separator.
   * @throws IOException Thrown if valid content cannot be loaded.
   */
  @ParameterizedTest
  @ValueSource(strings = {"\n", "\r\n", "\r"})
  void testLineEndings(String ending) throws IOException {
    final String table = String.join(ending, "# note", "id\tv", "a\t1", "b\t2");
    assertEquals(-1.0, load(table).similarity("a", "b"));
    assertEquals("duplicate id in row 5: a", assertThrows(InvalidFormatException.class,
        () -> load(table + ending + "a\t3")).getMessage());
  }

  /**
   * Does not close the supplied stream, including after a format error.
   *
   * @param table Valid content or content with an empty metric cell.
   * @throws IOException Thrown if valid content cannot be loaded.
   */
  @ParameterizedTest
  @ValueSource(strings = {"id\tv\na\t1\nb\t2\n", "id\tv\na\t\n"})
  void testStreamRemainsOpen(String table) throws IOException {
    final CloseTrackingInputStream input = new CloseTrackingInputStream(table);
    if (table.contains("\t\n")) {
      assertThrows(InvalidFormatException.class, () -> PlaceProfiles.load(input));
    } else {
      PlaceProfiles.load(input);
    }
    assertFalse(input.closed);
  }

  /** Transport errors remain distinguishable from malformed content. */
  @Test
  void testTransportErrorIsPreserved() {
    final IOException failure = new IOException("read failed");
    final InputStream input = new InputStream() {
      /** {@inheritDoc} */
      @Override
      public int read() throws IOException {
        throw failure;
      }
    };
    assertSame(failure, assertThrows(IOException.class, () -> PlaceProfiles.load(input)));
  }

  /** Input stream that records whether the loader closed it. */
  private static final class CloseTrackingInputStream extends ByteArrayInputStream {
    private boolean closed;

    /**
     * Wraps UTF-8 table content.
     *
     * @param table The table text.
     */
    private CloseTrackingInputStream(String table) {
      super(table.getBytes(StandardCharsets.UTF_8));
    }

    /** {@inheritDoc} */
    @Override
    public void close() throws IOException {
      closed = true;
      super.close();
    }
  }
}
