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

package opennlp.tools.numeric;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.extraction.NumberNotation;
import opennlp.tools.extraction.NumberScan;
import opennlp.tools.money.CursorMoneyExtractor;
import opennlp.tools.quantity.CursorQuantityExtractor;
import opennlp.tools.quantity.Quantity;
import opennlp.tools.temporal.CursorTemporalExtractor;
import opennlp.tools.temporal.TemporalExpression;
import opennlp.tools.util.Span;

/** Checks rejected numeric fragments and Unicode word boundaries. */
class NumericBoundaryTest {

  private static final LocalDate REFERENCE = LocalDate.of(2026, 7, 14);
  private static final List<String> NUMERIC_SEPARATORS =
      List.of("\u00A0", "\u2009", "\u202F", "'", "\u2019", "\u066B", "\u066C");

  /** {@return unsupported separators paired with each supported notation} */
  private static Stream<Arguments> unsupportedSeparators() {
    return NUMERIC_SEPARATORS.stream().flatMap(separator ->
        Stream.of(NumberNotation.values()).map(notation -> Arguments.of(separator, notation)));
  }

  /**
   * A recognized but unsupported numeric separator cannot terminate a valid prefix.
   *
   * @param separator The unsupported grouping or decimal character.
   * @param notation The selected number convention.
   */
  @ParameterizedTest
  @MethodSource("unsupportedSeparators")
  void testUnsupportedSeparatorRejectsScan(String separator, NumberNotation notation) {
    Assertions.assertNull(NumberScan.parse("1" + separator + "234", 0, false, notation));
    Assertions.assertTrue(NumberScan.continuesNumber("1" + separator + "234", 2, notation));
  }

  /**
   * Money extraction rejects both the prefix and the tail of unsupported grouping.
   *
   * @param separator The unsupported grouping or decimal character.
   * @param notation The selected number convention.
   */
  @ParameterizedTest
  @MethodSource("unsupportedSeparators")
  void testUnsupportedSeparatorRejectsMoney(String separator, NumberNotation notation) {
    final CursorMoneyExtractor extractor = new CursorMoneyExtractor(notation);
    Assertions.assertTrue(extractor.extract("$1" + separator + "234").isEmpty());
    Assertions.assertTrue(extractor.extract("1" + separator + "234 USD").isEmpty());
    Assertions.assertTrue(extractor.extract("USD 1" + separator + "234").isEmpty());
  }

  /**
   * Quantity extraction cannot restart at a trailing digit group.
   *
   * @param separator The unsupported grouping or decimal character.
   * @param notation The selected number convention.
   */
  @ParameterizedTest
  @MethodSource("unsupportedSeparators")
  void testUnsupportedSeparatorRejectsQuantity(String separator, NumberNotation notation) {
    Assertions.assertTrue(new CursorQuantityExtractor(notation)
        .extract("1" + separator + "234 kg").isEmpty());
  }

  /**
   * Unit tokens followed by a letter or digit are not complete mentions.
   *
   * @param suffix A BMP or supplementary letter or digit.
   */
  @ParameterizedTest
  @ValueSource(strings = {"a", "2", "\uD801\uDC00", "\uD835\uDFD9"})
  void testQuantitySuffix(String suffix) {
    final CursorQuantityExtractor extractor = new CursorQuantityExtractor();
    Assertions.assertTrue(extractor.extract("5kg" + suffix).isEmpty());
    Assertions.assertTrue(extractor.extract("5 percent" + suffix).isEmpty());
    Assertions.assertTrue(extractor.extract("5%" + suffix).isEmpty());
  }

  /**
   * Scale suffixes and words must also end before a complete Unicode boundary.
   *
   * @param suffix A BMP or supplementary letter or digit.
   */
  @ParameterizedTest
  @ValueSource(strings = {"a", "2", "\uD801\uDC00", "\uD835\uDFD9"})
  void testScaleSuffix(String suffix) {
    Assertions.assertNull(NumberScan.parse("5m" + suffix, 0, true, NumberNotation.LATIN_US));
    final NumberScan.Result word =
        NumberScan.parse("5 million" + suffix, 0, true, NumberNotation.LATIN_US);
    Assertions.assertEquals(1, word.end());
    Assertions.assertEquals(0, BigDecimal.valueOf(5).compareTo(word.value()));
  }

  /** {@return relative phrases paired with invalid trailing letters and digits} */
  private static Stream<Arguments> relativeSuffixes() {
    return Stream.of("today", "yesterday", "tomorrow", "next week", "in 2 days", "3 days ago")
        .flatMap(phrase -> Stream.of("a", "2", "\uD801\uDC00", "\uD835\uDFD9")
            .map(suffix -> Arguments.of(phrase, suffix)));
  }

  /**
   * Relative dates do not match prefixes of longer alphanumeric tokens.
   *
   * @param phrase The otherwise valid relative expression.
   * @param suffix The trailing letter or digit.
   */
  @ParameterizedTest
  @MethodSource("relativeSuffixes")
  void testRelativeSuffix(String phrase, String suffix) {
    Assertions.assertTrue(new CursorTemporalExtractor().extract(phrase + suffix, REFERENCE).isEmpty());
  }

