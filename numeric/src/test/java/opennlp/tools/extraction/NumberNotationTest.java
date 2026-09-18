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

package opennlp.tools.extraction;

import java.math.BigDecimal;
import java.util.Locale;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests the two written number conventions and their resolution from JDK locale data,
 * plus how the shared scan reads and rejects text in each of them.
 */
public class NumberNotationTest {

  @Test
  void testSeparatorsAreMirrorImages() {
    Assertions.assertEquals(',', NumberNotation.LATIN_US.groupSeparator());
    Assertions.assertEquals('.', NumberNotation.LATIN_US.decimalSeparator());
    Assertions.assertEquals('.', NumberNotation.LATIN_EU.groupSeparator());
    Assertions.assertEquals(',', NumberNotation.LATIN_EU.decimalSeparator());
  }

  @ParameterizedTest
  @EnumSource(NumberNotation.class)
  void testSeparatorsNeverCollide(NumberNotation notation) {
    Assertions.assertNotEquals(notation.groupSeparator(), notation.decimalSeparator());
  }

  /**
   * Verifies the locale resolution against JDK locale data: regions whose decimal
   * separator is a comma read as {@link NumberNotation#LATIN_EU}, every other region as
   * {@link NumberNotation#LATIN_US}. French Canada is in the list because it shows the
   * decision is taken per locale rather than per country neighborhood.
   */
  @ParameterizedTest
  @CsvSource(delimiter = ';', value = {
      "en-US; LATIN_US",
      "en-GB; LATIN_US",
      "en-AU; LATIN_US",
      "ja-JP; LATIN_US",
      "zh-CN; LATIN_US",
      "hi-IN; LATIN_US",
      "en-CA; LATIN_US",
      "de-DE; LATIN_EU",
      "fr-FR; LATIN_EU",
      "it-IT; LATIN_EU",
      "es-ES; LATIN_EU",
      "nl-NL; LATIN_EU",
      "pt-BR; LATIN_EU",
      "fr-CA; LATIN_EU"
  })
  void testNotationOfLocale(String languageTag, NumberNotation expected) {
    Assertions.assertEquals(expected,
        NumberNotation.forLocale(Locale.forLanguageTag(languageTag)), languageTag);
  }

  /**
   * Verifies that a locale without a country component resolves as well, so callers need
   * not special-case one: the root locale reads in the default notation.
   */
  @Test
  void testLocaleWithoutARegionResolves() {
    Assertions.assertEquals(NumberNotation.LATIN_US, NumberNotation.forLocale(Locale.ROOT));
  }

