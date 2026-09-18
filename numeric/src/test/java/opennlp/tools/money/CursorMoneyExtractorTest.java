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

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.extraction.NumberNotation;
import opennlp.tools.util.Span;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the recognized money shapes, the normalization of grouping and scale markers,
 * and the reject cases that keep bare numbers and near-misses out of the money layer.
 */
public class CursorMoneyExtractorTest {

  private final CursorMoneyExtractor extractor = new CursorMoneyExtractor();

  private MoneyAmount single(String text) {
    final List<MoneyAmount> mentions = extractor.extract(text);
    assertEquals(1, mentions.size(), "expected one mention in: " + text);
    return mentions.get(0);
  }

  @ParameterizedTest
  @CsvSource(delimiter = ';', value = {
      "$1,234.56; 1234.56; USD",
      "$1,234,567.89; 1234567.89; USD",
      "$ 100; 100; USD",
      "\u20AC50; 50; EUR",      // U+20AC, the euro sign
      "50\u20AC; 50; EUR",
      "\u00A32.5k; 2500.0; GBP",   // U+00A3, the pound sign
      "\u00A51000; 1000; JPY",     // U+00A5, the yen sign
      "USD 100; 100; USD",
      "100 USD; 100; USD",
      "$1.2M; 1200000.0; USD",
      "$3bn; 3000000000; USD",
      "$3 billion; 3000000000; USD",
      "USD 1.2 million; 1200000.0; USD",
      "1.2 million USD; 1200000.0; USD",
      "-$5; -5; USD",
      "-5 USD; -5; USD",
      "$0.00; 0.00; USD",
      "CHF 42; 42; CHF"
  })
  void testRecognizedShapesCoverTheFullMention(String text, String amount, String currency) {
    final MoneyAmount mention = single(text);
    assertEquals(new Span(0, text.length()), mention.span(), text);
    assertEquals(0, new BigDecimal(amount).compareTo(mention.amount()), text);
    assertEquals(currency, mention.currency(), text);
  }

  @Test
  void testMentionInsideSentenceHasExactSpan() {
    final MoneyAmount mention = single("they paid $1.2M for it.");
    assertEquals(new Span(10, 15), mention.span());
    assertEquals(0, new BigDecimal("1200000").compareTo(mention.amount()));
  }

  /**
   * Verifies that a known symbol directly preceded by an ASCII letter is read as part
   * of a longer, multi-character symbol the table does not know and yields no mention:
   * {@code HK$50} must not report a wrong 50 USD. The table holds single code points
   * only, so it cannot tell {@code US$}, which happens to denote the default currency
   * of {@code $}, from {@code HK$}, which does not; {@code US$} is therefore rejected
   * as well rather than matched by accident.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "HK$50",               // Hong Kong dollar
      "NZ$50",               // New Zealand dollar
      "R$50",                // Brazilian real
      "US$50"                // rejected too: the table knows no multi-character symbols
  })
  void testSymbolAfterLetterIsALongerSymbolAndYieldsNoMention(String text) {
    assertTrue(extractor.extract(text).isEmpty(), text);
  }

  /**
   * Verifies that the bare symbol keeps matching at ordinary left contexts while the
   * letter guard is in place: at the text start and after a space.
   */
  @Test
  void testBareSymbolStillMatchesAtOrdinaryBoundaries() {
    assertEquals("USD", single("$50").currency());
    assertEquals(new Span(4, 7), single("pay $50").span());
  }

  @Test
  void testMultipleMentions() {
    final List<MoneyAmount> mentions = extractor.extract("paid $5 and \u20AC3 today");
    assertEquals(2, mentions.size());
    assertEquals("USD", mentions.get(0).currency());
    assertEquals("EUR", mentions.get(1).currency());
  }

  /**
   * Verifies that the hyphen in a price range is not read as a minus sign: both
   * amounts of {@code $100-$200} are positive and their spans exclude the hyphen.
   */
  @Test
  void testHyphenatedRangeYieldsTwoPositiveMentions() {
    final List<MoneyAmount> mentions = extractor.extract("$100-$200");
    assertEquals(2, mentions.size());
    assertEquals(new Span(0, 4), mentions.get(0).span());
    assertEquals(0, new BigDecimal("100").compareTo(mentions.get(0).amount()));
    assertEquals(new Span(5, 9), mentions.get(1).span());
    assertEquals(0, new BigDecimal("200").compareTo(mentions.get(1).amount()));
  }

