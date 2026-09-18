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

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import opennlp.tools.tokenize.uax29.WordSegmenter;
import opennlp.tools.util.Span;
import opennlp.tools.util.StringUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Compares the automaton and its boundary shortcuts with direct term lookup. */
class AhoCorasickGlossaryOracleTest {

  private static final List<String> TERMS = List.of("a", "a b", "a b c", "b", "b c", "c",
      "cat", "CAT", "cat dog", "dog", "cat", "1", "12", "_", "a_b", ":", ".", ",",
      "can't", "\u4E2D", "\u6587", "\u4E2D\u6587", "\u05D0", "\u0628",
      "cafe\u0301", "\uD801\uDC00", "\uD801\uDC28", "\uD83D\uDC08");

  private static final List<String> FRAGMENTS = List.of("a", "b", "c", "cat", "CAT", "dog",
      "1", "12", "_", "'", ":", ".", ",", "-", " ", "  ", "\r\n", "\t", "\u0301",
      "\u200D", "\u00AD", "\u05D0", "\u0628", "\u4E2D", "\u6587", "\u3042",
      "\u30A2", "\uAC00", "\uD801\uDC00", "\uD801\uDC28", "\uD83D\uDC08");

  /**
   * Tests failure links, registration ties, overlap selection, and Unicode boundaries.
   *
   * @param seed The reproducible input generator seed.
   * @param ignoreCase Whether to compare lowercase forms.
   */
  @ParameterizedTest
  @CsvSource({"1,false", "1,true", "17,false", "17,true", "42,false", "42,true",
      "8675309,false", "8675309,true"})
  void testAgainstDirectLookup(long seed, boolean ignoreCase) {
    final List<GlossaryEntry> entries = new ArrayList<>();
    for (int i = 0; i < TERMS.size(); i++) {
      entries.add(new GlossaryEntry(Integer.toString(i), TERMS.get(i)));
    }
    final Random random = new Random(seed);
    final GlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(entries, ignoreCase);
    for (int sample = 0; sample < 250; sample++) {
      final StringBuilder text = new StringBuilder();
      for (int piece = 0; piece < 40; piece++) {
        text.append(FRAGMENTS.get(random.nextInt(FRAGMENTS.size())));
      }
      final String input = text.toString();
      assertEquals(directLookup(entries, input, ignoreCase), matcher.match(new StringBuilder(input)),
          "ignoreCase=" + ignoreCase + ", sample=" + sample + ", input=" + input);
    }
  }

  /**
   * Searches each word boundary for the longest matching term in registration order.
   * Uses the full segmenter without the automaton's ASCII boundary shortcuts.
   *
   * @param entries The terms in registration order.
   * @param text The source text.
   * @param ignoreCase Whether to apply the documented lowercase mapping.
   * @return Non-overlapping matches selected from left to right.
   */
  private List<GlossaryMatch> directLookup(List<GlossaryEntry> entries, String text,
      boolean ignoreCase) {
    final BitSet boundaries = new BitSet(text.length() + 1);
    boundaries.set(0);
    WordSegmenter.forEachSegment(text, (start, end) -> boundaries.set(end));
    final String input = ignoreCase ? StringUtil.toLowerCase(text) : text;
    final List<GlossaryMatch> matches = new ArrayList<>();
    int start = 0;
    while (start < input.length()) {
      GlossaryMatch longest = null;
      for (GlossaryEntry entry : entries) {
        final String pattern = ignoreCase ? StringUtil.toLowerCase(entry.term()) : entry.term();
        final int end = start + pattern.length();
        if (input.startsWith(pattern, start) && boundaries.get(start) && boundaries.get(end)
            && (longest == null || end > longest.span().getEnd())) {
          longest = new GlossaryMatch(new Span(start, end), entry.id(), entry.term());
        }
      }
      if (longest == null) {
        start++;
      } else {
        matches.add(longest);
        start = longest.span().getEnd();
      }
    }
    return matches;
  }
}
