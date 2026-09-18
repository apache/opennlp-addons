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

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.document.Document;
import opennlp.tools.util.Span;

/** Checks the UnionPay 81 prefix with synthetic, checksum-valid card numbers. */
class CardPrefixTest {

  private static final List<String> NUMBERS = List.of(
      "8100000000026", "81000000000028", "810000000000026", "8100000000000028",
      "81000000000000026", "810000000000000028", "8100000000000000026");

  private static final String EXAMPLE = "8100000000000028";
  private static final String FORMATTED_EXAMPLE = "8100 0000 0000 0028";

  private final PiiExtractor extractor = new CursorPiiExtractor(Set.of(PiiMention.TYPE_CARD));

  /**
   * Provides compact cards and single-separator forms, including a split prefix.
   *
   * @return The original and formatted numbers, with their preceding text.
   */
  private static Stream<Arguments> formats() {
    return NUMBERS.stream().flatMap(number -> Stream.of(number,
        PiiTestSupport.grouped(number, 4, ' '), PiiTestSupport.grouped(number, 4, '-'),
        PiiTestSupport.grouped(number, 1, ' '), PiiTestSupport.grouped(number, 1, '-'),
        "8-" + number.substring(1)).flatMap(formatted -> Stream.of("", "😀 Card: ")
            .map(prefix -> Arguments.of(number, formatted, prefix))));
  }

  /**
   * Checks complete spans, normalization, masking and replacement across card lengths.
   *
   * @param number The compact number.
   * @param formatted The original card text.
   * @param prefix The preceding text.
   */
  @ParameterizedTest
  @MethodSource("formats")
  void testUnionPayPrefix(String number, String formatted, String prefix) {
    final String text = prefix + formatted + ".";
    final List<PiiMention> expected = List.of(new PiiMention(
        new Span(prefix.length(), prefix.length() + formatted.length()),
        PiiMention.TYPE_CARD, number));

    Assertions.assertEquals(expected, extractor.extract(text));
    Assertions.assertEquals(expected, extractor.extract(new StringBuilder(text)));
    final Document document = new PiiAnnotator(extractor).annotate(Document.of(text));
    Assertions.assertEquals(prefix + "*".repeat(formatted.length()) + ".",
        Masker.mask(document, PiiAnnotator.PII, '*'));
    Assertions.assertEquals(prefix + "CARD-1.", new Pseudonymizer().rewrite(document).text());
  }

  /**
   * Provides all incorrect check digits for each accepted length.
   *
   * @return The compact numbers with one changed check digit.
   */
  private static Stream<String> incorrectChecks() {
    return NUMBERS.stream().flatMap(number -> IntStream.range(0, 10)
        .filter(digit -> digit != number.charAt(number.length() - 1) - '0')
        .mapToObj(digit -> number.substring(0, number.length() - 1) + digit));
  }

  /**
   * Checks that prefix support does not bypass the Luhn check.
   *
   * @param number The checksum-invalid compact number.
   */
  @ParameterizedTest
  @MethodSource("incorrectChecks")
  void testInvalidCheckDigit(String number) {
    Assertions.assertTrue(extractor.extract(number).isEmpty());
  }

  /**
   * Checks complete digit runs just outside the accepted 13-to-19-digit range.
   *
   * @param number The checksum-valid number of an unsupported length.
   */
  @ParameterizedTest
  @ValueSource(strings = {"810000000028", "81000000000000000028"})
  void testInvalidLength(String number) {
    Assertions.assertTrue(Luhn.valid(number, number.length()));
    Assertions.assertTrue(extractor.extract(number).isEmpty());
  }

  /**
   * Checks that other leading 8 prefixes remain excluded even with a valid checksum.
   *
   * @param digit The digit following 8.
   * @param check The check digit for the resulting 16-digit candidate.
   */
  @ParameterizedTest
  @CsvSource({"0,9", "2,7", "3,6", "4,5", "5,4", "6,3", "7,2", "8,1", "9,0"})
  void testOtherLeadingEightPrefixes(int digit, int check) {
    final String number = "8" + digit + "0".repeat(12) + "2" + check;
    Assertions.assertTrue(Luhn.valid(number, number.length()));
    Assertions.assertTrue(extractor.extract(number).isEmpty());
    Assertions.assertTrue(extractor.extract(PiiTestSupport.grouped(number, 4, ' ')).isEmpty());
  }

