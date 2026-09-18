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

package opennlp.tools.glossary;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import opennlp.tools.util.Span;
import opennlp.tools.util.StringUtil;
import opennlp.tools.util.normalizer.AlignedText;
import opennlp.tools.util.normalizer.Alignment;
import opennlp.tools.util.normalizer.OffsetAwareNormalizer;
import opennlp.tools.util.normalizer.TermAnalyzer;

/** Tests matching when a custom normalizer inserts characters without source coverage. */
class GlossaryInsertionTest {

  /** Registered token inserted by the test normalizer. */
  private static final String TERM = "term";

  /**
   * Supports aligned insertions and selects the first duplicate registration.
   *
   * @param normalizer The insertion position.
   * @param text The blank source text.
   * @param tokenMatching Whether to match normalized word tokens.
   */
  @ParameterizedTest
  @CsvSource(value = {
      "PREFIX|''|false", "PREFIX|' '|false", "PREFIX|'\t'|false",
      "SUFFIX|''|false", "SUFFIX|' '|false", "SUFFIX|'\t'|false",
      "PREFIX|''|true", "PREFIX|' '|true", "PREFIX|'\t'|true",
      "SUFFIX|''|true", "SUFFIX|' '|true", "SUFFIX|'\t'|true"
  }, delimiter = '|')
  void testInsertionAtSourceBoundary(InsertionNormalizer normalizer, String text,
      boolean tokenMatching) {
    final List<GlossaryEntry> entries = List.of(
        new GlossaryEntry("FIRST", TERM), new GlossaryEntry("SECOND", TERM));
    final GlossaryMatcher matcher = tokenMatching
        ? new TermAnalyzingGlossaryMatcher(entries, TermAnalyzer.builder().caseFold().build(), normalizer)
        : new AhoCorasickGlossaryMatcher(entries, false, normalizer);
    final int offset = normalizer == InsertionNormalizer.PREFIX ? 0 : text.length();
    Assertions.assertEquals(List.of(new GlossaryMatch(new Span(offset, offset), "FIRST", TERM)),
        matcher.match(text));
  }

  /** Inserts a token at the start or end of blank input, leaving registered terms unchanged. */
  private enum InsertionNormalizer implements OffsetAwareNormalizer {
    PREFIX, SUFFIX;

    /** {@inheritDoc} */
    @Override
    public CharSequence normalize(CharSequence text) {
      return normalizeAligned(text).normalized();
    }

    /** {@inheritDoc} */
    @Override
    public AlignedText normalizeAligned(CharSequence text) {
      if (text == null) {
        throw new IllegalArgumentException("text must not be null");
      }
      final Alignment.Builder alignment = new Alignment.Builder();
      if (!StringUtil.isBlank(text)) {
        return new AlignedText(text, text, alignment.equal(text.length()).build(text.length()));
      }
      final String normalized;
      if (this == PREFIX) {
        alignment.replace(0, TERM.length()).equal(text.length());
        normalized = TERM + text;
      } else {
        alignment.equal(text.length()).replace(0, TERM.length());
        normalized = text + TERM;
      }
      return new AlignedText(text, normalized, alignment.build(text.length()));
    }
  }
}
