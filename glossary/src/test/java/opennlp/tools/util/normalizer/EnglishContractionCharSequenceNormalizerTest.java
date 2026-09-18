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

package opennlp.tools.util.normalizer;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.util.Span;

/** Tests supported expansions, ambiguous forms, and the aligned builder. */
public class EnglishContractionCharSequenceNormalizerTest {

  private final EnglishContractionCharSequenceNormalizer normalizer =
      EnglishContractionCharSequenceNormalizer.getInstance();

  /**
   * Expands supported forms with their original case and apostrophe variants.
   *
   * @param input The contraction text.
   * @param expected The expanded text.
   */
  @ParameterizedTest
  @CsvSource(value = {
      "can't|can not",
      "won't|will not",
      "shan't|shall not",
      "don't|do not",
      "we're|we are",
      "they've|they have",
      "I'll|I will",
      "I'm|I am",
      "let's|let us",
      "CAN'T|CAN NOT",
      "Won't|Will not",
      "can\u2019t|can not",
      "can\u02BCt|can not",
      "can\uFF07t|can not",
      "we can't and they won't|we can not and they will not"
  }, delimiter = '|')
  void testExpandsUnambiguousEnglishContractions(String input, String expected) {
    Assertions.assertEquals(expected, normalizer.normalize(input).toString());
  }

  /**
   * Preserves ambiguous forms and lexical apostrophes.
   *
   * @param input The text to preserve.
   */
  @ParameterizedTest
  @ValueSource(strings = {"he's", "she'd", "John's", "ain't", "o'clock"})
  void testLeavesAmbiguousAndLexicalizedApostrophesUntouched(String input) {
    Assertions.assertSame(input, normalizer.normalize(input));
  }

  /** Maps the full expansion and individual replacement blocks to their source spans. */
  @Test
  void testAlignmentKeepsTheOriginalContraction() {
    final AlignedText aligned = normalizer.normalizeAligned("can't");

    Assertions.assertEquals("can not", aligned.normalizedString());
    Assertions.assertEquals(new Span(0, 5), aligned.toOriginalSpan(0, 7));
    Assertions.assertEquals(new Span(0, 3), aligned.toOriginalSpan(0, 3));
    Assertions.assertEquals(new Span(3, 5), aligned.toOriginalSpan(4, 7));
  }

  /** Adds contraction expansion through the aligned pipeline builder. */
  @Test
  void testBuilderProducesAnOffsetAwareContractionPipeline() {
    final OffsetAwareNormalizer pipeline = TextNormalizer.builder()
        .with(EnglishContractionCharSequenceNormalizer.getInstance())
        .buildAligned();

    final AlignedText aligned = pipeline.normalizeAligned("we'll go");
    Assertions.assertEquals("we will go", aligned.normalizedString());
    Assertions.assertEquals(new Span(0, 5), aligned.toOriginalSpan(0, 7));
  }

  /** Verifies the manual's quoted contraction and original-text span. */
  @Test
  void testQuotedManualExample() {
    final OffsetAwareNormalizer english = TextNormalizer.builder()
        .with(EnglishContractionCharSequenceNormalizer.getInstance()).buildAligned();
    final AlignedText expanded = english.normalizeAligned("we 'can't' leave");
    Assertions.assertEquals("we 'can not' leave", expanded.normalizedString());
    Assertions.assertEquals(new Span(4, 9), expanded.toOriginalSpan(4, 11));
  }

  /** Rejects a null input on both normalization entry points. */
  @Test
  void testRejectsNullText() {
    Assertions.assertThrows(IllegalArgumentException.class, () -> normalizer.normalize(null));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> normalizer.normalizeAligned(null));
  }
}