  /**
   * Punctuation and emoji remain valid boundaries and preserve original offsets.
   *
   * @param boundary A non-alphanumeric separator.
   */
  @ParameterizedTest
  @ValueSource(strings = {" ", ",", ";", "!", "\uD83D\uDCE6", "'", "\u2019"})
  void testAcceptedBoundaries(String boundary) {
    final List<Quantity> quantities =
        new CursorQuantityExtractor().extract(boundary + "5kg" + boundary);
    Assertions.assertEquals(1, quantities.size());
    Assertions.assertEquals(new Span(boundary.length(), boundary.length() + 3),
        quantities.getFirst().span());
    final List<TemporalExpression> dates = new CursorTemporalExtractor()
        .extract(boundary + "today" + boundary, REFERENCE);
    Assertions.assertEquals(1, dates.size());
    Assertions.assertEquals(new Span(boundary.length(), boundary.length() + 5),
        dates.getFirst().span());
    Assertions.assertEquals("2026-07-14", dates.getFirst().value());
    Assertions.assertEquals(1,
        new CursorMoneyExtractor().extract(boundary + "5 USD" + boundary).size());
  }

  /**
   * Custom unit tokens accept complete Unicode letters within the existing length limit.
   *
   * @param unit A BMP or supplementary-letter unit token.
   */
  @ParameterizedTest
  @ValueSource(strings = {"\u03BCg", "\uD835\uDC8E", "\uD800\uDC00\uD800\uDC01\uD800\uDC02"})
  void testCustomUnicodeUnits(String unit) {
    final CursorQuantityExtractor extractor = new CursorQuantityExtractor(Set.of(unit));
    final List<Quantity> quantities = extractor.extract("5" + unit);
    Assertions.assertEquals(1, quantities.size());
    Assertions.assertEquals(unit, quantities.getFirst().unit());
    Assertions.assertEquals(new Span(0, 1 + unit.length()), quantities.getFirst().span());
    Assertions.assertTrue(extractor.extract("5" + unit + "x").isEmpty());
  }

  /**
   * Custom units reject incomplete surrogate pairs, nonletters, and excessive length.
   *
   * @param unit The invalid custom unit.
   */
  @ParameterizedTest
  @ValueSource(strings = {"\uD800", "\uDC00", "\uD83D\uDCE6",
      "\uD800\uDC00\uD800\uDC01\uD800\uDC02\uD800\uDC03"})
  void testInvalidCustomUnicodeUnits(String unit) {
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new CursorQuantityExtractor(Set.of(unit)));
  }

  /**
   * Word-length limits do not split supplementary letters or accept partial tokens.
   *
   * @param text The candidate text.
   * @param start The start offset.
   * @param limit The maximum UTF-16 length.
   * @param end The expected end offset, or minus one for rejection.
   */
  @ParameterizedTest
  @CsvSource(delimiter = ';', value = {
      "cat!; 0; 3; 3", "cat3; 0; 3; -1", "catastrophe; 0; 3; -1",
      "xcat!; 1; 3; 4", "\uD835\uDC8E!; 0; 2; 2", "\uD835\uDC8E!; 0; 1; -1",
      "\uD835\uDC8Ex!; 0; 3; 3", "\uD835\uDC8Ex!; 0; 2; -1", "cat; 3; 3; -1"
  })
  void testBoundedWord(String text, int start, int limit, int end) {
    Assertions.assertEquals(end, NumberScan.wordEnd(text, start, limit));
  }

  /**
   * Generated grouped decimals retain their exact value and optional scale.
   *
   * @param notation The convention used by both the independent formatter and scanner.
   */
  @ParameterizedTest
  @EnumSource(NumberNotation.class)
  void testGeneratedDecimals(NumberNotation notation) {
    final Locale locale = notation == NumberNotation.LATIN_US ? Locale.US : Locale.GERMANY;
    final DecimalFormat format =
        new DecimalFormat("#,##0.0000", DecimalFormatSymbols.getInstance(locale));
    final Random random = new Random(1909);
    for (int i = 0; i < 1000; i++) {
      final BigDecimal expected = BigDecimal.valueOf(random.nextLong(1_000_000_000_000L), 4);
      final String text = format.format(expected);
      final NumberScan.Result actual = NumberScan.parse(text, 0, false, notation);
      Assertions.assertNotNull(actual, text);
      Assertions.assertEquals(0, expected.compareTo(actual.value()), text);
      Assertions.assertEquals(text.length(), actual.end());
      final NumberScan.Result scaled = NumberScan.parse(text + "M", 0, true, notation);
      Assertions.assertNotNull(scaled, text);
      Assertions.assertEquals(0, expected.movePointRight(6).compareTo(scaled.value()), text);
      Assertions.assertEquals(text.length() + 1, scaled.end());
    }
  }
}