  /**
   * Checks the existing leading-digit range with independently calculated check digits.
   *
   * @param first The leading digit.
   * @param check The check digit for a 16-digit candidate.
   * @param accepted Whether this leading digit is accepted.
   */
  @ParameterizedTest
  @CsvSource({"0,5,false", "1,3,false", "2,1,true", "3,9,true", "4,7,true",
      "5,4,true", "6,2,true", "7,0,false", "9,6,false"})
  void testOtherLeadingDigits(int first, int check, boolean accepted) {
    final String number = first + "1" + "0".repeat(12) + "2" + check;
    Assertions.assertTrue(Luhn.valid(number, number.length()));
    final List<PiiMention> expected = accepted
        ? List.of(new PiiMention(new Span(0, number.length()), PiiMention.TYPE_CARD, number))
        : List.of();
    Assertions.assertEquals(expected, extractor.extract(number));
  }

  /**
   * Checks malformed separators and non-ASCII digits within the new prefix.
   *
   * @param prefix The rejected prefix before the remaining digits.
   */
  @ParameterizedTest
  @ValueSource(strings = {"8", "8  1", "8--1", "8 -1", "8\t1", "8\n1", "8.1",
      "8,1", "8\u00a01", "8\u0661", "\uff181", "81x"})
  void testMalformedPrefix(String prefix) {
    Assertions.assertTrue(extractor.extract(prefix + EXAMPLE.substring(2)).isEmpty());
  }

  /**
   * Checks Unicode word boundaries around a compact number and a separated control.
   *
   * @param neighbor A letter or digit that continues a word.
   */
  @ParameterizedTest
  @ValueSource(strings = {"a", "9", "é", "\u0661", "\uD801\uDC28", "\uD835\uDFD9"})
  void testWordBoundaries(String neighbor) {
    Assertions.assertTrue(extractor.extract(neighbor + EXAMPLE).isEmpty());
    Assertions.assertTrue(extractor.extract(EXAMPLE + neighbor).isEmpty());
    final String text = neighbor + " " + EXAMPLE + " " + neighbor;
    Assertions.assertEquals(List.of(new PiiMention(
        new Span(neighbor.length() + 1, neighbor.length() + 1 + EXAMPLE.length()),
        PiiMention.TYPE_CARD, EXAMPLE)), extractor.extract(text));
  }

  /**
   * Checks prefix recovery before a separated digit run beyond the card-length limit.
   *
   * @param separator The character before the extra group.
   */
  @ParameterizedTest
  @ValueSource(strings = {" ", "-"})
  void testTrailingGroup(String separator) {
    final String text = FORMATTED_EXAMPLE + separator + "12345678901234567890";
    Assertions.assertEquals(List.of(new PiiMention(new Span(0, FORMATTED_EXAMPLE.length()),
        PiiMention.TYPE_CARD, EXAMPLE)), extractor.extract(text));
  }

  /**
   * Checks that a neighboring accepted card remains a separate detection.
   *
   * @param separator The separator between the cards.
   */
  @ParameterizedTest
  @ValueSource(strings = {" ", ";", "\n", " | "})
  void testTwoCards(String separator) {
    final String other = "4111111111111111";
    final String text = EXAMPLE + separator + other;
    Assertions.assertEquals(List.of(
        new PiiMention(new Span(0, EXAMPLE.length()), PiiMention.TYPE_CARD, EXAMPLE),
        new PiiMention(new Span(EXAMPLE.length() + separator.length(), text.length()),
            PiiMention.TYPE_CARD, other)), extractor.extract(text));
  }

  /** Checks that the default extractor and both payment-containing packs find the card. */
  @Test
  void testPackIntegration() {
    final List<PiiMention> expected = List.of(new PiiMention(new Span(0, EXAMPLE.length()),
        PiiMention.TYPE_CARD, EXAMPLE));
    Assertions.assertEquals(expected, new CursorPiiExtractor().extract(EXAMPLE));
    Assertions.assertEquals(expected, PiiPacks.payment().extract(EXAMPLE));
    Assertions.assertEquals(expected, PiiPacks.allStructured().extract(EXAMPLE));
  }

  /** Checks the manual's masked receipt without exposing the complete card number. */
  @Test
  void testManualExample() {
    final Document cards = new PiiAnnotator(PiiPacks.payment())
        .annotate(Document.of("Card " + FORMATTED_EXAMPLE + "."));
    Assertions.assertEquals(1, cards.get(PiiAnnotator.PII).size());
    Assertions.assertEquals(EXAMPLE, cards.get(PiiAnnotator.PII).getFirst().value().normalized());
    Assertions.assertEquals("Card **** **** **** 0028.",
        Masker.mask(cards, PiiAnnotator.PII, MaskPolicies.byType()));
  }
}
