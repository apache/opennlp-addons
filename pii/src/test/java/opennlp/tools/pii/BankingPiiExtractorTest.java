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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class BankingPiiExtractorTest {

  private final BankingPiiExtractor extractor = new BankingPiiExtractor();

  /**
   * Checks accepted numbers and their exact offsets and normalized forms.
   *
   * @param text The numeric input.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "011000015",
      "021000021",
      "026009593",
      "111000025",
      "121000358",
      "071000013",
      "072000326",
      "124003116",
      "061000104",
      "307070115",
      "322271627",
      "120000003",
      "210000007",
      "610000005",
      "800000006"})
  void testAcceptsRoutingNumbers(String text) {
    final List<PiiMention> mentions = extractor.extract(text);

    Assertions.assertEquals(1, mentions.size(), text);
    Assertions.assertEquals(PiiMention.TYPE_ABA_ROUTING, mentions.get(0).type());
    Assertions.assertEquals(text, mentions.get(0).normalized());
    Assertions.assertEquals(0, mentions.get(0).span().getStart());
    Assertions.assertEquals(text.length(), mentions.get(0).span().getEnd());
  }

  /**
   * Checks rejection of invalid check digits with allocated routing prefixes.
   *
   * @param text The number with an invalid checksum.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "021000022",
      "011000016",
      "121000359",
      "111000026",
      "322271628",
      "123456789",
      "111111111"})
  void testRejectsRoutingNumbersWithABrokenCheckDigit(String text) {
    Assertions.assertTrue(extractor.extract(text).isEmpty(), text);
  }

  /**
   * Checks reserved prefixes and the all-zero value despite a valid checksum.
   *
   * @param text The candidate with a reserved prefix or all-zero digits.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "130000006",
      "140000009",
      "200000004",
      "330000000",
      "600000002",
      "730000008",
      "810000009",
      "900000003",
      "000000000"})
  void testRejectsReservedPrefixesAndZero(String text) {
    Assertions.assertTrue(extractor.extract(text).isEmpty(), text);
  }

  /**
   * Checks lengths, separators and surrounding numeric or word characters.
   *
   * @param text The invalid numeric form.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "02100002",
      "0210000210",
      "021 000 021",
      "021-000-021",
      "x021000021",
      "021000021x",
      "1021000021",
      "021000021.5",
      "021000021,5",
      "1.021000021"})
  void testRejectsRoutingNumberNearMisses(String text) {
    Assertions.assertTrue(extractor.extract(text).isEmpty(), text);
  }

  /** Checks a match within a sentence. */
  @Test
  void testSpanInSentence() {
    final String text = "Wire to routing 021000021, account 12345678.";
    final List<PiiMention> mentions = extractor.extract(text);

    Assertions.assertEquals(1, mentions.size());
    Assertions.assertEquals("021000021", text.substring(
        mentions.get(0).span().getStart(), mentions.get(0).span().getEnd()));
  }

  /** Checks that matches retain their text order. */
  @Test
  void testTwoRoutingNumbersInOneText() {
    final String text = "from 021000021 to 121000358";
    final List<PiiMention> mentions = extractor.extract(text);

    Assertions.assertEquals(2, mentions.size());
    Assertions.assertEquals("021000021", mentions.get(0).normalized());
    Assertions.assertEquals("121000358", mentions.get(1).normalized());
  }

  /**
   * Checks text with no routing-number candidate.
   *
   * @param text The input text.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "nothing here",
      "call (555) 123-4567",
      "1234",
      "",
      "the year 2026 and the number 42"})
  void testTextWithoutARoutingNumberYieldsNoMention(String text) {
    Assertions.assertTrue(extractor.extract(text).isEmpty(), text);
  }

  /** Checks the null-text boundary contract. */
  @Test
  void testRejectsNullText() {
    Assertions.assertThrows(IllegalArgumentException.class, () -> extractor.extract(null));
  }
}
