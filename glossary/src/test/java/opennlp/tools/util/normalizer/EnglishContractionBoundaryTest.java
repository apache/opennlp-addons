/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
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

package opennlp.tools.util.normalizer;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.util.Span;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Tests contraction boundaries and source alignment in surrounding text. */
class EnglishContractionBoundaryTest {

  private static final EnglishContractionCharSequenceNormalizer NORMALIZER =
      EnglishContractionCharSequenceNormalizer.getInstance();

  /**
   * Does not expand a contraction-shaped portion of a longer word or identifier.
   *
   * @param input The text that must remain unchanged.
   */
  @ParameterizedTest
  @ValueSource(strings = {"\u00E9can't", "can't\u00E9", "\uD801\uDC00can't", "can't\uD801\uDC00",
      "_can't", "can't_", "1can't", "can't1", "e\u0301can't", "can't\u0301", "can't\u200Dgo",
      "rock'can't", "can't'roll", "can't've", "I.don't", "don't.com"})
  void testNoPartialWordExpansion(String input) {
    assertSame(input, NORMALIZER.normalize(input));
    final AlignedText aligned = NORMALIZER.normalizeAligned(input);
    assertEquals(input, aligned.normalizedString());
    assertEquals(new Span(0, input.length()), aligned.toOriginalSpan(0, input.length()));
  }

  /**
   * Expands quoted or punctuation-delimited words while preserving the surrounding text.
   *
   * @param input The original text.
   * @param expected The expected expansion.
   */
  @ParameterizedTest
  @CsvSource(value = {"'can't'|'can not'", "\u2018can\u2019t\u2019|\u2018can not\u2019",
      "'we're ready'|'we are ready'", "'I'm'|'I am'", "(don't)|(do not)",
      "\uD83D\uDE42can't!|\uD83D\uDE42can not!", "x-can't|x-can not",
      "can't:|can not:", "can't,|can not,"}, delimiter = '|', quoteCharacter = '"')
  void testDelimitedExpansion(String input, String expected) {
    assertEquals(expected, NORMALIZER.normalize(new StringBuilder(input)).toString());
    final AlignedText aligned = NORMALIZER.normalizeAligned(input);
    assertEquals(expected, aligned.normalizedString());
    assertEquals(input, aligned.original());
    assertEquals(new Span(0, input.length()), aligned.toOriginalSpan(0, expected.length()));
  }

  /**
   * Checks source offsets after a supplementary character and surrounding quotation marks.
   *
   * @param contraction The quoted contraction.
   * @param expansion Its normalized form.
   */
  @ParameterizedTest
  @CsvSource(value = {"can't|can not", "won't|will not", "shan't|shall not", "we're|we are"},
      delimiter = '|')
  void testQuotedExpansionOffsets(String contraction, String expansion) {
    final String prefix = "\uD83D\uDE42 '";
    final String suffix = "' now";
    final AlignedText aligned = NORMALIZER.normalizeAligned(prefix + contraction + suffix);
    assertEquals(prefix + expansion + suffix, aligned.normalizedString());
    assertEquals(new Span(prefix.length(), prefix.length() + contraction.length()),
        aligned.toOriginalSpan(prefix.length(), prefix.length() + expansion.length()));
    assertEquals(new Span(prefix.length() + contraction.length(), aligned.original().length()),
        aligned.toOriginalSpan(prefix.length() + expansion.length(), aligned.normalized().length()));
  }
}
