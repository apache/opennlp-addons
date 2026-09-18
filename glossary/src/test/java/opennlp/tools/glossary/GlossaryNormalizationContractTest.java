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

package opennlp.tools.glossary;

import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.util.Span;
import opennlp.tools.util.normalizer.EnglishContractionCharSequenceNormalizer;
import opennlp.tools.util.normalizer.OffsetAwareNormalizer;
import opennlp.tools.util.normalizer.TermAnalyzer;
import opennlp.tools.util.normalizer.TextNormalizer;

import static opennlp.tools.glossary.GlossaryTestSupport.englishStemmingAnalyzer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests normalization on registered terms and source text through both matching paths. */
class GlossaryNormalizationContractTest {

  /**
   * Combines contraction expansion, case conversion, whitespace collapse, and optional stemming.
   *
   * @param tokenMatching Whether to use token matching and English stemming.
   */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testComposedNormalizationOffsets(boolean tokenMatching) {
    final OffsetAwareNormalizer normalizer = TextNormalizer.builder()
        .with(EnglishContractionCharSequenceNormalizer.getInstance())
        .fullCaseFold().whitespace().buildAligned();
    final String term = tokenMatching ? "can not ship order" : "can not ship orders";
    final List<GlossaryEntry> glossary = List.of(new GlossaryEntry("SHIPPING", term));
    final GlossaryMatcher matcher = tokenMatching
        ? new TermAnalyzingGlossaryMatcher(glossary, englishStemmingAnalyzer(), normalizer)
        : new AhoCorasickGlossaryMatcher(glossary, false, normalizer);
    final String input = "\uD83D\uDE42 'CAN\u2019T    ship orders' today.";
    assertEquals(List.of(new GlossaryMatch(new Span(4, 24), "SHIPPING", term)), matcher.match(input));
    assertEquals("CAN\u2019T    ship orders", input.substring(4, 24));
  }

  /**
   * Normalizes both sides of the lookup, including contracted glossary registrations.
   *
   * @param contraction The contracted form.
   * @param expansion The expanded form.
   */
  @ParameterizedTest
  @CsvSource(value = {"can't|can not", "won't|will not", "we\u2019re|we are"}, delimiter = '|')
  void testContractedRegistrations(String contraction, String expansion) {
    final OffsetAwareNormalizer normalizer = EnglishContractionCharSequenceNormalizer.getInstance();
    final List<GlossaryEntry> entries = List.of(new GlossaryEntry("ID", contraction));
    final TermAnalyzer analyzer = TermAnalyzer.builder().caseFold().build();
    final List<GlossaryMatcher> matchers = List.of(
        new AhoCorasickGlossaryMatcher(entries, true, normalizer),
        new TermAnalyzingGlossaryMatcher(entries, analyzer, normalizer));
    for (GlossaryMatcher matcher : matchers) {
      assertEquals(List.of(new GlossaryMatch(new Span(1, 1 + expansion.length()), "ID", contraction)),
          matcher.match("'" + expansion + "'"));
      assertEquals(List.of(new GlossaryMatch(new Span(1, 1 + contraction.length()), "ID", contraction)),
          matcher.match("'" + contraction + "'"));
    }
  }

  /**
   * Does not create separate matching tokens by expanding part of a source word.
   *
   * @param text The word that contains a contraction-shaped fragment.
   */
  @ParameterizedTest
  @ValueSource(strings = {"_can't", "\u00E9can't", "can't1", "can't've", "don't.com"})
  void testPartialWordDoesNotProduceExpandedTokens(String text) {
    final GlossaryMatcher matcher = new TermAnalyzingGlossaryMatcher(
        List.of(new GlossaryEntry("CANNOT", "can not"), new GlossaryEntry("DONOT", "do not")),
        TermAnalyzer.builder().caseFold().build(), EnglishContractionCharSequenceNormalizer.getInstance());
    assertTrue(matcher.match(text).isEmpty());
  }
}
