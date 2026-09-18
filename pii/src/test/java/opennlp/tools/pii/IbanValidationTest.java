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

import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
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

/** Checks country lengths and MOD 97 with synthetic candidates, not bank-issued accounts. */
class IbanValidationTest {

  /**
   * A country and the registered number of characters in an IBAN.
   *
   * @param code The country code.
   * @param length The complete IBAN length.
   */
  private record Country(String code, int length) {
  }

  private static final List<Country> COUNTRIES = List.of(
      new Country("NO", 15), new Country("DE", 22), new Country("GB", 22),
      new Country("MT", 31), new Country("LC", 32), new Country("RU", 33));

  private static final BigInteger MODULUS = BigInteger.valueOf(97);
  private static final String EXAMPLE = "DE89370400440532013000";
  private final PiiExtractor extractor = new CursorPiiExtractor(Set.of(PiiMention.TYPE_IBAN));

  /**
   * Provides national parts covering all MOD 97 remainders for each country.
   *
   * @return The country and numeric national part.
   */
  private static Stream<Arguments> nationalParts() {
    return COUNTRIES.stream().flatMap(country -> IntStream.range(0, 97)
        .mapToObj(value -> Arguments.of(country,
            "0".repeat(country.length() - 6) + decimalPair(value))));
  }

  /**
   * Formats a number from 0 through 99 with 2 decimal digits.
   *
   * @param value The number to format.
   * @return The decimal digits.
   */
  private static String decimalPair(int value) {
    return value < 10 ? "0" + value : Integer.toString(value);
  }

  /**
   * Calculates check digits with an expanded integer.
   *
   * @param country The country code.
   * @param national The uppercase alphanumeric national part.
   * @return The compact candidate.
   */
  private static String candidate(String country, String national) {
    final String numeric = (national + country + "00").chars()
        .mapToObj(c -> c >= 'A' && c <= 'Z' ? Integer.toString(c - 'A' + 10)
            : Character.toString((char) c))
        .collect(Collectors.joining());
    final int check = 98 - new BigInteger(numeric).mod(MODULUS).intValue();
    return country + decimalPair(check) + national;
  }

  /**
   * Checks compact and printed spans, normalization and masking for all remainders.
   *
   * @param country The registered country and length.
   * @param national The synthetic national part.
   */
  @ParameterizedTest
  @MethodSource("nationalParts")
  void testChecksumAndSpans(Country country, String national) {
    final String compact = candidate(country.code(), national);
    final String prefix = "😀 Wire: ";
    for (final String formatted : List.of(compact, PiiTestSupport.grouped(compact, 4, ' '))) {
      final String text = prefix + formatted + ".";
      final List<PiiMention> expected = List.of(new PiiMention(
          new Span(prefix.length(), prefix.length() + formatted.length()),
          PiiMention.TYPE_IBAN, compact));
      Assertions.assertEquals(expected, extractor.extract(text));
      Assertions.assertEquals(expected, extractor.extract(new StringBuilder(text)));
      final Document document = new PiiAnnotator(extractor).annotate(Document.of(text));
      Assertions.assertEquals(prefix + "*".repeat(formatted.length()) + ".",
          Masker.mask(document, PiiAnnotator.PII, '*'));
    }
  }

  /**
   * Provides incorrect check digits from the generated 02-through-98 range.
   *
   * @return The checksum-invalid compact candidates.
   */
  private static Stream<String> incorrectChecks() {
    return COUNTRIES.stream().flatMap(country -> {
      final String compact = candidate(country.code(), "0".repeat(country.length() - 4));
      return IntStream.rangeClosed(2, 98).mapToObj(IbanValidationTest::decimalPair)
          .filter(check -> !compact.substring(2, 4).equals(check))
          .map(check -> country.code() + check + compact.substring(4));
    });
  }

  /**
   * Checks incorrect check digits without changing country or national-part length.
   *
   * @param compact The checksum-invalid candidate.
   */
  @ParameterizedTest
  @MethodSource("incorrectChecks")
  void testIncorrectChecksum(String compact) {
    Assertions.assertTrue(extractor.extract(compact).isEmpty(), compact);
    Assertions.assertTrue(extractor.extract(PiiTestSupport.grouped(compact, 4, ' ')).isEmpty(),
        compact);
  }

  /**
   * Provides checksum-valid candidates with incorrect country lengths.
   *
   * @return The incorrect-length compact candidates.
   */
  private static Stream<String> incorrectLengths() {
    return COUNTRIES.stream().flatMap(country -> IntStream.rangeClosed(14, 35)
        .filter(length -> length != country.length())
        .mapToObj(length -> candidate(country.code(), "0".repeat(length - 4))));
  }

  /**
   * Checks that a passing checksum does not bypass the registered length.
   *
   * @param compact The incorrect-length candidate.
   */
  @ParameterizedTest
  @MethodSource("incorrectLengths")
  void testIncorrectLength(String compact) {
    Assertions.assertTrue(extractor.extract(compact).isEmpty(), compact);
  }

  /**
   * Checks unregistered codes, with valid check digits and a common length.
   *
   * @param country The unregistered code.
   */
  @ParameterizedTest
  @ValueSource(strings = {"AA", "US", "XX", "ZZ"})
  void testUnregisteredCountry(String country) {
    Assertions.assertTrue(extractor.extract(candidate(country, "0".repeat(18))).isEmpty());
  }

  /**
   * Provides uppercase letters for the alphanumeric-to-decimal conversion.
   *
   * @return The ASCII uppercase letters.
   */
  private static Stream<String> letters() {
    return IntStream.rangeClosed('A', 'Z').mapToObj(c -> Character.toString((char) c));
  }

