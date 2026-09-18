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

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.lemmatizer.Lemmatizer;
import opennlp.tools.stemmer.snowball.SnowballStemmer;
import opennlp.tools.stemmer.snowball.SnowballStemmerFactory;
import opennlp.tools.util.Span;
import opennlp.tools.util.normalizer.CharSequenceNormalizer;
import opennlp.tools.util.normalizer.Dimension;
import opennlp.tools.util.normalizer.EnglishContractionCharSequenceNormalizer;
import opennlp.tools.util.normalizer.TermAnalyzer;

import static opennlp.tools.glossary.GlossaryTestSupport.englishStemmingAnalyzer;

/**
 * Tests token-normalized glossary matching and source offsets.
 */
public class TermAnalyzingGlossaryMatcherTest {

  /**
   * Matches the plural form of a registered phrase using English stemming.
   */
  @Test
  void testHotDogMatchesHotDogsWithOriginalPluralSpan() {
    final TermAnalyzingGlossaryMatcher matcher = new TermAnalyzingGlossaryMatcher(
        List.of(new GlossaryEntry("FOOD", "hot dog")), englishStemmingAnalyzer());

    final String text = "hot dogs";
    final List<GlossaryMatch> matches = matcher.match(text);

    Assertions.assertEquals(1, matches.size());
    Assertions.assertEquals("FOOD", matches.get(0).id());
    Assertions.assertEquals("hot dog", matches.get(0).term());
    Assertions.assertEquals(new Span(0, 8), matches.get(0).span());
    Assertions.assertEquals("hot dogs",
        text.substring(matches.get(0).span().getStart(), matches.get(0).span().getEnd()));
  }

  /**
   * Excludes surrounding words from an inflected phrase match.
   */
  @Test
  void testHotDogMatchesHotDogsInsideSentence() {
    final TermAnalyzingGlossaryMatcher matcher = new TermAnalyzingGlossaryMatcher(
        List.of(new GlossaryEntry("FOOD", "hot dog")), englishStemmingAnalyzer());

    final String text = "the hot dogs were cold";
    final List<GlossaryMatch> matches = matcher.match(text);

    Assertions.assertEquals(1, matches.size());
    Assertions.assertEquals(new Span(4, 12), matches.get(0).span());
    Assertions.assertEquals("hot dogs",
        text.substring(matches.get(0).span().getStart(), matches.get(0).span().getEnd()));
  }

