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

public class UsIdentityPiiExtractorTest {

  private final UsIdentityPiiExtractor extractor = new UsIdentityPiiExtractor();

  /**
   * Checks accepted SSN ranges, grouping, normalization and offsets.
   *
   * @param text The candidate text.
   * @param normalized The expected hyphenated form.
   */
  @ParameterizedTest
  @CsvSource({
      "123-45-6789, 123-45-6789",
      "001-01-0001, 001-01-0001",
      "899-99-9999, 899-99-9999",
      "078-05-1120, 078-05-1120",
      "'123 45 6789', 123-45-6789",
      "'001 01 0001', 001-01-0001"
  })
  void testAcceptsSocialSecurityNumbers(String text, String normalized) {
    final List<PiiMention> mentions = extractor.extract(text);

    Assertions.assertEquals(1, mentions.size(), text);
    Assertions.assertEquals(PiiMention.TYPE_US_SSN, mentions.get(0).type());
    Assertions.assertEquals(normalized, mentions.get(0).normalized());
    Assertions.assertEquals(0, mentions.get(0).span().getStart());
    Assertions.assertEquals(text.length(), mentions.get(0).span().getEnd());
  }

  /**
   * Checks excluded SSN areas and zero-valued groups.
   *
   * @param text The rejected SSN form.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "000-45-6789",
      "666-45-6789",
      "123-00-6789",
      "123-45-0000",
      "000-00-0000"})
  void testRejectsSocialSecurityNumbersThatAreNeverIssued(String text) {
    Assertions.assertTrue(extractor.extract(text).isEmpty(), text);
  }

  /**
   * Checks invalid lengths, groupings, separators and boundaries.
   *
   * @param text The rejected candidate.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "123456789",
      "12-345-6789",
      "1234-56-789",
      "123-456789",
      "123-45-678",
      "123-45-67890",
      "'123 45-6789'",
      "'123-45 6789'",
      "123.45.6789",
      "x123-45-6789",
      "123-45-6789-0"})
  void testRejectsSocialSecurityNumberNearMisses(String text) {
    Assertions.assertTrue(extractor.extract(text).isEmpty(), text);
  }

  /**
   * Checks accepted ITIN groups and normalization.
   *
   * @param text The candidate text.
   * @param normalized The expected hyphenated form.
   */
  @ParameterizedTest
  @CsvSource({
      "900-70-1234, 900-70-1234",
      "900-70-0000, 900-70-0000",
      "'900 70 0000', 900-70-0000",
      "999-88-9999, 999-88-9999",
      "912-50-1234, 912-50-1234",
      "923-99-1234, 923-99-1234",
      "934-94-0001, 934-94-0001",
      "900-65-1234, 900-65-1234",
      "'900 92 1234', 900-92-1234"
  })
  void testAcceptsIndividualTaxpayerIdentificationNumbers(String text, String normalized) {
    final List<PiiMention> mentions = extractor.extract(text);

    Assertions.assertEquals(1, mentions.size(), text);
    Assertions.assertEquals(PiiMention.TYPE_US_ITIN, mentions.get(0).type());
    Assertions.assertEquals(normalized, mentions.get(0).normalized());
  }

  /**
   * Checks excluded ITIN groups.
   *
   * @param text The rejected candidate.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "900-49-1234",
      "900-66-1234",
      "900-69-1234",
      "900-89-1234",
      "900-93-1234",
      "999-00-1234"})
  void testRejectsAreasAboveEightHundredNinetyNineOutsideTheIrsGroups(String text) {
    Assertions.assertTrue(extractor.extract(text).isEmpty(), text);
  }

  /** Checks the original span in surrounding prose. */
  @Test
  void testSpanInSentence() {
    final String text = "Her SSN is 123-45-6789, filed in 2026.";
    final List<PiiMention> mentions = extractor.extract(text);

    Assertions.assertEquals(1, mentions.size());
    Assertions.assertEquals("123-45-6789", text.substring(
        mentions.get(0).span().getStart(), mentions.get(0).span().getEnd()));
  }

  /** Checks both supported types in one request. */
  @Test
  void testFindsBothTypesInOneText() {
    final String text = "SSN 123-45-6789 and ITIN 900-70-1234";
    final List<PiiMention> mentions = extractor.extract(text);

    Assertions.assertEquals(List.of(PiiMention.TYPE_US_SSN, PiiMention.TYPE_US_ITIN),
        mentions.stream().map(PiiMention::type).toList());
  }

  /** Checks type selection without changing recognition rules. */
  @Test
  void testTypeSubsetLimitsWhatIsReported() {
    final String text = "SSN 123-45-6789 and ITIN 900-70-1234";

    Assertions.assertEquals(List.of(PiiMention.TYPE_US_SSN),
        new UsIdentityPiiExtractor(Set.of(PiiMention.TYPE_US_SSN)).extract(text)
            .stream().map(PiiMention::type).toList());
    Assertions.assertEquals(List.of(PiiMention.TYPE_US_ITIN),
        new UsIdentityPiiExtractor(Set.of(PiiMention.TYPE_US_ITIN)).extract(text)
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
      "call (555) 123-4567",
      "2026-08-10",
      "",
      "the ratio 10-10-10 of parts"})
  void testTextWithoutAnIdentifierYieldsNoMention(String text) {
    Assertions.assertTrue(extractor.extract(text).isEmpty(), text);
  }

  /** Checks constructor and extraction argument validation. */
  @Test
  void testRejectsUnrecognizedTypeAndMissingArguments() {
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new UsIdentityPiiExtractor(Set.of(PiiMention.TYPE_EMAIL)));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new UsIdentityPiiExtractor(Set.of()));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new UsIdentityPiiExtractor(null));
    Assertions.assertThrows(IllegalArgumentException.class, () -> extractor.extract(null));
  }
}