  /**
   * Verifies that the hyphen in a range of symbol-suffix mentions is not read as a
   * minus sign: both amounts of {@code 50\u20AC-60\u20AC} (euro signs) are positive,
   * although the code point before the hyphen is a currency symbol rather than a
   * digit.
   */
  @Test
  void testHyphenatedSymbolSuffixRangeYieldsTwoPositiveMentions() {
    final List<MoneyAmount> mentions = extractor.extract("50\u20AC-60\u20AC");
    assertEquals(2, mentions.size());
    assertEquals(new Span(0, 3), mentions.get(0).span());
    assertEquals(0, new BigDecimal("50").compareTo(mentions.get(0).amount()));
    assertEquals("EUR", mentions.get(0).currency());
    assertEquals(new Span(4, 7), mentions.get(1).span());
    assertEquals(0, new BigDecimal("60").compareTo(mentions.get(1).amount()));
    assertEquals("EUR", mentions.get(1).currency());
  }

  /**
   * Verifies that a hyphen after a symbol-suffix mention never negates a following
   * symbol-first mention either: {@code 50\u20AC-$60} (euro sign) reports two positive
   * amounts in their own currencies.
   */
  @Test
  void testHyphenBetweenSuffixAndPrefixMentionsIsNotAMinus() {
    final List<MoneyAmount> mentions = extractor.extract("50\u20AC-$60");
    assertEquals(2, mentions.size());
    assertEquals(0, new BigDecimal("50").compareTo(mentions.get(0).amount()));
    assertEquals("EUR", mentions.get(0).currency());
    assertEquals(new Span(4, 7), mentions.get(1).span());
    assertEquals(0, new BigDecimal("60").compareTo(mentions.get(1).amount()));
    assertEquals("USD", mentions.get(1).currency());
  }

  /**
   * Verifies that a hyphen directly after a letter is ordinary prose punctuation, not
   * a minus sign, so {@code pre-$5 deal} reports a positive amount.
   */
  @Test
  void testHyphenAfterLetterIsNotAMinus() {
    final MoneyAmount mention = single("pre-$5 deal");
    assertEquals(new Span(4, 6), mention.span());
    assertEquals(0, new BigDecimal("5").compareTo(mention.amount()));
  }

  /**
   * Verifies that a genuinely negative symbol-first mention keeps its sign at every
   * boundary the number-first shape accepts: the text start, after whitespace, and
   * after non-alphanumeric punctuation.
   */
  @Test
  void testNegativeSymbolFirstAtBoundariesKeepsTheSign() {
    final MoneyAmount atStart = single("-$5");
    assertEquals(new Span(0, 3), atStart.span());
    assertEquals(0, new BigDecimal("-5").compareTo(atStart.amount()));

    final MoneyAmount afterSpace = single("balance -$5");
    assertEquals(new Span(8, 11), afterSpace.span());
    assertEquals(0, new BigDecimal("-5").compareTo(afterSpace.amount()));

    final MoneyAmount afterParenthesis = single("(-$5)");
    assertEquals(new Span(1, 4), afterParenthesis.span());
    assertEquals(0, new BigDecimal("-5").compareTo(afterParenthesis.amount()));
  }

  /**
   * Verifies that an amount grouped in a convention the scanner cannot parse is
   * rejected entirely rather than truncated to a wildly wrong value: the
   * Indian-grouped {@code \u20B91,00,000} (rupee sign) must not report 1 INR, and
   * {@code 1,00,000 USD} must not report 0 USD off a restarted scan over the tail
   * {@code 000}.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "\u20B91,00,000",       // symbol first, Indian grouping
      "1,00,000 USD",         // number first, Indian grouping
      "USD 1,00,000",         // code first, Indian grouping
      "$1,23",                // short trailing group
      "\u20AC1.234,56"        // European decimal comma after a dot-read decimal
  })
  void testGroupingTheScannerCannotParseYieldsNoMention(String text) {
    assertTrue(extractor.extract(text).isEmpty(), text);
  }

  /**
   * Verifies that a comma not followed by a digit is ordinary punctuation behind the
   * amount: {@code $5, sure} keeps its mention and the span ends before the comma.
   */
  @Test
  void testTrailingCommaWithoutDigitStillEndsTheMatch() {
    final MoneyAmount mention = single("$5, sure");
    assertEquals(new Span(0, 2), mention.span());
    assertEquals(0, new BigDecimal("5").compareTo(mention.amount()));
  }

