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

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import opennlp.tools.glossary.GlossaryEntry;
import opennlp.tools.util.InvalidFormatException;

import static opennlp.tools.formats.glossary.GlossaryTestSupport.utf8;

/** Tests CSV quoting, configuration, and invalid-record diagnostics. */
public class CsvGlossaryReaderTest {

  /**
   * Returns identifiers and terms in file order.
   *
   * @throws IOException If reading fails.
   */
  @Test
  void testReadsSimpleCommaSeparatedRows() throws IOException {
    final List<GlossaryEntry> entries = new CsvGlossaryReader()
        .read(utf8("Q1,hot dog\nQ2,New York City\n"));

    Assertions.assertEquals(2, entries.size());
    Assertions.assertEquals("Q1", entries.get(0).id());
    Assertions.assertEquals("hot dog", entries.get(0).term());
    Assertions.assertEquals("Q2", entries.get(1).id());
    Assertions.assertEquals("New York City", entries.get(1).term());
  }

  /**
   * Ignores the first record's values when header skipping is enabled.
   *
   * @throws IOException If reading fails.
   */
  @Test
  void testSkipsHeaderWhenConfigured() throws IOException {
    final List<GlossaryEntry> entries = new CsvGlossaryReader(',', true)
        .read(utf8("id,term\nQ1,hot dog\n"));

    Assertions.assertEquals(1, entries.size());
    Assertions.assertEquals("Q1", entries.get(0).id());
  }

  /**
   * Preserves delimiters, escaped quotes, and line breaks in quoted fields.
   *
   * @throws IOException If reading fails.
   */
  @Test
  void testQuotedFieldsCarryDelimitersQuotesAndNewlines() throws IOException {
    final List<GlossaryEntry> entries = new CsvGlossaryReader()
        .read(utf8("Q1,\"hot, dog\"\nQ2,\"say \"\"hi\"\"\"\nQ3,\"line one\nline two\"\n"));

    Assertions.assertEquals(3, entries.size());
    Assertions.assertEquals("hot, dog", entries.get(0).term());
    Assertions.assertEquals("say \"hi\"", entries.get(1).term());
    Assertions.assertEquals("line one\nline two", entries.get(2).term());
  }

  /**
   * Supports tab-separated input.
   *
   * @throws IOException If reading fails.
   */
  @Test
  void testTabDelimiter() throws IOException {
    final List<GlossaryEntry> entries = new CsvGlossaryReader('\t', false)
        .read(utf8("Q1\thot dog\nQ2\tNew York City\n"));

    Assertions.assertEquals(2, entries.size());
    Assertions.assertEquals("New York City", entries.get(1).term());
  }

  /**
   * Accepts CRLF, blank lines, and a record without a final newline.
   *
   * @throws IOException If reading fails.
   */
  @Test
  void testCrlfBlankLinesAndMissingFinalNewline() throws IOException {
    final List<GlossaryEntry> entries = new CsvGlossaryReader()
        .read(utf8("Q1,hot dog\r\n\r\nQ2,New York City"));

    Assertions.assertEquals(2, entries.size());
    Assertions.assertEquals("Q2", entries.get(1).id());
  }

  /**
   * Removes a leading UTF-8 byte order mark.
   *
   * @throws IOException If reading fails.
   */
  @Test
  void testUtf8ByteOrderMarkIsStripped() throws IOException {
    final List<GlossaryEntry> entries = new CsvGlossaryReader()
        .read(utf8("\uFEFFQ1,hot dog\n"));

    Assertions.assertEquals(1, entries.size());
    Assertions.assertEquals("Q1", entries.get(0).id());
  }

  /**
   * Ignores metadata column values after the identifier and term.
   *
   * @throws IOException If reading fails.
   */
  @Test
  void testExtraColumnsAreIgnored() throws IOException {
    final List<GlossaryEntry> entries = new CsvGlossaryReader()
        .read(utf8("Q1,hot dog,en,approved\n"));

    Assertions.assertEquals(1, entries.size());
    Assertions.assertEquals("Q1", entries.get(0).id());
    Assertions.assertEquals("hot dog", entries.get(0).term());
  }

  /** Reports a missing term column with the record's line number. */
  @Test
  void testTooFewColumnsFailsWithLineNumber() {
    final InvalidFormatException ex = Assertions.assertThrows(InvalidFormatException.class,
        () -> new CsvGlossaryReader().read(utf8("Q1,hot dog\nQ2\n")));
    Assertions.assertTrue(ex.getMessage().contains("line 2"), ex.getMessage());
  }

  /** Reports blank identifiers and terms with the record's line number. */
  @Test
  void testBlankIdOrTermFailsWithLineNumber() {
    final InvalidFormatException blankId = Assertions.assertThrows(InvalidFormatException.class,
        () -> new CsvGlossaryReader().read(utf8(" ,hot dog\n")));
    Assertions.assertTrue(blankId.getMessage().contains("line 1"), blankId.getMessage());

    final InvalidFormatException blankTerm = Assertions.assertThrows(InvalidFormatException.class,
        () -> new CsvGlossaryReader().read(utf8("Q1,hot dog\nQ2,\"\"\n")));
    Assertions.assertTrue(blankTerm.getMessage().contains("line 2"), blankTerm.getMessage());
  }

  /** Rejects an unclosed quoted field. */
  @Test
  void testUnclosedQuoteFailsLoud() {
    Assertions.assertThrows(InvalidFormatException.class,
        () -> new CsvGlossaryReader().read(utf8("Q1,\"never closed\n")));
  }

  /**
   * Returns no entries for empty input or a header without data.
   *
   * @throws IOException If reading fails.
   */
  @Test
  void testEmptyInputYieldsEmptyList() throws IOException {
    Assertions.assertTrue(new CsvGlossaryReader().read(utf8("")).isEmpty());
    Assertions.assertTrue(new CsvGlossaryReader(',', true).read(utf8("id,term\n")).isEmpty());
  }

  /** Rejects invalid delimiters and a null input stream. */
  @Test
  void testInvalidArguments() {
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new CsvGlossaryReader('"', false));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new CsvGlossaryReader('\n', false));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new CsvGlossaryReader('\r', false));
    final CsvGlossaryReader reader = new CsvGlossaryReader();
    Assertions.assertThrows(IllegalArgumentException.class, () -> reader.read(null));
  }
}