  /**
   * Confirms that character matching does not perform stemming.
   */
  @Test
  void testExactMatcherStillMissesInflectedHotDogs() {
    final AhoCorasickGlossaryMatcher exact = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("FOOD", "hot dog")), true);
    Assertions.assertTrue(exact.match("hot dogs").isEmpty());
    Assertions.assertTrue(exact.match("the hot dogs were cold").isEmpty());
  }

  /**
   * Returns the mixed-case source span after case normalization and stemming.
   */
  @Test
  void testMixedCaseInflectedSurfaceKeepsOriginalSpan() {
    final TermAnalyzingGlossaryMatcher matcher = new TermAnalyzingGlossaryMatcher(
        List.of(new GlossaryEntry("FOOD", "hot dog")), englishStemmingAnalyzer());

    final String text = "Hot Dogs sold here";
    final List<GlossaryMatch> matches = matcher.match(text);
    Assertions.assertEquals(1, matches.size());
    Assertions.assertEquals("Hot Dogs",
        text.substring(matches.get(0).span().getStart(), matches.get(0).span().getEnd()));
  }

  /**
   * Matches a singular source phrase registered in plural form.
   */
  @Test
  void testPluralGlossaryTermMatchesSingularSurface() {
    final TermAnalyzingGlossaryMatcher matcher = new TermAnalyzingGlossaryMatcher(
        List.of(new GlossaryEntry("FOOD", "hot dogs")), englishStemmingAnalyzer());

    final String text = "one hot dog please";
    final List<GlossaryMatch> matches = matcher.match(text);
    Assertions.assertEquals(1, matches.size());
    Assertions.assertEquals(new Span(4, 11), matches.get(0).span());
    Assertions.assertEquals("hot dog",
        text.substring(matches.get(0).span().getStart(), matches.get(0).span().getEnd()));
  }

  /**
   * Selects the longest stemmed phrase at a shared start offset.
   */
  @Test
  void testLongestStemmedPhraseWins() {
    final TermAnalyzingGlossaryMatcher matcher = new TermAnalyzingGlossaryMatcher(
        List.of(new GlossaryEntry("SHORT", "hot dog"),
            new GlossaryEntry("LONG", "hot dog stand")), englishStemmingAnalyzer());

    final String text = "hot dog stands nearby";
    final List<GlossaryMatch> matches = matcher.match(text);
    Assertions.assertEquals(1, matches.size());
    Assertions.assertEquals("LONG", matches.get(0).id());
    Assertions.assertEquals("hot dog stands",
        text.substring(matches.get(0).span().getStart(), matches.get(0).span().getEnd()));
  }

  /**
   * Reports separate stemmed phrases in source order.
   */
  @Test
  void testMultipleStemmedHitsPreserveOrder() {
    final TermAnalyzingGlossaryMatcher matcher = new TermAnalyzingGlossaryMatcher(
        List.of(new GlossaryEntry("FOOD", "hot dog"),
            new GlossaryEntry("DRINK", "soft drink")), englishStemmingAnalyzer());

    final String text = "hot dogs and soft drinks";
    final List<GlossaryMatch> matches = matcher.match(text);
    Assertions.assertEquals(2, matches.size());
    Assertions.assertEquals("FOOD", matches.get(0).id());
    Assertions.assertEquals("DRINK", matches.get(1).id());
    Assertions.assertEquals("hot dogs",
        text.substring(matches.get(0).span().getStart(), matches.get(0).span().getEnd()));
    Assertions.assertEquals("soft drinks",
        text.substring(matches.get(1).span().getStart(), matches.get(1).span().getEnd()));
  }

  /**
   * Accepts empty input and rejects null input.
   */
  @Test
  void testEmptyMissAndNullText() {
    final TermAnalyzingGlossaryMatcher matcher = new TermAnalyzingGlossaryMatcher(
        List.of(new GlossaryEntry("FOOD", "hot dog")), englishStemmingAnalyzer());
    Assertions.assertTrue(matcher.match("").isEmpty());
    Assertions.assertTrue(matcher.match("nothing tasty here").isEmpty());
    Assertions.assertThrows(IllegalArgumentException.class, () -> matcher.match(null));
  }

  /**
   * Construction validates glossary and analyzer arguments.
   */
  @Test
  void testInvalidConstructorArguments() {
    final TermAnalyzer analyzer = englishStemmingAnalyzer();
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new TermAnalyzingGlossaryMatcher(null, analyzer));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new TermAnalyzingGlossaryMatcher(List.of(), analyzer));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new TermAnalyzingGlossaryMatcher(Collections.singletonList(null), analyzer));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new TermAnalyzingGlossaryMatcher(
            List.of(new GlossaryEntry("FOOD", "hot dog")), null));
  }

  /**
   * Includes separators between matching word tokens in the source span.
   *
   * @param surface The phrase with punctuation between words.
   */
  @ParameterizedTest
  @ValueSource(strings = {"hot-dogs", "hot, dogs", "hot/dogs", "hot (dogs)", "hot. Dogs"})
  void testSeparatorsBetweenTokensDoNotBlockTheMatch(String surface) {
    final TermAnalyzingGlossaryMatcher matcher = new TermAnalyzingGlossaryMatcher(
        List.of(new GlossaryEntry("FOOD", "hot dog")), englishStemmingAnalyzer());

    final String text = "eat " + surface + " now";
    final List<GlossaryMatch> matches = matcher.match(text);

    Assertions.assertEquals(1, matches.size());
    final Span span = matches.get(0).span();
    final String covered = text.substring(span.getStart(), span.getEnd());
    Assertions.assertTrue(covered.startsWith("hot"), covered);
    // A trailing parenthesis is outside the final word token.
    Assertions.assertTrue(covered.endsWith("dogs") || covered.endsWith("Dogs"), covered);
  }

  /**
   * Uses registration order when stemming makes phrases equal.
   */
  @Test
  void testFirstWinsForDuplicateAnalyzedPatterns() {
    final TermAnalyzingGlossaryMatcher matcher = new TermAnalyzingGlossaryMatcher(
        List.of(new GlossaryEntry("FIRST", "hot dog"),
            new GlossaryEntry("SECOND", "hot dogs")), englishStemmingAnalyzer());

    final List<GlossaryMatch> matches = matcher.match("hot dogs for sale");
    Assertions.assertEquals(1, matches.size());
    Assertions.assertEquals("FIRST", matches.get(0).id());
    Assertions.assertEquals("hot dog", matches.get(0).term());
  }

  /**
   * Rejects lemmatizing analyzers because plain-text analysis supplies no POS tags.
   */
  @Test
  void testLemmatizingAnalyzerFailsFastAtConstruction() {
    final TermAnalyzer lemmatizing = TermAnalyzer.builder()
        .caseFold()
        .lemmatize(new Lemmatizer() {
          /** {@inheritDoc} */
          @Override
          public String[] lemmatize(String[] toks, String[] tags) {
            return toks;
          }

          /** {@inheritDoc} */
          @Override
          public List<List<String>> lemmatize(List<String> toks, List<String> tags) {
            throw new UnsupportedOperationException();
          }
        })
        .build();

    final IllegalArgumentException ex = Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> new TermAnalyzingGlossaryMatcher(
            List.of(new GlossaryEntry("FOOD", "hot dog")), lemmatizing));
    Assertions.assertTrue(ex.getMessage().contains("LEMMA"), ex.getMessage());
  }

  /**
   * Rejects blank-normalized registration tokens and omits them from input matching.
   */
  @Test
  void testBlankNormalizedTokensRejectInTermsAndVanishInText() {
    final CharSequenceNormalizer blanker = text ->
        "zap".contentEquals(text) ? "" : text;
    final TermAnalyzer blanking = TermAnalyzer.builder()
        .caseFold()
        .transform(Dimension.WHITESPACE, blanker)
        .stem(new SnowballStemmerFactory(SnowballStemmer.ALGORITHM.ENGLISH))
        .build();

    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new TermAnalyzingGlossaryMatcher(
            List.of(new GlossaryEntry("Z", "zap")), blanking));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new TermAnalyzingGlossaryMatcher(
            List.of(new GlossaryEntry("Z2", "hot zap dog")), blanking));

    final TermAnalyzingGlossaryMatcher matcher = new TermAnalyzingGlossaryMatcher(
        List.of(new GlossaryEntry("FOOD", "hot dog")), blanking);
    final String text = "the hot zap dogs bark";
    final List<GlossaryMatch> matches = matcher.match(text);
    Assertions.assertEquals(1, matches.size());
    Assertions.assertEquals("hot zap dogs",
        text.substring(matches.get(0).span().getStart(), matches.get(0).span().getEnd()));
  }

  /**
   * Finds Tokyo using adjacent Han character tokens.
   */
  @Test
  void testUnspacedHanTextMatchesThroughTokenPath() {
    final TermAnalyzingGlossaryMatcher matcher = new TermAnalyzingGlossaryMatcher(
        List.of(new GlossaryEntry("Q1490", "\u6771\u4EAC")),
        TermAnalyzer.builder().caseFold().build());

    // watashi wa Tokyo ni sumu, no spaces anywhere
    final String text = "\u79C1\u306F\u6771\u4EAC\u306B\u4F4F\u3080";
    final List<GlossaryMatch> matches = matcher.match(text);

    Assertions.assertEquals(1, matches.size());
    Assertions.assertEquals("Q1490", matches.get(0).id());
    Assertions.assertEquals(new Span(2, 4), matches.get(0).span());
  }

  /**
   * Rejects a partial katakana token and accepts a complete token after Han text.
   */
  @Test
  void testKatakanaRunHidesEmbeddedTermOnTokenPath() {
    final TermAnalyzingGlossaryMatcher matcher = new TermAnalyzingGlossaryMatcher(
        List.of(new GlossaryEntry("TOWER", "\u30BF\u30EF\u30FC")),
        TermAnalyzer.builder().caseFold().build());

    // toukyou tawaa as one katakana run: no hit inside it
    Assertions.assertTrue(
        matcher.match("\u30C8\u30A6\u30AD\u30E7\u30A6\u30BF\u30EF\u30FC").isEmpty());
    // the same term after Han neighbors is a token of its own
    final String bounded = "\u6771\u4EAC\u30BF\u30EF\u30FC";
    final List<GlossaryMatch> matches = matcher.match(bounded);
    Assertions.assertEquals(1, matches.size());
    Assertions.assertEquals(new Span(2, 5), matches.get(0).span());
  }

  /**
   * Maps a match through contraction expansion and stemming to the original phrase.
   */
  @Test
  void testContractionExpansionComposesWithTokenStemming() {
    final TermAnalyzingGlossaryMatcher matcher = new TermAnalyzingGlossaryMatcher(
        List.of(new GlossaryEntry("ACTION", "can not ship order")), englishStemmingAnalyzer(),
        EnglishContractionCharSequenceNormalizer.getInstance());

    final String text = "can't ship orders";
    final List<GlossaryMatch> matches = matcher.match(text);

    Assertions.assertEquals(1, matches.size());
    Assertions.assertEquals(new Span(0, 17), matches.get(0).span());
    Assertions.assertEquals(text,
        text.substring(matches.get(0).span().getStart(), matches.get(0).span().getEnd()));
  }

  /** The explicit pre-tokenization normalizer is required on the three-argument path. */
  @Test
  void testRejectsNullPreTokenizationNormalizer() {
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new TermAnalyzingGlossaryMatcher(
            List.of(new GlossaryEntry("TERM", "term")), englishStemmingAnalyzer(), null));
  }
}
