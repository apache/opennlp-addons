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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.extraction.NumberNotation;
import opennlp.tools.extraction.NumberScan;
import opennlp.tools.money.CursorMoneyExtractor;
import opennlp.tools.quantity.CursorQuantityExtractor;
import opennlp.tools.quantity.Quantity;
import opennlp.tools.temporal.CursorTemporalExtractor;
import opennlp.tools.util.Span;

/** Tests unsupported numeric forms and competing interpretations of the same text. */
class NumericTokenIntegrityTest {

  private static final LocalDate REFERENCE = LocalDate.of(2026, 7, 14);

  /**
   * Scientific notation is not split into a mantissa or a signed exponent mention.
   *
   * @param number The unsupported scientific notation.
   */
  @ParameterizedTest
  @ValueSource(strings = {"1e3", "1e+3", "1e-3", "1E-03", "2.5e-3", "2,500e+3",
      "2,5e-3", "1e-3e-4", "1.e-3"})
  void testScientificNotationFragments(String number) {
    for (final NumberNotation notation : NumberNotation.values()) {
      final CursorQuantityExtractor quantity = new CursorQuantityExtractor(notation);
      final CursorMoneyExtractor money = new CursorMoneyExtractor(notation);
      Assertions.assertAll(number + " in " + notation,
          () -> Assertions.assertTrue(quantity.extract(number + "kg").isEmpty()),
          () -> Assertions.assertTrue(money.extract(number + " USD").isEmpty()),
          () -> Assertions.assertTrue(money.extract("$" + number).isEmpty()),
          () -> Assertions.assertTrue(new CursorTemporalExtractor()
              .extract(number + " days ago", REFERENCE).isEmpty()));
    }
  }

  /** A custom unit named e does not turn an exponent prefix into a quantity. */
  @Test
  void testCustomExponentUnit() {
    final CursorQuantityExtractor quantity = new CursorQuantityExtractor(Set.of("e", "E"));
    Assertions.assertTrue(quantity.extract("1e-3").isEmpty());
    Assertions.assertTrue(quantity.extract("1E+3").isEmpty());
    Assertions.assertEquals(1, quantity.extract("3e").size());
  }

  /**
   * Combining marks remain attached to their neighboring token.
   *
   * @param mark A nonspacing, spacing, enclosing, supplementary, or variation mark.
   */
  @ParameterizedTest
  @ValueSource(strings = {"\u0301", "\u0903", "\u20DD", "\uD834\uDD65", "\uFE0F"})
  void testCombiningMarks(String mark) {
    final CursorQuantityExtractor quantity = new CursorQuantityExtractor();
    final CursorMoneyExtractor money = new CursorMoneyExtractor();
    final CursorTemporalExtractor temporal = new CursorTemporalExtractor();
    Assertions.assertAll(
        () -> Assertions.assertTrue(quantity.extract("a" + mark + "12kg").isEmpty()),
        () -> Assertions.assertTrue(quantity.extract("5kg" + mark).isEmpty()),
        () -> Assertions.assertTrue(money.extract("$5" + mark).isEmpty()),
        () -> Assertions.assertTrue(money.extract("US" + mark + "$5").isEmpty()),
        () -> Assertions.assertTrue(temporal.extract("today" + mark, REFERENCE).isEmpty()));
  }

  /**
   * Numeric suffixes and identifier connectors cannot be dropped from a unit.
   *
   * @param suffix The continuation of the unit or identifier.
   */
  @ParameterizedTest
  @ValueSource(strings = {"\u00B2", "\u2163", "\u2460", "_item"})
  void testUnitContinuations(String suffix) {
    Assertions.assertTrue(new CursorQuantityExtractor().extract("5m" + suffix).isEmpty());
  }

  /**
   * A complete money mention is not also reported as a measurement.
   *
   * @param text The amount whose scale suffix also names a unit.
   */
  @ParameterizedTest
  @ValueSource(strings = {"$3m", "USD 3m", "3m USD", "3m dollars", "-\u00A33m",
      "EUR 1,000m", "($3m)", "3m\u20AC"})
  void testMoneyTakesPrecedence(String text) {
    Assertions.assertEquals(1, new CursorMoneyExtractor().extract(text).size());
    Assertions.assertTrue(new CursorQuantityExtractor().extract(text).isEmpty());
  }

  /**
   * Filtering money retains other quantities and their original spans.
   *
   * @param notation The number convention shared by the two extractors.
   */
  @ParameterizedTest
  @EnumSource(NumberNotation.class)
  void testSeparateMeasurementsRemain(NumberNotation notation) {
    final String text = "Paid $3m for 3m of pipe and 5kg of fittings; USD 2m remained.";
    final List<Quantity> quantities = new CursorQuantityExtractor(notation).extract(text);
    Assertions.assertEquals(2, quantities.size());
    Assertions.assertEquals(new Span(13, 15), quantities.get(0).span());
    Assertions.assertEquals(new Span(28, 31), quantities.get(1).span());
    Assertions.assertEquals("m", quantities.get(0).unit());
    Assertions.assertEquals("kg", quantities.get(1).unit());
    Assertions.assertEquals(0, BigDecimal.valueOf(3).compareTo(quantities.get(0).value()));
    Assertions.assertEquals(0, BigDecimal.valueOf(5).compareTo(quantities.get(1).value()));
  }

  /** Range hyphens and ordinary measurement signs are not exponent signs. */
  @Test
  void testRangesAndSignsRemain() {
    final List<Quantity> quantities = new CursorQuantityExtractor().extract("3kg-5kg and -2kg");
    Assertions.assertEquals(3, quantities.size());
    Assertions.assertEquals(0, BigDecimal.valueOf(3).compareTo(quantities.get(0).value()));
    Assertions.assertEquals(0, BigDecimal.valueOf(5).compareTo(quantities.get(1).value()));
    Assertions.assertEquals(0, BigDecimal.valueOf(-2).compareTo(quantities.get(2).value()));
  }

