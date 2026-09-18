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

package opennlp.tools.money;

import java.io.IOException;
import java.time.LocalDate;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static opennlp.tools.money.EcbFxRatesTestSupport.assertRate;
import static opennlp.tools.money.EcbFxRatesTestSupport.load;

/** Tests CSV field padding without accepting malformed values after trimming. */
class EcbFxRatesPaddingTest {

  private static final String HEADER = "Date,USD,JPY\n";
  private static final String RECORD = "2026-07-14,1.25,150\n";
  private static final LocalDate DATE = LocalDate.of(2026, 7, 14);

  /**
   * Supplies control characters at boundaries where trimming could remove them.
   *
   * @return Descriptions and malformed CSV tables.
   */
  private static Stream<Arguments> corruptedFields() {
    return Stream.of(
        Arguments.of("currency prefix", "Date,\u0000USD,JPY\n" + RECORD),
        Arguments.of("currency suffix", "Date,USD\u0000,JPY\n" + RECORD),
        Arguments.of("header final field", "Date,USD,JPY,\u0000\n" + RECORD),
        Arguments.of("date prefix", HEADER + "\u00002026-07-14,1.25,150\n"),
        Arguments.of("date suffix", HEADER + "2026-07-14\u0000,1.25,150\n"),
        Arguments.of("rate prefix", HEADER + "2026-07-14,\u00001.25,150\n"),
        Arguments.of("rate suffix", HEADER + "2026-07-14,1.25\u0000,150\n"),
        Arguments.of("missing marker prefix", HEADER + "2026-07-14,1.25,\u0000N/A\n"),
        Arguments.of("missing marker suffix", HEADER + "2026-07-14,1.25,N/A\u0000\n"),
        Arguments.of("empty quote", HEADER + "2026-07-14,1.25,\u0000\n"),
        Arguments.of("record final field", HEADER + "2026-07-14,1.25,150,\u0000\n"));
  }

  /**
   * Control characters cannot turn malformed fields into usable values or empty fields.
   *
   * @param description The field location.
   * @param csv The malformed input.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("corruptedFields")
  void testCorruptedField(String description, String csv) {
    Assertions.assertThrows(IllegalArgumentException.class, () -> load(csv), description);
  }

  /**
   * Non-whitespace controls and format characters are not currency-code padding.
   *
   * @param codePoint The padding character.
   */
  @ParameterizedTest
  @ValueSource(ints = {0, 1, 7, 8, 14, 27, 127, 133, 0x200B, 0xFEFF})
  void testNonWhitespacePadding(int codePoint) {
    final String padding = Character.toString(codePoint);
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> load("Date," + padding + "USD" + padding + ",JPY\n" + RECORD));
  }

  /**
   * Existing ASCII whitespace padding remains accepted around all field kinds.
   *
   * @param codePoint The Java whitespace character in the ASCII range.
   * @throws IOException If loading fails.
   */
  @ParameterizedTest
  @ValueSource(ints = {9, 11, 12, 28, 29, 30, 31, 32})
  void testWhitespacePadding(int codePoint) throws IOException {
    final String pad = Character.toString(codePoint);
    final EcbFxRates rates = load("Date," + pad + "USD" + pad + "," + pad + "JPY" + pad + ","
        + pad + "\r\n" + pad + "2026-07-14" + pad + "," + pad + "1.25" + pad + ","
        + pad + "N/A" + pad + "," + pad + "\r\n" + pad + "\r\n");
    assertRate("1.25", rates.rate("EUR", "USD", DATE));
    Assertions.assertTrue(rates.rate("EUR", "JPY", DATE).isEmpty());
  }

  /**
   * Unicode spaces are not added to the supported CSV field-padding syntax.
   *
   * @param codePoint The unsupported whitespace character.
   */
  @ParameterizedTest
  @ValueSource(ints = {0xA0, 0x2003, 0x202F})
  void testUnsupportedWhitespacePadding(int codePoint) {
    final String padding = Character.toString(codePoint);
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> load("Date," + padding + "USD,JPY\n" + RECORD));
  }
}