  /**
   * Verifies the exact spans of a mention at the very start and at the very end of the
   * text, where the boundary checks run against the text bounds.
   */
  @Test
  void testMentionAtTextStartAndEnd() {
    assertEquals(new Span(0, 2), single("$5 up front").span());
    assertEquals(new Span(11, 13), single("the fee is $7").span());
  }

  @Test
  void testTrailingPunctuationIsNotIncluded() {
    final MoneyAmount mention = single("it cost $3.20.");
    assertEquals("$3.20", mention.span().getCoveredText("it cost $3.20.").toString());
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "5%",                  // percent is not money
      "the 100 things",      // bare number
      "USD alone",           // code without a number
      "$x",                  // symbol without a number
      "$5x",                 // invalid letter suffix invalidates the match
      "100 USDX",            // not an ISO code
      "chapter USD",         // no number after the code
      "USD -5",              // the code-first shape takes no leading minus
      "call 555,1234 now"    // invalid grouping, no currency
  })
  void testRejectedShapes(String text) {
    assertTrue(extractor.extract(text).isEmpty(), text);
  }

  /**
   * Verifies the spelled-out currency words: the word follows the amount and any scale
   * marker, and the mention covers the whole phrase including the word.
   */
  @ParameterizedTest
  @CsvSource(delimiter = ';', value = {
      "50 dollars; 50; USD",
      "1 dollar; 1; USD",
      "1.2 million dollars; 1200000.0; USD",
      "50 euros; 50; EUR",
      "1 euro; 1; EUR",
      "3 billion pounds; 3000000000; GBP",
      "40 million sterling; 40000000; GBP",
      "1,200 yen; 1200; JPY",
      "500 rupees; 500; INR",
      "3.5k dollars; 3500.0; USD",
      "-20 dollars; -20; USD",
      "12 trillion yuan; 12000000000000; CNY",
      "80 renminbi; 80; CNY",
      "300 roubles; 300; RUB",
      "25 shekels; 25; ILS",
      "900 baht; 900; THB"
  })
  void testSpelledOutCurrencyWordsCoverTheFullMention(String text, String amount,
      String currency) {
    final MoneyAmount mention = single(text);
    assertEquals(new Span(0, text.length()), mention.span(), text);
    assertEquals(0, new BigDecimal(amount).compareTo(mention.amount()), text);
    assertEquals(currency, mention.currency(), text);
  }

  @Test
  void testCurrencyWordIsMatchedCaseInsensitively() {
    assertEquals("USD", single("50 Dollars").currency());
    assertEquals("EUR", single("50 EUROS").currency());
  }

  @Test
  void testCurrencyWordMentionInsideSentenceHasExactSpan() {
    final MoneyAmount mention = single("the fund raised 1.2 million dollars last year");
    assertEquals(new Span(16, 35), mention.span());
    assertEquals(0, new BigDecimal("1200000").compareTo(mention.amount()));
  }

  @Test
  void testCurrencyWordInTheEuropeanNotation() {
    final CursorMoneyExtractor european = new CursorMoneyExtractor(NumberNotation.LATIN_EU);
    final List<MoneyAmount> mentions = european.extract("1.234,56 euros");
    assertEquals(1, mentions.size());
    assertEquals(0, new BigDecimal("1234.56").compareTo(mentions.get(0).amount()));
    assertEquals("EUR", mentions.get(0).currency());
  }

  /**
   * Verifies that the word table stays a table of currencies: an ordinary noun after a
   * number is not money, a currency subunit is not the currency, a word naming several
   * currencies at once is not guessed at, and a word that only begins like a currency
   * word does not match. The everyday English verb {@code won} is excluded for the same
   * reason, although it does name a currency.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "5 apples",
      "10 cents",
      "3 pence",
      "12 items",
      "500 pesos",              // names several currencies
      "20 francs",              // names several currencies
      "75 kronor",              // names several currencies
      "3 won",                  // an everyday English verb
      "50 dollarsx",            // the word runs into more letters
      "50 dollar5",             // the word runs into a digit
      "50 euro-cent",           // a subunit, not the currency
      "dollars 50",             // the word does not precede the amount
      "50  dollars"             // two spaces, not the single separator
  })
  void testWordsThatAreNotCurrenciesYieldNoMention(String text) {
    assertTrue(extractor.extract(text).isEmpty(), text);
  }

  /**
   * Verifies the European notation end to end: dots group digits and a comma marks the
   * fraction, so a German amount is read at its real magnitude rather than as a value a
   * thousand times too small.
   */
  @ParameterizedTest
  @CsvSource(delimiter = ';', value = {
      "1.234,56 EUR; 1234.56; EUR",
      "1.234.567,89 EUR; 1234567.89; EUR",
      "\u20AC1.234,56; 1234.56; EUR",     // U+20AC, the euro sign
      "\u20AC50,25; 50.25; EUR",
      "\u20AC1.500; 1500; EUR",
      "EUR 2.400.000; 2400000; EUR",
      "-\u20AC5,50; -5.50; EUR",
      "\u20AC2,5 million; 2500000.0; EUR",
      "1.250,5 CHF; 1250.5; CHF"
  })
  void testEuropeanNotationShapesCoverTheFullMention(String text, String amount,
      String currency) {
    final CursorMoneyExtractor european = new CursorMoneyExtractor(NumberNotation.LATIN_EU);
    final List<MoneyAmount> mentions = european.extract(text);

    assertEquals(1, mentions.size(), "expected one mention in: " + text);
    assertEquals(new Span(0, text.length()), mentions.get(0).span(), text);
    assertEquals(0, new BigDecimal(amount).compareTo(mentions.get(0).amount()), text);
    assertEquals(currency, mentions.get(0).currency(), text);
  }

  /**
   * Verifies that an amount written in one notation yields no mention under the other:
   * a wrong magnitude in a money layer is worse than a missing one, so the mismatch is
   * fail-closed in both directions.
   */
  @ParameterizedTest
  @CsvSource(delimiter = ';', value = {
      "$1,234.56; LATIN_EU",
      "1,234.56 USD; LATIN_EU",
      "USD 1,234.56; LATIN_EU",
      "1,234,567.89 USD; LATIN_EU",
      "\u20AC1.234,56; LATIN_US",
      "1.234,56 EUR; LATIN_US",
      "EUR 1.234,56; LATIN_US",
      "1.234.567,89 EUR; LATIN_US"
  })
  void testAmountInTheOtherNotationYieldsNoMention(String text, NumberNotation notation) {
    assertTrue(new CursorMoneyExtractor(notation).extract(text).isEmpty(), text);
  }

  /**
   * Verifies the sharpest case of the notation choice: an amount whose text is valid in
   * both conventions means a thousand times more in one of them, and each extractor
   * reports the value its own notation implies.
   */
  @Test
  void testAmountValidInBothNotationsMeansWhatTheNotationSays() {
    final CursorMoneyExtractor european = new CursorMoneyExtractor(NumberNotation.LATIN_EU);
    assertEquals(0, new BigDecimal("1500")
        .compareTo(european.extract("1.500 EUR").get(0).amount()));
    assertEquals(0, new BigDecimal("1.500")
        .compareTo(extractor.extract("1.500 EUR").get(0).amount()));
  }

  /**
   * Verifies that a region resolves the notation as well as the symbol: a German document
   * is read in the European notation, and an Australian one keeps the notation its region
   * writes while resolving the dollar sign to its own currency.
   */
  @Test
  void testForRegionResolvesTheNotationOfTheRegion() {
    final CursorMoneyExtractor german = CursorMoneyExtractor.forRegion(Locale.GERMANY);
    assertEquals(0, new BigDecimal("1234.56")
        .compareTo(german.extract("1.234,56 EUR").get(0).amount()));
    assertTrue(german.extract("$1,234.56").isEmpty());

    final CursorMoneyExtractor french = CursorMoneyExtractor.forRegion(Locale.FRANCE);
    assertEquals(0, new BigDecimal("50.25")
        .compareTo(french.extract("50,25 EUR").get(0).amount()));

    final CursorMoneyExtractor australian = CursorMoneyExtractor.forRegion(Locale.of("en", "AU"));
    final MoneyAmount australianAmount = australian.extract("$1,234.56").get(0);
    assertEquals(0, new BigDecimal("1234.56").compareTo(australianAmount.amount()));
    assertEquals("AUD", australianAmount.currency());
  }

  @Test
  void testNotationValidation() {
    assertThrows(IllegalArgumentException.class,
        () -> new CursorMoneyExtractor((NumberNotation) null));
    assertThrows(IllegalArgumentException.class,
        () -> new CursorMoneyExtractor(Map.of((int) '$', "USD"), null));
  }

  @Test
  void testCustomSymbolTable() {
    final CursorMoneyExtractor canadian =
        new CursorMoneyExtractor(Map.of((int) '$', "CAD"));
    assertEquals("CAD", canadian.extract("$5").get(0).currency());
  }

  /** Verifies that constructor sorting keeps every symbol aligned with its currency. */
  @Test
  void testCustomSymbolTableKeepsCodesAlignedAfterSorting() {
    final Map<Integer, String> symbols = new HashMap<>();
    symbols.put(0x20B9, "INR");
    symbols.put((int) '$', "CAD");
    symbols.put(0x20AC, "EUR");
    symbols.put(0x00A3, "GBP");
    final CursorMoneyExtractor custom = new CursorMoneyExtractor(symbols);

    final List<MoneyAmount> mentions = custom.extract("$1 \u00A32 \u20AC3 \u20B94");

    assertEquals(List.of("CAD", "GBP", "EUR", "INR"),
        mentions.stream().map(MoneyAmount::currency).toList());
  }

  @Test
  void testCustomSymbolTableValidation() {
    assertThrows(IllegalArgumentException.class,
        () -> new CursorMoneyExtractor((Map<Integer, String>) null));
    assertThrows(IllegalArgumentException.class, () -> new CursorMoneyExtractor(Map.of()));
    assertThrows(IllegalArgumentException.class,
        () -> new CursorMoneyExtractor(Map.of((int) '$', "DOLLARS")));
    assertThrows(IllegalArgumentException.class,
        () -> new CursorMoneyExtractor(Map.of(-1, "USD")));
    assertThrows(IllegalArgumentException.class,
        () -> new CursorMoneyExtractor(Map.of(Character.MAX_CODE_POINT + 1, "USD")));
    assertThrows(IllegalArgumentException.class,
        () -> new CursorMoneyExtractor(Map.of((int) 'A', "USD")));
    final Map<Integer, String> nullCodePoint = new HashMap<>();
    nullCodePoint.put(null, "USD");
    assertThrows(IllegalArgumentException.class,
        () -> new CursorMoneyExtractor(nullCodePoint));
  }

  /** Verifies that malformed repeated-decimal amounts fail closed in every position. */
  @ParameterizedTest
  @ValueSource(strings = {
      "$1.2.3",
      "USD 1.2.3",
      "1.2.3 USD"
  })
  void testRepeatedDecimalSeparatorYieldsNoMention(String text) {
    assertTrue(extractor.extract(text).isEmpty(), text);
  }

  /** Verifies that a valid locale with no currency fails with the public API exception. */
  @Test
  void testRegionWithoutACurrencyFailsAtTheFactoryBoundary() {
    assertThrows(IllegalArgumentException.class,
        () -> CursorMoneyExtractor.forRegion(Locale.of("", "AQ")));
  }

  /**
   * Verifies the scan around supplementary-plane neighbors: a code point outside the
   * basic multilingual plane before a mention is an ordinary boundary crossed without
   * splitting its surrogate pair, while a supplementary-plane digit directly before
   * the number forbids a match exactly as an ASCII digit would.
   */
  @Test
  void testSupplementaryPlaneNeighbors() {
    // U+1F4B0 (money bag) takes two chars; the mention span starts behind it
    final MoneyAmount mention = single("\uD83D\uDCB0$5");
    assertEquals(new Span(2, 4), mention.span());
    assertEquals(0, new BigDecimal("5").compareTo(mention.amount()));

    // U+1D7D9 (mathematical double-struck one) is a digit, so no number starts after it
    assertTrue(extractor.extract("\uD835\uDFD95 USD").isEmpty());
  }

  @Test
  void testNullTextThrows() {
    assertThrows(IllegalArgumentException.class, () -> extractor.extract(null));
  }

  @Test
  void testMoneyAmountValidation() {
    assertThrows(IllegalArgumentException.class,
        () -> new MoneyAmount(null, BigDecimal.ONE, "USD"));
    assertThrows(IllegalArgumentException.class,
        () -> new MoneyAmount(new Span(0, 1), null, "USD"));
    assertThrows(IllegalArgumentException.class,
        () -> new MoneyAmount(new Span(0, 1), BigDecimal.ONE, " "));
  }
}
