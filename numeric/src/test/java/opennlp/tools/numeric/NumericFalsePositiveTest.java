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

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import opennlp.tools.extraction.NumberNotation;
import opennlp.tools.money.CursorMoneyExtractor;
import opennlp.tools.money.MoneyAmount;
import opennlp.tools.money.MoneyExtractor;
import opennlp.tools.quantity.CursorQuantityExtractor;
import opennlp.tools.quantity.Quantity;
import opennlp.tools.quantity.QuantityExtractor;
import opennlp.tools.temporal.CursorTemporalExtractor;
import opennlp.tools.temporal.TemporalExpression;
import opennlp.tools.temporal.TemporalExtractor;

/**
 * Holds the numeric extractors to a corpus of near misses: text that looks numeric, and in
 * several cases is numeric, but names no money amount, no measured quantity, and no calendar
 * date. Every fixture must stay unmatched by all four extractor configurations at once, in
 * both number notations.
 *
 * <p>Precision is what decides whether a numeric layer can be trusted by whatever reads it
 * downstream, and unit tests written next to a scanner are worst at defending it: they are
 * written by whoever just decided what the scanner accepts. This corpus is the counterweight,
 * collecting the shapes that are near misses on purpose: identifiers that group like amounts,
 * version numbers, groupings from conventions the scanners do not model, ordinary nouns after
 * a number, and relative expressions with nothing to resolve against.</p>
 */
public class NumericFalsePositiveTest {

  private static final MoneyExtractor MONEY_US = new CursorMoneyExtractor();
  private static final MoneyExtractor MONEY_EU =
      new CursorMoneyExtractor(NumberNotation.LATIN_EU);
  private static final QuantityExtractor QUANTITY_US = new CursorQuantityExtractor();
  private static final QuantityExtractor QUANTITY_EU =
      new CursorQuantityExtractor(NumberNotation.LATIN_EU);
  private static final TemporalExtractor TEMPORAL = new CursorTemporalExtractor();

  /**
   * The fixtures that must stay unmatched everywhere. Grouped by what makes each one a near
   * miss, so a future reader can tell an accident from a decision.
   */
  private static final String[] UNIVERSAL = {
      // identifiers and codes that group like amounts
      "order 4,50,000 shipped",
      "order 12345678 confirmed",
      "call 555,1234 now",
      "PIN 1234 was reset",
      "the ticket is 1,00,000",
      "extension 2,204",
      // version numbers
      "invoice 2.5.1 attached",
      "upgraded to 2.5.1",
      "release 10.15.7 notes",
      "1.2.3.4 is the address",
      "$1.2.3 is malformed",
      "1,2,3 euros is malformed",
      // letter-prefixed currency symbols the single-code-point table cannot tell apart
      "HK$50 in Kowloon",
      "US$50 bid",
      "NZ$50 offer",
      "R$50 receipt",
      // bare numbers and ordinary nouns after a number
      "the 100 things",
      "chapter 7 of 12",
      "5 apples a day",
      "12 items in the cart",
      "5 in the morning",
      // currency subunits and words naming several currencies, deliberately not money
      "10 cents",
      "3 pence",
      "500 pesos",
      "20 francs",
      "3 won the raffle",
      // units that are not units, and words that only begin like a currency word
      "50kmh top speed",
      "50 gb of it",
      "50 dollarsx",
      "50 euro-cent coins",
      // impossible or unsupported calendar shapes
      "2026-13-45",
      "February 30, 2026",
      "Q5 2024",
      "the 3000 year plan",
      "07/14/2026",
      // relative expressions with no reference date to resolve against
      "we shipped it yesterday",
      "audited 3 days ago",
      "due in 2 weeks",
      "reviewed last month",
      "renewing next quarter"
  };

  private static List<String> universal() {
    return List.of(UNIVERSAL);
  }

