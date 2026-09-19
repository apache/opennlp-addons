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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.glossary.GlossaryEntry;
import opennlp.tools.util.InvalidFormatException;

import static opennlp.tools.formats.glossary.GlossaryTestSupport.utf8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks CSV field boundaries, encoding errors, and physical line numbers. */
public class CsvGlossaryReaderBoundaryTest {

  /**
   * Rejects unescaped quotes and content following a closing quote.
   *
   * @param record The malformed record.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "Q1,unquoted\"term", "Q\"1,term", "Q1,\"term\"suffix",
      "Q1,\"term\" ", "Q1, \"term\"", "Q1,term,\"metadata\"suffix",
      "\"Q1\"suffix,term"
  })
  void testMalformedQuotes(String record) {
    final InvalidFormatException error = assertThrows(InvalidFormatException.class,
        () -> new CsvGlossaryReader().read(utf8("Q0,valid\n" + record)));
    assertTrue(error.getMessage().contains("line 2"), error.getMessage());
  }

  /** Validates CSV quoting even when the header's values are ignored. */
  @Test
  void testMalformedHeader() {
    assertThrows(InvalidFormatException.class, () -> new CsvGlossaryReader(',', true)
        .read(utf8("\"id\"suffix,term\nQ1,valid")));
  }

  /**
   * Preserves field text in valid quoted and unquoted records.
   *
   * @param record The record with a term containing a quote or spaces.
   * @throws IOException If the input cannot be read.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "Q1,\"say \"\"hi\"\"\"", "Q1,\"say \"\"hi\"\"\",ignored"
  })
  void testEscapedQuotes(String record) throws IOException {
    assertEquals(List.of(new GlossaryEntry("Q1", "say \"hi\"")),
        new CsvGlossaryReader().read(utf8(record)));
  }

  /**
   * Counts physical line breaks inside quoted fields without normalizing them.
   *
   * @param newline The physical line separator.
   */
  @ParameterizedTest
  @ValueSource(strings = {"\n", "\r\n", "\r"})
  void testLineNumberAfterMultilineField(String newline) {
    final String content = "Q1,\"first" + newline + "last\"" + newline + "Q2";
    final InvalidFormatException error = assertThrows(InvalidFormatException.class,
        () -> new CsvGlossaryReader().read(utf8(content)));
    assertTrue(error.getMessage().contains("line 3"), error.getMessage());
  }

  /**
   * Supplies malformed UTF-8 byte sequences.
   *
   * @return Invalid sequences, including an incomplete final character.
   */
  private static Stream<byte[]> invalidUtf8() {
    return Stream.of(new byte[] {(byte) 0xC3, 0x28}, new byte[] {(byte) 0x80},
        new byte[] {(byte) 0xED, (byte) 0xA0, (byte) 0x80},
        new byte[] {(byte) 0xE2, (byte) 0x82});
  }

  /**
   * Rejects invalid UTF-8 instead of inserting a replacement character into a term.
   *
   * @param invalid The malformed encoded term.
   */
  @ParameterizedTest
  @MethodSource("invalidUtf8")
  void testInvalidUtf8(byte[] invalid) {
    final byte[] prefix = "Q1,".getBytes(StandardCharsets.UTF_8);
    final byte[] content = new byte[prefix.length + invalid.length];
    System.arraycopy(prefix, 0, content, 0, prefix.length);
    System.arraycopy(invalid, 0, content, prefix.length, invalid.length);
    assertThrows(InvalidFormatException.class,
        () -> new CsvGlossaryReader().read(new ByteArrayInputStream(content)));
  }

  /**
   * Accepts an explicitly encoded replacement character as data.
   *
   * @throws IOException If the input cannot be read.
   */
  @Test
  void testEncodedReplacementCharacter() throws IOException {
    assertEquals(List.of(new GlossaryEntry("Q1", "replacement\uFFFD")),
        new CsvGlossaryReader().read(utf8("Q1,replacement\uFFFD")));
  }

}