  /**
   * Marks attached to punctuation or emoji do not make that symbol a word prefix.
   *
   * @param prefix A decorated non-word character before the mention.
   */
  @ParameterizedTest
  @ValueSource(strings = {"\u2615\uFE0F", "\uD83D\uDCE6\uFE0F", "(\u0301"})
  void testDecoratedSymbolBoundaries(String prefix) {
    final CursorQuantityExtractor quantity = new CursorQuantityExtractor();
    final List<Quantity> positive = quantity.extract(prefix + "5kg");
    Assertions.assertEquals(1, positive.size());
    Assertions.assertEquals(new Span(prefix.length(), prefix.length() + 3),
        positive.getFirst().span());
    final List<Quantity> negative = quantity.extract(prefix + "-5kg");
    Assertions.assertEquals(1, negative.size());
    Assertions.assertEquals(0, BigDecimal.valueOf(-5).compareTo(negative.getFirst().value()));
    Assertions.assertEquals(1, new CursorTemporalExtractor().extract(prefix + "today", REFERENCE).size());
    Assertions.assertEquals(1, new CursorMoneyExtractor().extract(prefix + "$5").size());
  }

  /**
   * Consecutive marks use the preceding base character for start and sign boundaries.
   *
   * @param base The base character, or an empty string for leading marks.
   * @param boundary Whether a numeric mention can start after the marks.
   * @param signed Whether a negative mention can start after the marks.
   */
  @ParameterizedTest
  @CsvSource(value = {"''|true|true", "a|false|false", "9|false|false", "_|false|false",
      "\uD801\uDC00|false|false", "\u00B2|false|false", "(|true|true",
      "\uD83D\uDCE6|true|true", "$|true|false", "%|true|false"}, delimiter = '|')
  void testCombiningMarkRuns(String base, boolean boundary, boolean signed) {
    final String prefix = base + "\u0301\u0903\u20DD\uD834\uDD65\uFE0F";
    Assertions.assertEquals(base.isEmpty() ? NumberScan.NO_CODE_POINT : base.codePointAt(0),
        NumberScan.baseCodePointBefore(prefix, prefix.length()));
    Assertions.assertEquals(boundary, NumberScan.boundaryBefore(prefix, prefix.length()));
    Assertions.assertEquals(signed, NumberScan.signBoundaryBefore(prefix, prefix.length()));
  }

  /**
   * ASCII currency prefixes reject attached marks; non-ASCII context remains supported.
   *
   * @param prefix Text before the currency symbol.
   * @param expected The expected amount count.
   */
  @ParameterizedTest
  @CsvSource(value = {"US\u0301|0", "HK\u0301\uFE0F|0", "\u4EF7\u0301|1",
      "\uD801\uDC00\u0301|1", "\uD83D\uDCE6\uFE0F|1"}, delimiter = '|')
  void testMarkedCurrencyContext(String prefix, int expected) {
    Assertions.assertEquals(expected, new CursorMoneyExtractor().extract(prefix + "$5").size());
  }

  /** Currency-like text without a recognized amount does not suppress a measurement. */
  @Test
  void testUnrecognizedCurrencyContext() {
    final String text = "$5kg; USD 8kg; 12kg USD; HK$3m";
    Assertions.assertTrue(new CursorMoneyExtractor().extract(text).isEmpty());
    final List<Quantity> quantities = new CursorQuantityExtractor().extract(text);
    Assertions.assertEquals(List.of("5kg", "8kg", "12kg", "3m"), quantities.stream()
        .map(quantity -> quantity.span().getCoveredText(text).toString()).toList());
  }

  /** Custom units defer to currency amounts, and the extractor copies the unit set. */
  @Test
  void testCustomUnitMoneyPrecedence() {
    final Set<String> units = new HashSet<>(Set.of("m", "USD", "bbl"));
    final CursorQuantityExtractor extractor = new CursorQuantityExtractor(units);
    units.clear();
    final String text = "$3m; 3m USD; 3 USD; 4m; 5bbl";
    final List<Quantity> quantities = extractor.extract(text);
    Assertions.assertEquals(List.of("4m", "5bbl"), quantities.stream()
        .map(quantity -> quantity.span().getCoveredText(text).toString()).toList());
    Assertions.assertThrows(UnsupportedOperationException.class, quantities::clear);
    Assertions.assertEquals(List.of(), extractor.extract("$3m"));
    Assertions.assertEquals(List.of(new Quantity(new Span(0, 2), BigDecimal.valueOf(6), "m")),
        extractor.extract("6m"));
  }

  /**
   * Concurrent calls use independent currency exclusion spans and quantity results.
   *
   * @throws Exception If a worker fails or is interrupted.
   */
  @Test
  void testConcurrentExtraction() throws Exception {
    final CursorQuantityExtractor extractor = new CursorQuantityExtractor();
    final List<Callable<Void>> tasks = new ArrayList<>();
    for (int i = 1; i <= 200; i++) {
      final int value = i;
      tasks.add(() -> {
        final String digits = Integer.toString(value);
        final String text = "$3m; " + digits + "kg; USD 2m; 1e-3kg";
        Assertions.assertEquals(List.of(new Quantity(new Span(5, 7 + digits.length()),
            BigDecimal.valueOf(value), "kg")), extractor.extract(new StringBuilder(text)));
        return null;
      });
    }
    try (var executor = Executors.newFixedThreadPool(4)) {
      for (var result : executor.invokeAll(tasks)) {
        result.get();
      }
    }
  }
}