  /**
   * Asserts that a fixture yields no mention in any configuration, naming the offender when
   * it does.
   *
   * @param fixture The text that must stay unmatched. Must not be {@code null}.
   */
  private static void assertNothingFound(String fixture) {
    for (final MoneyExtractor money : List.of(MONEY_US, MONEY_EU)) {
      final List<MoneyAmount> mentions = money.extract(fixture);
      Assertions.assertEquals(List.of(), mentions,
          () -> "money false positive in [" + fixture + "]");
    }
    for (final QuantityExtractor quantity : List.of(QUANTITY_US, QUANTITY_EU)) {
      final List<Quantity> mentions = quantity.extract(fixture);
      Assertions.assertEquals(List.of(), mentions,
          () -> "quantity false positive in [" + fixture + "]");
    }
    final List<TemporalExpression> temporals = TEMPORAL.extract(fixture);
    Assertions.assertEquals(List.of(), temporals,
        () -> "temporal false positive in [" + fixture + "]");
  }

  @ParameterizedTest
  @MethodSource("universal")
  void testNearMissYieldsNothingInAnyConfiguration(String fixture) {
    assertNothingFound(fixture);
  }

  /**
   * Verifies that the fixtures stay unmatched when they are read as one text rather than one
   * at a time, so a mention cannot appear out of the seam between two of them.
   */
  @Test
  void testJoinedCorpusYieldsNothing() {
    assertNothingFound(String.join(" | ", UNIVERSAL));
  }

  /**
   * Verifies the notation-specific near misses: an amount written in one convention is a
   * false positive risk only under the other one, where it must yield nothing rather than a
   * value off by a factor of a thousand.
   */
  @ParameterizedTest
  @CsvSource(delimiter = ';', value = {
      "the invoice says 1.234,56 EUR; LATIN_US",
      "the invoice says 12,34 EUR; LATIN_US",
      "\u20AC1.234,56 total; LATIN_US",
      "the invoice says 1,234.56 USD; LATIN_EU",
      "$1,234.56 total; LATIN_EU",
      "1,00,000 USD; LATIN_EU"
  })
  void testAmountInTheOtherNotationYieldsNothing(String fixture, NumberNotation notation) {
    Assertions.assertEquals(List.of(),
        new CursorMoneyExtractor(notation).extract(fixture), fixture);
  }

  /**
   * Verifies the same for quantities, including the Indian grouping, which neither notation
   * models and which must therefore be silent in both.
   */
  @ParameterizedTest
  @CsvSource(delimiter = ';', value = {
      "capacity 1.234,5 kg; LATIN_US",
      "capacity 12,34 kg; LATIN_US",
      "capacity 1,234.5 kg; LATIN_EU",
      "capacity 1,00,000 kg; LATIN_EU",
      "capacity 1,00,000 kg; LATIN_US"
  })
  void testQuantityInTheOtherNotationYieldsNothing(String fixture, NumberNotation notation) {
    Assertions.assertEquals(List.of(),
        new CursorQuantityExtractor(notation).extract(fixture), fixture);
  }

  /**
   * Verifies that the corpus is not silent because the extractors are: the same
   * configurations find the mentions in a control text that does name an amount, a quantity,
   * and a date. Without this, a scanner that stopped working would pass the corpus.
   */
  @Test
  void testControlTextIsStillMatched() {
    final String control = "Berlin, 14 July 2026. The buyer paid $2,400,000 for 1,250 GB.";

    Assertions.assertEquals(1, MONEY_US.extract(control).size());
    Assertions.assertEquals(1, QUANTITY_US.extract(control).size());
    Assertions.assertEquals(1, TEMPORAL.extract(control).size());
    Assertions.assertEquals(1,
        MONEY_EU.extract("Berlin. Der Preis war 2.400.000 EUR.").size());
    Assertions.assertEquals(1,
        QUANTITY_EU.extract("Kapazitat 1.250,5 GB gemessen.").size());
  }
}
