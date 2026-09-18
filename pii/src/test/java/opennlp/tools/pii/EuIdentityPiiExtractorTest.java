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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

public class EuIdentityPiiExtractorTest {

  private final EuIdentityPiiExtractor extractor = new EuIdentityPiiExtractor();

  /**
   * Checks accepted NHS forms, normalized digits and offsets.
   *
   * @param text The candidate text.
   * @param normalized The expected compact digits.
   */
  @ParameterizedTest
  @CsvSource({
      "9434765919, 9434765919",
      "9999999468, 9999999468",
      "4010232137, 4010232137",
      "1234567881, 1234567881",
      "9876543210, 9876543210",
      "'943 476 5919', 9434765919",
      "943-476-5919, 9434765919",
      "'999 999 9468', 9999999468"
  })
  void testAcceptsNhsNumbers(String text, String normalized) {
    final List<PiiMention> mentions = extractor.extract(text);

    Assertions.assertEquals(1, mentions.size(), text);
    Assertions.assertEquals(PiiMention.TYPE_UK_NHS, mentions.get(0).type());
    Assertions.assertEquals(normalized, mentions.get(0).normalized());
    Assertions.assertEquals(0, mentions.get(0).span().getStart());
    Assertions.assertEquals(text.length(), mentions.get(0).span().getEnd());
  }

  /**
   * Checks incorrect NHS check digits, including a computed check value of 10.
   *
   * @param text The rejected number.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "9434765918",
      "9999999469",
      "4010232138",
      "1234567882",
      "1234567890",
      "'943 476 5918'"})
  void testRejectsNhsNumbersWithABrokenCheckDigit(String text) {
    Assertions.assertTrue(extractor.extract(text).isEmpty(), text);
  }

  /**
   * Checks uniform digit rejection even when the checksum passes.
   *
   * @param text The uniform digits.
   */
  @ParameterizedTest
  @ValueSource(strings = {"0000000000", "1111111111"})
  void testRejectsUniformDigitRuns(String text) {
    Assertions.assertTrue(extractor.extract(text).isEmpty(), text);
  }

  /**
   * Checks invalid NHS lengths, groupings and boundaries.
   *
   * @param text The rejected form.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "943476591",
      "94347659190",
      "'9434 76 5919'",
      "'943 4765919'",
      "'943-476 5919'",
      "x9434765919",
      "9434765919x"})
  void testRejectsNhsNumberNearMisses(String text) {
    Assertions.assertTrue(
        extractor.extract(text).stream()
            .noneMatch(m -> PiiMention.TYPE_UK_NHS.equals(m.type())), text);
  }

  /**
   * Checks accepted German tax number forms and normalized digits.
   *
   * @param text The candidate text.
   * @param normalized The expected compact digits.
   */
  @ParameterizedTest
  @CsvSource({
      "65929970489, 65929970489",
      "81095324717, 81095324717",
      "23746189575, 23746189575",
      "50123456782, 50123456782",
      "'65 929 970 489', 65929970489",
      "65-929-970-489, 65929970489"
  })
  void testAcceptsGermanTaxNumbers(String text, String normalized) {
    final List<PiiMention> mentions = extractor.extract(text);

    Assertions.assertEquals(1, mentions.size(), text);
    Assertions.assertEquals(PiiMention.TYPE_DE_STEUER_ID, mentions.get(0).type());
    Assertions.assertEquals(normalized, mentions.get(0).normalized());
  }

  /**
   * Checks incorrect German tax number check digits.
   *
   * @param text The rejected number.
   */
  @ParameterizedTest
  @ValueSource(strings = {"65929970488", "81095324716", "23746189574", "50123456783"})
  void testRejectsGermanTaxNumbersWithABrokenCheckDigit(String text) {
    Assertions.assertTrue(extractor.extract(text).isEmpty(), text);
  }

  /**
   * Checks a leading zero, absent or excess repeated digits, and three adjacent repeats.
   *
   * @param text The rejected number.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "02476291358",
      "12345678903",
      "11223456785",
      "11123456786"})
  void testRejectsGermanTaxNumbersBreakingTheDigitRules(String text) {
    Assertions.assertTrue(extractor.extract(text).isEmpty(), text);
  }

  /** Checks three repeated digits with two adjacent occurrences, at indices 2, 4 and 5. */
  @Test
  void testAcceptsThreeOccurrencesWithAnAdjacentPair() {
    final List<PiiMention> mentions = extractor.extract("65929970489");

    Assertions.assertEquals(1, mentions.size());
    Assertions.assertEquals(PiiMention.TYPE_DE_STEUER_ID, mentions.get(0).type());
  }

  /** Checks original spans for both types in surrounding prose. */
  @Test
  void testSpansInSentence() {
    final String text = "NHS 943 476 5919 and IdNr 65929970489 recorded.";
    final List<PiiMention> mentions = extractor.extract(text);

    Assertions.assertEquals(2, mentions.size());
    Assertions.assertEquals("943 476 5919", text.substring(
        mentions.get(0).span().getStart(), mentions.get(0).span().getEnd()));
    Assertions.assertEquals("65929970489", text.substring(
        mentions.get(1).span().getStart(), mentions.get(1).span().getEnd()));
  }

  /**
   * Checks that an NHS match is not extracted from inside a German tax number.
   */
  @Test
  void testTaxNumberIsNotAlsoReportedAsAnNhsNumber() {
    final List<PiiMention> mentions = extractor.extract("65929970489");

    Assertions.assertEquals(1, mentions.size());
    Assertions.assertEquals(PiiMention.TYPE_DE_STEUER_ID, mentions.get(0).type());
  }

  /** Checks type selection without changing recognition rules. */
  @Test
  void testTypeSubsetLimitsWhatIsReported() {
    final String text = "NHS 9434765919 and IdNr 65929970489";

    Assertions.assertEquals(List.of(PiiMention.TYPE_UK_NHS),
        new EuIdentityPiiExtractor(Set.of(PiiMention.TYPE_UK_NHS)).extract(text)
            .stream().map(PiiMention::type).toList());
    Assertions.assertEquals(List.of(PiiMention.TYPE_DE_STEUER_ID),
        new EuIdentityPiiExtractor(Set.of(PiiMention.TYPE_DE_STEUER_ID)).extract(text)
            .stream().map(PiiMention::type).toList());
  }

  /**
   * Checks ordinary text without matching identifiers.
   *
   * @param text The input without a match.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "no identifier here",
      "1234",
      "",
      "the year 2026",
      "call (555) 123-4567"})
  void testTextWithoutAnIdentifierYieldsNoMention(String text) {
    Assertions.assertTrue(extractor.extract(text).isEmpty(), text);
  }

  /** Checks constructor and extraction argument validation. */
  @Test
  void testRejectsUnrecognizedTypeAndMissingArguments() {
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new EuIdentityPiiExtractor(Set.of(PiiMention.TYPE_EMAIL)));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new EuIdentityPiiExtractor(Set.of()));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new EuIdentityPiiExtractor(null));
    Assertions.assertThrows(IllegalArgumentException.class, () -> extractor.extract(null));
  }
}