  /**
   * Checks decimal expansion of letters, and rejection of lowercase candidate content.
   *
   * @param letter The letter in the national part.
   */
  @ParameterizedTest
  @MethodSource("letters")
  void testLetterExpansion(String letter) {
    final String national = "TEST" + "0".repeat(13) + letter;
    final String compact = candidate("GB", national);
    Assertions.assertEquals(List.of(new PiiMention(new Span(0, compact.length()),
        PiiMention.TYPE_IBAN, compact)), extractor.extract(compact));
    final String lower = compact.substring(0, compact.length() - 1)
        + (char) (letter.charAt(0) + ('a' - 'A'));
    Assertions.assertTrue(extractor.extract(lower).isEmpty());
  }

  /**
   * Checks Unicode word boundaries without changing the recognized number.
   *
   * @param neighbor A letter or digit next to the compact candidate.
   */
  @ParameterizedTest
  @ValueSource(strings = {"a", "0", "é", "\u0661", "\uD801\uDC28", "\uD835\uDFD9"})
  void testWordBoundaries(String neighbor) {
    Assertions.assertTrue(extractor.extract(neighbor + EXAMPLE).isEmpty());
    Assertions.assertTrue(extractor.extract(EXAMPLE + neighbor).isEmpty());
    Assertions.assertEquals(List.of(new PiiMention(
        new Span(neighbor.length() + 1, neighbor.length() + 1 + EXAMPLE.length()),
        PiiMention.TYPE_IBAN, EXAMPLE)), extractor.extract(neighbor + " " + EXAMPLE));
  }

  /**
   * Checks prefix recovery before a long separated group.
   *
   * @param suffix The trailing text.
   */
  @ParameterizedTest
  @ValueSource(strings = {" 1234567890123456789012345678901234567890", " ACCOUNT CLOSED"})
  void testTrailingGroup(String suffix) {
    Assertions.assertEquals(List.of(new PiiMention(new Span(0, EXAMPLE.length()),
        PiiMention.TYPE_IBAN, EXAMPLE)), extractor.extract(EXAMPLE + suffix));
  }

  /**
   * Checks space-separated national groups following a complete header.
   *
   * @param width The number of characters in each national group.
   */
  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4, 5, 6})
  void testNationalGroups(int width) {
    final String text = EXAMPLE.substring(0, 4) + " "
        + PiiTestSupport.grouped(EXAMPLE.substring(4), width, ' ');
    Assertions.assertEquals(List.of(new PiiMention(new Span(0, text.length()),
        PiiMention.TYPE_IBAN, EXAMPLE)), extractor.extract(text));
  }

  /**
   * Checks the contiguous ASCII header and the allowed separator after the header.
   *
   * @param header The invalid header or following separator.
   */
  @ParameterizedTest
  @ValueSource(strings = {"D E89", "DE 89", "DE8 9", "DE89\t", "DE89-", "de89",
      "DE\uff18\uff19", "DE89\n", "DE89  "})
  void testMalformedHeader(String header) {
    Assertions.assertTrue(extractor.extract(header + EXAMPLE.substring(4)).isEmpty());
  }

  /**
   * Checks characters outside the uppercase ASCII alphanumeric alphabet.
   *
   * @param inserted The character replacing a national digit.
   */
  @ParameterizedTest
  @ValueSource(strings = {"a", "é", "\u0660", "\uff10", "😀", "_", "\u00a0"})
  void testMalformedNationalPart(String inserted) {
    final String text = EXAMPLE.substring(0, 10) + inserted + EXAMPLE.substring(11);
    Assertions.assertTrue(extractor.extract(text).isEmpty());
  }

  /**
   * Checks incomplete candidates without reading beyond the input.
   *
   * @param text The incomplete candidate.
   */
  @ParameterizedTest
  @ValueSource(strings = {"", "D", "DE", "DE8", "DE89"})
  void testShortInput(String text) {
    Assertions.assertTrue(extractor.extract(text).isEmpty());
  }

  /**
   * Checks the integer-based generator using existing published IBAN examples.
   *
   * @param compact The complete number.
   */
  @ParameterizedTest
  @ValueSource(strings = {EXAMPLE, "NO9386011117947", "GB82WEST12345698765432"})
  void testReferenceCheckDigits(String compact) {
    Assertions.assertEquals(compact, candidate(compact.substring(0, 2), compact.substring(4)));
  }

  /**
   * Checks exact registered lengths independently of checksum calculations.
   *
   * @param country The country code.
   * @param length The registered length.
   */
  @ParameterizedTest
  @CsvSource({"NO,15", "DE,22", "GB,22", "MT,31", "LC,32", "RU,33"})
  void testRegisteredLengths(String country, int length) {
    Assertions.assertEquals(length, IbanLengths.registeredLength(country.charAt(0), country.charAt(1)));
  }

  /** Checks the manual's transfer text and excludes the following reference from masking. */
  @Test
  void testManualExample() {
    final String account = PiiTestSupport.grouped(EXAMPLE, 4, ' ');
    final Document transfer = new PiiAnnotator(PiiPacks.payment())
        .annotate(Document.of("Wire " + account + "; reference 1234."));
    Assertions.assertEquals(1, transfer.get(PiiAnnotator.PII).size());
    Assertions.assertEquals(EXAMPLE, transfer.get(PiiAnnotator.PII).getFirst().value().normalized());
    Assertions.assertEquals("Wire " + "*".repeat(account.length()) + "; reference 1234.",
        Masker.mask(transfer, PiiAnnotator.PII, '*'));
    Assertions.assertEquals("Wire IBAN-1; reference 1234.",
        new Pseudonymizer().rewrite(transfer).text());
  }
}