  @Test
  void testForLocaleValidation() {
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> NumberNotation.forLocale(null));
  }

  /**
   * Verifies the whole point of naming a notation: text that is written the same way
   * means one number in each convention, and both values are normalized to a dot decimal
   * separator whatever the text used.
   */
  @ParameterizedTest
  @CsvSource(delimiter = ';', value = {
      "1.500; 1.500; 1500",
      "1,500; 1500; 1.500",
      "12.345; 12.345; 12345",
      "12,345; 12345; 12.345"
  })
  void testTheSameTextMeansOneNumberPerNotation(String text, String unitedStates,
      String european) {
    final NumberScan.Result us = NumberScan.parse(text, 0, false, NumberNotation.LATIN_US);
    Assertions.assertEquals(0, new BigDecimal(unitedStates).compareTo(us.value()), text);

    final NumberScan.Result eu = NumberScan.parse(text, 0, false, NumberNotation.LATIN_EU);
    Assertions.assertEquals(0, new BigDecimal(european).compareTo(eu.value()), text);
  }

  /**
   * Verifies that a number carrying both separators is read as a whole only in the
   * notation it was written in. In the other notation the scan either fails outright or
   * stops inside the text, which is what makes the typed extractors reject it: their
   * mentions must end at a boundary, and a scan that stopped mid-number does not. Reading
   * {@code 1.234,56} as one and a bit is the silent error this behavior prevents.
   */
  @ParameterizedTest
  @CsvSource(delimiter = ';', value = {
      "1.234,56; LATIN_EU; 1234.56",
      "1.234.567,89; LATIN_EU; 1234567.89",
      "1,234.56; LATIN_US; 1234.56",
      "1,234,567.89; LATIN_US; 1234567.89"
  })
  void testTextCarryingBothSeparatorsScansWholeInOneNotationOnly(String text,
      NumberNotation written, String value) {
    final NumberScan.Result scanned = NumberScan.parse(text, 0, false, written);
    Assertions.assertEquals(0, new BigDecimal(value).compareTo(scanned.value()), text);
    Assertions.assertEquals(text.length(), scanned.end(), text);

    final NumberNotation other =
        written == NumberNotation.LATIN_EU ? NumberNotation.LATIN_US : NumberNotation.LATIN_EU;
    final NumberScan.Result misread = NumberScan.parse(text, 0, false, other);
    Assertions.assertTrue(misread == null || misread.end() < text.length(),
        () -> "misread as a whole number: " + text);
  }

  /**
   * Verifies that a grouping neither notation knows, the Indian grouping above all, is
   * never read as a whole number in either of them: the scan fails outright or stops
   * inside the text, and the typed extractors reject what does not end at a boundary.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "1,00,000",             // Indian grouping written with commas
      "1.00.000",             // the same shape written with dots
      "12,3456,789",          // groups of four
      "12.3456.789"           // groups of four in the other notation
  })
  void testGroupingNeitherNotationKnowsIsNeverReadWhole(String text) {
    for (final NumberNotation notation : NumberNotation.values()) {
      final NumberScan.Result scanned = NumberScan.parse(text, 0, false, notation);
      Assertions.assertTrue(scanned == null || scanned.end() < text.length(),
          () -> "read as a whole number in " + notation + ": " + text);
    }
  }

  /**
   * Verifies that a second decimal separator continues the malformed number instead of
   * ending a valid prefix. Typed extractors must not report that prefix as an amount.
   */
  @ParameterizedTest
  @CsvSource(delimiter = ';', value = {
      "1.2.3; LATIN_US",
      "1,2,3; LATIN_EU"
  })
  void testRepeatedDecimalSeparatorRejectsTheWholeScan(String text,
      NumberNotation notation) {
    Assertions.assertNull(NumberScan.parse(text, 0, false, notation), text);
  }

  /**
   * Verifies that a digit run too long to be a group is read as a fraction where the
   * separator marks one and rejected where it groups digits: {@code 1,2345} is a fraction
   * in the European notation and no number at all in the other, since a comma there
   * promises a group of exactly three digits.
   */
  @ParameterizedTest
  @CsvSource(delimiter = ';', value = {
      "1,2345; LATIN_EU; 1.2345",
      "1.2345; LATIN_US; 1.2345"
  })
  void testOverLongGroupIsAFractionInTheOtherNotation(String text, NumberNotation fraction,
      String value) {
    final NumberScan.Result scanned = NumberScan.parse(text, 0, false, fraction);
    Assertions.assertEquals(0, new BigDecimal(value).compareTo(scanned.value()), text);

    final NumberNotation grouping =
        fraction == NumberNotation.LATIN_EU ? NumberNotation.LATIN_US : NumberNotation.LATIN_EU;
    Assertions.assertNull(NumberScan.parse(text, 0, false, grouping), text);
  }

  /**
   * Verifies the tail guard: digits directly behind a separator that itself follows a
   * digit never seed a scan of their own, whichever role that separator plays in the
   * notation. This is what keeps a restarted scan from reporting the tail {@code 000} of
   * {@code 1,00,000} or the {@code 3} of a version {@code 1.2.3} as a number of its own.
   */
  @Test
  void testTailOfAnEarlierNumberIsRecognizedInEachNotation() {
    for (final NumberNotation notation : NumberNotation.values()) {
      Assertions.assertTrue(NumberScan.continuesNumber("1,00,000", 5, notation),
          notation.name());
      Assertions.assertTrue(NumberScan.continuesNumber("1.00.000", 5, notation),
          notation.name());
      Assertions.assertTrue(NumberScan.continuesNumber("1.2.3", 4, notation),
          notation.name());
      Assertions.assertFalse(NumberScan.continuesNumber("1 000", 2, notation),
          notation.name());
      Assertions.assertFalse(NumberScan.continuesNumber(",000", 1, notation),
          notation.name());
      Assertions.assertFalse(NumberScan.continuesNumber("id,000", 3, notation),
          notation.name());
    }
  }
}
