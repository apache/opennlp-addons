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

package opennlp.tools.pii;

import java.util.Locale;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

public class AsciiTest {

  @Test
  void testClassificationAgreesWithTheJdkOverTheAsciiRange() {
    for (char c = 0; c < 128; c++) {
      Assertions.assertEquals(Character.isDigit(c), Ascii.isDigit(c), "digit " + (int) c);
      Assertions.assertEquals(Character.isLetter(c), Ascii.isLetter(c), "letter " + (int) c);
      Assertions.assertEquals(Character.isUpperCase(c), Ascii.isUpper(c), "upper " + (int) c);
      Assertions.assertEquals(Character.isLowerCase(c), Ascii.isLower(c), "lower " + (int) c);
      Assertions.assertEquals(Character.isLetterOrDigit(c), Ascii.isLetterOrDigit(c),
          "letterOrDigit " + (int) c);
      Assertions.assertEquals(Character.digit(c, 16), Ascii.hexValue(c), "hex " + (int) c);
      Assertions.assertEquals(Ascii.hexValue(c) >= 0, Ascii.isHexDigit(c), "isHex " + (int) c);
    }
  }

  @ParameterizedTest
  @ValueSource(chars = {'\u00e9', '\u00c9', '\u0131', '\u0130', '\u4e2d', '\u0660', '\u2160'})
  void testNonAsciiCharactersAreNeitherLetterNorDigit(char c) {
    Assertions.assertFalse(Ascii.isLetter(c));
    Assertions.assertFalse(Ascii.isDigit(c));
    Assertions.assertFalse(Ascii.isLetterOrDigit(c));
    Assertions.assertFalse(Ascii.isHexDigit(c));
    Assertions.assertEquals(-1, Ascii.hexValue(c));
  }

  @ParameterizedTest
  @CsvSource({
      "A, a", "Z, z", "M, m", "a, a", "z, z", "0, 0", "-, -", "@, @"
  })
  void testFoldsAsciiLettersToLowercase(char input, char expected) {
    Assertions.assertEquals(expected, Ascii.toLower(input));
  }

  @ParameterizedTest
  @CsvSource({
      "a, A", "z, Z", "m, M", "A, A", "Z, Z", "0, 0", "-, -", "@, @"
  })
  void testFoldsAsciiLettersToUppercase(char input, char expected) {
    Assertions.assertEquals(expected, Ascii.toUpper(input));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "AKIAIOSFODNN7EXAMPLE",
      "00:1B:44:11:3A:B7",
      "0xAbCdEf0123456789",
      "MiXeD case With SPACES",
      ""})
  void testSequenceFoldingMatchesTheRootLocaleForAsciiInput(String value) {
    Assertions.assertEquals(value.toLowerCase(Locale.ROOT), Ascii.toLower(value));
  }

  /**
   * Verifies that folding stays ASCII-only and length preserving, unlike a locale
   * fold: the dotted capital I becomes an i with a combining dot there, one character
   * more than the input.
   */
  @Test
  void testFoldingLeavesNonAsciiLettersUnchanged() {
    Assertions.assertEquals("\u0130stanbul", Ascii.toLower("\u0130STANBUL"));
    Assertions.assertEquals("\u00c9cole", Ascii.toLower("\u00c9COLE"));
    Assertions.assertEquals(8, Ascii.toLower("\u0130STANBUL").length());
    Assertions.assertEquals(9, "\u0130STANBUL".toLowerCase(Locale.ROOT).length());
  }
}
