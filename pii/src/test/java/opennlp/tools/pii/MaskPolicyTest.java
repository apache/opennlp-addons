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
import org.junit.jupiter.params.provider.CsvSource;

import opennlp.tools.document.Document;

public class MaskPolicyTest {

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "4111 1111 1111 1111|*******************",
      "jane@example.com|****************"})
  void testBasePolicyMasksEveryCharacter(String text, String expected) {
    Assertions.assertEquals(expected, MaskPolicy.of('*').apply(text));
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "4111 1111 1111 1111|**** **** **** ****",
      "jane@example.com|****@*******.***",
      "+44 20 7946 0958|+** ** **** ****",
      "(555) 123-4567|(***) ***-****"})
  void testKeepingFormatLeavesSeparatorsVisible(String text, String expected) {
    Assertions.assertEquals(expected, MaskPolicy.of('*').keepingFormat().apply(text));
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "4111 1111 1111 1111|**** **** **** 1111",
      "DE89 3704 0044 0532 0130 00|**** **** **** **** **30 00"})
  void testKeepingFormatAndTrailingFourReadsLikeAReceipt(String text, String expected) {
    Assertions.assertEquals(expected,
        MaskPolicy.of('*').keepingFormat().keepingTrailing(4).apply(text));
  }

  @Test
  void testKeepingTrailingWithoutFormatMasksSeparatorsToo() {
    Assertions.assertEquals("***************1111",
        MaskPolicy.of('*').keepingTrailing(4).apply("4111 1111 1111 1111"));
  }

  @Test
  void testKeepingTrailingBeyondSpanLengthKeepsAllLettersAndDigits() {
    Assertions.assertEquals("123", MaskPolicy.of('*').keepingTrailing(9).apply("123"));
  }

  /**
   * A code point outside the basic plane becomes two mask characters, so the masked
   * text keeps the length of the original in UTF-16 units and every other layer's
   * offsets stay valid.
   */
  @Test
  void testSupplementaryCodePointMasksToTwoCharactersPreservingLength() {
    final String text = "user𝕒@example.com";
    final String masked = MaskPolicy.of('*').apply(text);
    Assertions.assertEquals(text.length(), masked.length());
    Assertions.assertEquals("*".repeat(text.length()), masked);
  }

  @Test
  void testTrailingCountingIsByCodePoint() {
    final String text = "ab𝕒";
    Assertions.assertEquals("**𝕒",
        MaskPolicy.of('*').keepingTrailing(1).apply(text));
  }

  @Test
  void testInvalidArguments() {
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> MaskPolicy.of('\uD835'));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> MaskPolicy.of('*').keepingTrailing(-1));
  }

  @Test
  void testMaskerAppliesAPolicyAcrossTheDocument() {
    final String text = "Card 4111 1111 1111 1111 and mail jane@example.com.";
    final Document document =
        new PiiAnnotator(new CursorPiiExtractor()).annotate(Document.of(text));

    final String masked = Masker.mask(document, PiiAnnotator.PII,
        MaskPolicy.of('*').keepingFormat().keepingTrailing(4));

    Assertions.assertEquals(text.length(), masked.length());
    Assertions.assertEquals("Card **** **** **** 1111 and mail ****@******e.com.",
        masked);
  }

  @Test
  void testMaskerRejectsNullPolicy() {
    final Document document = new PiiAnnotator(new CursorPiiExtractor())
        .annotate(Document.of("no pii here"));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> Masker.mask(document, List.of(PiiAnnotator.PII), (MaskPolicy) null));
  }
}
