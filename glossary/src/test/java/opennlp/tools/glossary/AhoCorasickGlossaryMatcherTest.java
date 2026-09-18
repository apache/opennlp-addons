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
import org.junit.jupiter.params.provider.CsvSource;

import opennlp.tools.util.Span;
import opennlp.tools.util.normalizer.DashCharSequenceNormalizer;
import opennlp.tools.util.normalizer.EnglishContractionCharSequenceNormalizer;
import opennlp.tools.util.normalizer.FullCaseFoldCharSequenceNormalizer;
import opennlp.tools.util.normalizer.GermanUmlautCharSequenceNormalizer;
import opennlp.tools.util.normalizer.InvisibleCharSequenceNormalizer;
import opennlp.tools.util.normalizer.OffsetAwareNormalizer;
import opennlp.tools.util.normalizer.TextNormalizer;
import opennlp.tools.util.normalizer.WhitespaceCharSequenceNormalizer;

/** Tests character matching, word boundaries, and normalized source offsets. */
public class AhoCorasickGlossaryMatcherTest {

  /** A case-sensitive one-term glossary, shared by the word boundary tests. */
  private static final AhoCorasickGlossaryMatcher CAT_MATCHER =
      new AhoCorasickGlossaryMatcher(List.of(new GlossaryEntry("CAT", "cat")), false);

  /**
   * Finds registered phrases and single words in source order.
   */
  @Test
  void testFindsSingleAndMultiwordTerms() {
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(List.of(
        new GlossaryEntry("Q60", "New York City"),
        new GlossaryEntry("Q11299", "Manhattan")), false);

    final String text = "She moved from Manhattan to New York City last year.";
    final List<GlossaryMatch> matches = matcher.match(text);

    Assertions.assertEquals(2, matches.size());
    Assertions.assertEquals("Q11299", matches.get(0).id());
    Assertions.assertEquals("Manhattan", text.substring(
        matches.get(0).span().getStart(), matches.get(0).span().getEnd()));
    Assertions.assertEquals("Q60", matches.get(1).id());
    Assertions.assertEquals("New York City", text.substring(
        matches.get(1).span().getStart(), matches.get(1).span().getEnd()));
  }

  /**
   * Selects the longest match at a shared start offset.
   */
  @Test
  void testPrefersLongestMatch() {
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(List.of(
        new GlossaryEntry("STATE", "New York"),
        new GlossaryEntry("CITY", "New York City")), false);

    final List<GlossaryMatch> matches = matcher.match("Flights to New York City are full.");

    Assertions.assertEquals(1, matches.size());
    Assertions.assertEquals("CITY", matches.get(0).id());
  }

  /**
   * Prefers the earlier start when phrases overlap.
   */
  @Test
  void testLeftmostWinsOverLaterOverlap() {
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(List.of(
        new GlossaryEntry("A", "New York"),
        new GlossaryEntry("B", "York City")), false);

    final List<GlossaryMatch> matches = matcher.match("in New York City today");

    Assertions.assertEquals(1, matches.size());
    Assertions.assertEquals("A", matches.get(0).id());
  }

  /**
   * Preserves source offsets and the registered term when case is ignored.
   */
  @Test
  void testIgnoreCaseKeepsOriginalSpan() {
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("ML", "machine learning")), true);

    final String text = "Machine Learning is popular.";
    final List<GlossaryMatch> matches = matcher.match(text);

    Assertions.assertEquals(1, matches.size());
    Assertions.assertEquals(0, matches.get(0).span().getStart());
    Assertions.assertEquals("Machine Learning".length(), matches.get(0).span().getEnd());
    Assertions.assertEquals("machine learning", matches.get(0).term());
  }

  /**
   * Rejects case differences when ignoreCase is disabled.
   */
  @Test
  void testCaseSensitiveByDefaultDoesNotCrossCase() {
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("ML", "machine learning")), false);

    Assertions.assertTrue(matcher.match("Machine Learning is popular.").isEmpty());
  }

  /**
   * Matches complete words at punctuation and text limits.
   *
   * @param text The source text.
   * @param expectedHits The expected match count.
   */
  @ParameterizedTest
  @CsvSource({
      "concatenate the files, 0",
      "the cat sleeps, 1",
      "cat, 1",
      "a cat., 1"
  })
  void testRespectsWordBoundaries(String text, int expectedHits) {
    Assertions.assertEquals(expectedHits, CAT_MATCHER.match(text).size());
  }

  /**
   * Reports distinct registered aliases under a shared identifier.
   */
  @Test
  void testAliasesShareOneId() {
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(List.of(
        new GlossaryEntry("ACME-1", "widget press"),
        new GlossaryEntry("ACME-1", "press for widgets")), false);

    final List<GlossaryMatch> matches =
        matcher.match("The widget press replaced the old press for widgets.");

    Assertions.assertEquals(2, matches.size());
    Assertions.assertEquals("ACME-1", matches.get(0).id());
    Assertions.assertEquals("ACME-1", matches.get(1).id());
    Assertions.assertEquals("widget press", matches.get(0).term());
    Assertions.assertEquals("press for widgets", matches.get(1).term());
  }

  /**
   * Uses registration order for duplicate terms.
   */
  @Test
  void testDuplicateTermFirstRegistrationWins() {
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(List.of(
        new GlossaryEntry("FIRST", "python"),
        new GlossaryEntry("SECOND", "python")), false);

    final List<GlossaryMatch> matches = matcher.match("a python slithered by");

    Assertions.assertEquals(1, matches.size());
    Assertions.assertEquals("FIRST", matches.get(0).id());
  }

  /**
   * Reports repeated occurrences when case is ignored.
   */
  @Test
  void testRepeatedHitsAllReported() {
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("T", "gene therapy")), true);

    Assertions.assertEquals(2,
        matcher.match("Gene therapy trials expand; gene therapy works.").size());
  }

  /**
   * Returns correct offsets at the start and end of the input.
   */
  @Test
  void testTermsAtTextStartAndEndReportExactSpans() {
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(List.of(
        new GlossaryEntry("ML", "machine learning"),
        new GlossaryEntry("TTS", "speech synthesis")), false);

    final String text = "machine learning powers speech synthesis";
    final List<GlossaryMatch> matches = matcher.match(text);

    Assertions.assertEquals(2, matches.size());
    Assertions.assertEquals(0, matches.get(0).span().getStart());
    Assertions.assertEquals(16, matches.get(0).span().getEnd());
    Assertions.assertEquals("ML", matches.get(0).id());
    Assertions.assertEquals(24, matches.get(1).span().getStart());
    Assertions.assertEquals(40, matches.get(1).span().getEnd());
    Assertions.assertEquals(text.length(), matches.get(1).span().getEnd());
    Assertions.assertEquals("TTS", matches.get(1).id());
  }

  /**
   * Reports a prefix term only where a longer match does not cover it.
   */
  @Test
  void testPrefixTermSuppressedInsideLongerMatchButReportedAlone() {
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(List.of(
        new GlossaryEntry("G", "gene"),
        new GlossaryEntry("GT", "gene therapy")), false);

    final List<GlossaryMatch> matches =
        matcher.match("gene therapy advanced; the gene won.");

    Assertions.assertEquals(2, matches.size());
    Assertions.assertEquals("GT", matches.get(0).id());
    Assertions.assertEquals(0, matches.get(0).span().getStart());
    Assertions.assertEquals(12, matches.get(0).span().getEnd());
    Assertions.assertEquals("G", matches.get(1).id());
    Assertions.assertEquals(27, matches.get(1).span().getStart());
    Assertions.assertEquals(31, matches.get(1).span().getEnd());
  }

  /**
   * Reports an inner term only where a longer match does not cover it.
   */
  @Test
  void testInnerTermSuppressedInsideLongerMatchButReportedAlone() {
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(List.of(
        new GlossaryEntry("Q60", "New York City"),
        new GlossaryEntry("Q1384", "York")), false);

    final List<GlossaryMatch> matches =
        matcher.match("York is old; New York City is new.");

    Assertions.assertEquals(2, matches.size());
    Assertions.assertEquals("Q1384", matches.get(0).id());
    Assertions.assertEquals(0, matches.get(0).span().getStart());
    Assertions.assertEquals(4, matches.get(0).span().getEnd());
    Assertions.assertEquals("Q60", matches.get(1).id());
    Assertions.assertEquals(13, matches.get(1).span().getStart());
    Assertions.assertEquals(26, matches.get(1).span().getEnd());
  }

  /**
   * Returns source offsets for repeated occurrences in text order.
   */
  @Test
  void testSameTermTwiceReportsBothOccurrences() {
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("DL", "data lake")), false);

    final String text = "data lake governance beats data lake chaos";
    final List<GlossaryMatch> matches = matcher.match(text);

    Assertions.assertEquals(2, matches.size());
    Assertions.assertEquals(0, matches.get(0).span().getStart());
    Assertions.assertEquals(9, matches.get(0).span().getEnd());
    Assertions.assertEquals(27, matches.get(1).span().getStart());
    Assertions.assertEquals(36, matches.get(1).span().getEnd());
    Assertions.assertEquals("data lake",
        text.substring(matches.get(1).span().getStart(), matches.get(1).span().getEnd()));
  }

  /**
   * Returns an empty list for empty input.
   */
  @Test
  void testEmptyTextYieldsNoMatches() {
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("T", "term")), false);

    Assertions.assertTrue(matcher.match("").isEmpty());
  }

  /**
   * Counts supplementary characters as UTF-16 surrogate pairs in source offsets.
   */
  @Test
  void testSpansCountUtf16CharsForSupplementaryCharacters() {
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("PAD", "launch pad")), false);

    // Two rocket emoji (U+1F680), each one surrogate pair, precede the term.
    final String text = "\uD83D\uDE80\uD83D\uDE80 launch pad ready";
    final List<GlossaryMatch> matches = matcher.match(text);

    Assertions.assertEquals(1, matches.size());
    Assertions.assertEquals(5, matches.get(0).span().getStart());
    Assertions.assertEquals(15, matches.get(0).span().getEnd());
    Assertions.assertEquals("launch pad",
        text.substring(matches.get(0).span().getStart(), matches.get(0).span().getEnd()));
  }

  /**
   * Returns source offsets for a phrase containing an accented letter.
   */
  @Test
  void testAccentedTermMatchesWithExactSpan() {
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("CAF", "caf\u00E9 au lait")), false);

    final String text = "I ordered caf\u00E9 au lait today.";
    final List<GlossaryMatch> matches = matcher.match(text);

    Assertions.assertEquals(1, matches.size());
    Assertions.assertEquals(10, matches.get(0).span().getStart());
    Assertions.assertEquals(22, matches.get(0).span().getEnd());
    Assertions.assertEquals("caf\u00E9 au lait",
        text.substring(matches.get(0).span().getStart(), matches.get(0).span().getEnd()));
  }

  /**
   * Rejects terms inside alphanumeric words and accepts parenthesized terms.
   */
  @Test
  void testDigitNeighborsBlockTheBoundary() {
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("ID", "b12")), false);
    Assertions.assertTrue(matcher.match("ab123x").isEmpty());
    Assertions.assertTrue(matcher.match("4b12").isEmpty());
    final List<GlossaryMatch> matches = matcher.match("(b12)");
    Assertions.assertEquals(1, matches.size());
    Assertions.assertEquals(new Span(1, 4), matches.get(0).span());
  }

  /**
   * Treats an adjacent Deseret letter as part of the same word.
   */
  @Test
  void testSupplementaryLetterNeighborBlocksTheBoundary() {
    // U+10428, DESERET SMALL LETTER LONG I, a supplementary-plane letter
    Assertions.assertTrue(CAT_MATCHER.match("\uD801\uDC28cat").isEmpty());
    Assertions.assertTrue(CAT_MATCHER.match("cat\uD801\uDC28").isEmpty());
    Assertions.assertEquals(1, CAT_MATCHER.match("a \uD801\uDC28 cat").size());
  }

  /**
   * Applies word boundaries before choosing among overlapping phrases.
   */
  @Test
  void testBoundaryRejectedLongerCandidateLetsTheShorterTermThrough() {
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("LONG", "launch pad"),
            new GlossaryEntry("SHORT", "launch")), false);
    final List<GlossaryMatch> matches = matcher.match("launch pads ready");
    Assertions.assertEquals(1, matches.size());
    Assertions.assertEquals("SHORT", matches.get(0).id());
    Assertions.assertEquals(new Span(0, 6), matches.get(0).span());
  }

  /**
   * Supports accented case pairs without treating sharp s as {@code ss}.
   */
  @Test
  void testNonAsciiCasePairFoldsAndMultiCharacterFoldingDoesNot() {
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("RES", "r\u00E9sum\u00E9")), true);
    final List<GlossaryMatch> matches = matcher.match("R\u00C9SUM\u00C9 attached");
    Assertions.assertEquals(1, matches.size());
    Assertions.assertEquals(new Span(0, 6), matches.get(0).span());

    final AhoCorasickGlossaryMatcher sharp = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("ST", "strasse")), true);
    // U+00DF requires full case normalization for expansion to ss.
    Assertions.assertTrue(sharp.match("stra\u00DFe").isEmpty());
  }

  /**
   * Maps German sharp-s expansion back to the source characters.
   */
  @Test
  void testOffsetAwareNormalizerMatchesEszettAndKeepsOriginalSpan() {
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("ST", "strasse")), false,
        GermanUmlautCharSequenceNormalizer.getInstance());

    final String text = "die stra\u00DFe hier";
    final List<GlossaryMatch> matches = matcher.match(text);

    Assertions.assertEquals(1, matches.size());
    Assertions.assertEquals("ST", matches.get(0).id());
    Assertions.assertEquals(new Span(4, 10), matches.get(0).span());
    Assertions.assertEquals("stra\u00DFe",
        text.substring(matches.get(0).span().getStart(), matches.get(0).span().getEnd()));
  }

  /**
   * Matches umlauts and their ASCII expansions with source offsets.
   */
  @Test
  void testGermanUmlautNormalizerMatchesUmlautAndAsciiSpellings() {
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("MUE", "mueller"),
            new GlossaryEntry("KOE", "koeln")), false,
        GermanUmlautCharSequenceNormalizer.getInstance());

    final String text = "mueller and m\u00FCller visit k\u00F6ln";
    final List<GlossaryMatch> matches = matcher.match(text);

    Assertions.assertEquals(3, matches.size());
    Assertions.assertEquals("MUE", matches.get(0).id());
    Assertions.assertEquals(new Span(0, 7), matches.get(0).span());
    Assertions.assertEquals("MUE", matches.get(1).id());
    Assertions.assertEquals("m\u00FCller",
        text.substring(matches.get(1).span().getStart(), matches.get(1).span().getEnd()));
    Assertions.assertEquals("KOE", matches.get(2).id());
    Assertions.assertEquals("k\u00F6ln",
        text.substring(matches.get(2).span().getStart(), matches.get(2).span().getEnd()));
  }

  /**
   * Normalizes registered sharp-s terms as well as input text.
   */
  @Test
  void testTermRegisteredWithEszettMatchesAsciiSsText() {
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("ST", "stra\u00DFe")), false,
        GermanUmlautCharSequenceNormalizer.getInstance());

    final List<GlossaryMatch> matches = matcher.match("see strasse tonight");
    Assertions.assertEquals(1, matches.size());
    Assertions.assertEquals(new Span(4, 11), matches.get(0).span());
    Assertions.assertEquals("stra\u00DFe", matches.get(0).term());
  }

  /**
   * Uses full case normalization to match capitalized sharp-s text.
   */
  @Test
  void testFullCaseFoldNormalizerMatchesCapitalizedEszettForm() {
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("ST", "strasse")), false,
        FullCaseFoldCharSequenceNormalizer.getInstance());

    final String text = "Stra\u00DFe ahead";
    final List<GlossaryMatch> matches = matcher.match(text);

    Assertions.assertEquals(1, matches.size());
    Assertions.assertEquals(new Span(0, 6), matches.get(0).span());
    Assertions.assertEquals("Stra\u00DFe",
        text.substring(matches.get(0).span().getStart(), matches.get(0).span().getEnd()));
  }

  /**
   * Maps capital sharp-s expansion at the text limits.
   */
  @Test
  void testCapitalEszettAtTextEdgesMapsTightOriginalSpans() {
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("ST", "strasse")), true,
        GermanUmlautCharSequenceNormalizer.getInstance());

    final String start = "STRA\u1E9EE";
    final List<GlossaryMatch> atStart = matcher.match(start);
    Assertions.assertEquals(1, atStart.size());
    Assertions.assertEquals(new Span(0, start.length()), atStart.get(0).span());

    final String end = "x STRA\u1E9EE";
    final List<GlossaryMatch> atEnd = matcher.match(end);
    Assertions.assertEquals(1, atEnd.size());
    Assertions.assertEquals(new Span(2, end.length()), atEnd.get(0).span());
  }

  /**
   * Applies case comparison after German character expansion.
   */
  @Test
  void testIgnoreCaseAppliesAfterOffsetAwareFold() {
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("ST", "strasse")), true,
        GermanUmlautCharSequenceNormalizer.getInstance());

    final String text = "Die Stra\u00DFe ist frei.";
    final List<GlossaryMatch> matches = matcher.match(text);
    Assertions.assertEquals(1, matches.size());
    Assertions.assertEquals("Stra\u00DFe",
        text.substring(matches.get(0).span().getStart(), matches.get(0).span().getEnd()));
  }

  /**
   * Maps offsets through invisible-character removal, whitespace normalization, and dash conversion.
   */
  @Test
  void testAlignedPipelineNormalizerMapsHitsAcrossCollapseAndDashFold() {
    final OffsetAwareNormalizer pipeline = TextNormalizer.builder()
        .stripInvisible().whitespace().dashes().buildAligned();
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("NY", "New York"),
            new GlossaryEntry("AB", "a-b")), false, pipeline);

    final String zwsp = "\u200B";
    final String text = "New" + zwsp + "  York and a\u2014b done";
    final List<GlossaryMatch> matches = matcher.match(text);

    Assertions.assertEquals(2, matches.size());
    Assertions.assertEquals("NY", matches.get(0).id());
    Assertions.assertEquals("New" + zwsp + "  York",
        text.substring(matches.get(0).span().getStart(), matches.get(0).span().getEnd()));
    Assertions.assertEquals("AB", matches.get(1).id());
    Assertions.assertEquals("a\u2014b",
        text.substring(matches.get(1).span().getStart(), matches.get(1).span().getEnd()));
  }

  /**
   * Excludes surrounding whitespace from the mapped match.
   */
  @Test
  void testWhitespaceCollapseDoesNotExpandSpanIntoNeighborSpaces() {
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("NY", "New York")), false,
        WhitespaceCharSequenceNormalizer.getInstance());

    final String text = "  New   York  ";
    final List<GlossaryMatch> matches = matcher.match(text);
    Assertions.assertEquals(1, matches.size());
    Assertions.assertEquals(new Span(2, 12), matches.get(0).span());
    Assertions.assertEquals("New   York",
        text.substring(matches.get(0).span().getStart(), matches.get(0).span().getEnd()));
  }

  /**
   * Reports the longer phrase after sharp-s expansion.
   */
  @Test
  void testLongestMatchWinsAfterLengthChangingFold() {
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("SHORT", "strasse"),
            new GlossaryEntry("LONG", "strassebahn")), false,
        GermanUmlautCharSequenceNormalizer.getInstance());

    final String text = "stra\u00DFebahn kommt";
    final List<GlossaryMatch> matches = matcher.match(text);
    Assertions.assertEquals(1, matches.size());
    Assertions.assertEquals("LONG", matches.get(0).id());
    Assertions.assertEquals("stra\u00DFebahn",
        text.substring(matches.get(0).span().getStart(), matches.get(0).span().getEnd()));
  }

  /**
   * Uses registration order when normalization makes terms equal.
   */
  @Test
  void testDuplicateFoldedTermsKeepRegistrationOrder() {
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("FIRST", "strasse"),
            new GlossaryEntry("SECOND", "stra\u00DFe"),
            new GlossaryEntry("CITY", "koeln")), false,
        GermanUmlautCharSequenceNormalizer.getInstance());

    final List<GlossaryMatch> matches = matcher.match("strasse in k\u00F6ln");
    Assertions.assertEquals(2, matches.size());
    Assertions.assertEquals("FIRST", matches.get(0).id());
    Assertions.assertEquals("CITY", matches.get(1).id());
  }

  /**
   * Requires a normalizer when the normalizing constructor is selected.
   */
  @Test
  void testThreeArgumentConstructorRejectsNullNormalizer() {
    final List<GlossaryEntry> glossary = List.of(new GlossaryEntry("ML", "machine learning"));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new AhoCorasickGlossaryMatcher(glossary, true, null));
    Assertions.assertFalse(
        new AhoCorasickGlossaryMatcher(glossary, true).match("Machine Learning").isEmpty());
  }

  /**
   * Rejects normalized candidates inside longer source words.
   */
  @Test
  void testNormalizerHitStillRespectsOriginalWordBoundaries() {
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("ST", "strasse")), false,
        GermanUmlautCharSequenceNormalizer.getInstance());

    Assertions.assertTrue(matcher.match("superstra\u00DFe").isEmpty());
    Assertions.assertTrue(matcher.match("stra\u00DFe9").isEmpty());
    Assertions.assertTrue(matcher.match("9stra\u00DFe").isEmpty());
    Assertions.assertEquals(1, matcher.match("stra\u00DFe").size());
    Assertions.assertEquals(1, matcher.match("(stra\u00DFe)").size());
  }

  /**
   * Rejects registrations containing only removable invisible characters.
   */
  @Test
  void testRejectsTermThatNormalizesToBlank() {
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new AhoCorasickGlossaryMatcher(
            List.of(new GlossaryEntry("Z", "\u200B")), false,
            InvisibleCharSequenceNormalizer.getInstance()));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new AhoCorasickGlossaryMatcher(
            List.of(new GlossaryEntry("Z2", "\u200B\u200B\u200B")), false,
            InvisibleCharSequenceNormalizer.getInstance()));
  }

  /**
   * Validates registrations and input text on the normalizing path.
   */
  @Test
  void testThreeArgumentConstructorValidatesGlossaryAndText() {
    final OffsetAwareNormalizer fold = GermanUmlautCharSequenceNormalizer.getInstance();
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new AhoCorasickGlossaryMatcher(null, false, fold));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new AhoCorasickGlossaryMatcher(List.of(), false, fold));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new AhoCorasickGlossaryMatcher(Collections.singletonList(null), false, fold));
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("ST", "strasse")), false, fold);
    Assertions.assertThrows(IllegalArgumentException.class, () -> matcher.match(null));
  }

  /**
   * Supports standalone dash and whitespace normalizers.
   */
  @Test
  void testDashAndWhitespaceNormalizersAlone() {
    final AhoCorasickGlossaryMatcher dashes = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("AB", "a-b")), false,
        DashCharSequenceNormalizer.getInstance());
    Assertions.assertEquals(1, dashes.match("see a\u2013b now").size());
    Assertions.assertEquals(1, dashes.match("see a\u2014b now").size());
    Assertions.assertEquals(1, dashes.match("see a-b now").size());
    Assertions.assertTrue(dashes.match("see a b now").isEmpty());

    final AhoCorasickGlossaryMatcher spaces = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("NY", "New York")), false,
        WhitespaceCharSequenceNormalizer.getInstance());
    final String text = "visit New   York today";
    final List<GlossaryMatch> matches = spaces.match(text);
    Assertions.assertEquals(1, matches.size());
    Assertions.assertEquals("New   York",
        text.substring(matches.get(0).span().getStart(), matches.get(0).span().getEnd()));
    Assertions.assertTrue(spaces.match("visit NewYork today").isEmpty());
  }

  /**
   * Returns normalized matches in source order and accepts empty input.
   */
  @Test
  void testMultipleFoldedHitsPreserveOrderAndEmptyTextIsEmpty() {
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("ST", "strasse"),
            new GlossaryEntry("MUE", "mueller")), false,
        GermanUmlautCharSequenceNormalizer.getInstance());

    Assertions.assertTrue(matcher.match("").isEmpty());
    final String text = "stra\u00DFe dann m\u00FCller";
    final List<GlossaryMatch> matches = matcher.match(text);
    Assertions.assertEquals(2, matches.size());
    Assertions.assertEquals("ST", matches.get(0).id());
    Assertions.assertEquals("MUE", matches.get(1).id());
    Assertions.assertTrue(matches.get(0).span().getEnd() <= matches.get(1).span().getStart());
  }

  /**
   * Matches Deseret capital and lowercase forms with UTF-16 offsets.
   */
  @Test
  void testSupplementaryCasePairFoldsWhenIgnoringCase() {
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("DES", "\uD801\uDC28")), true);
    final List<GlossaryMatch> matches = matcher.match("- \uD801\uDC00 -");
    Assertions.assertEquals(1, matches.size());
    Assertions.assertEquals(new Span(2, 4), matches.get(0).span());
  }

  /**
   * Finds Tokyo in unspaced Japanese text.
   */
  @Test
  void testUnspacedHanTermMatchesInsideJapaneseSentence() {
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("Q1490", "\u6771\u4EAC")), false);

    // watashi wa Tokyo ni sumu, no spaces anywhere
    final String text = "\u79C1\u306F\u6771\u4EAC\u306B\u4F4F\u3080";
    final List<GlossaryMatch> matches = matcher.match(text);

    Assertions.assertEquals(1, matches.size());
    Assertions.assertEquals("Q1490", matches.get(0).id());
    Assertions.assertEquals(new Span(2, 4), matches.get(0).span());
    Assertions.assertEquals("\u6771\u4EAC",
        text.substring(matches.get(0).span().getStart(), matches.get(0).span().getEnd()));
  }

  /**
   * Accepts Han ideograph boundaries within an unspaced character sequence.
   */
  @Test
  void testHanTermInsideLongerHanRunMatches() {
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("Q148", "\u4E2D\u56FD")), false);

    // wo ai zhongguoren, no spaces
    final String text = "\u6211\u7231\u4E2D\u56FD\u4EBA";
    final List<GlossaryMatch> matches = matcher.match(text);

    Assertions.assertEquals(1, matches.size());
    Assertions.assertEquals(new Span(2, 4), matches.get(0).span());
  }

  /**
   * Accepts boundaries between Latin letters and Han ideographs.
   */
  @Test
  void testLatinNeighborDoesNotBlockHanHit() {
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("Q1490", "\u6771\u4EAC")), false);

    final String text = "visit\u6771\u4EACnow";
    final List<GlossaryMatch> matches = matcher.match(text);

    Assertions.assertEquals(1, matches.size());
    Assertions.assertEquals(new Span(5, 7), matches.get(0).span());
  }

  /**
   * Matches a hiragana term using character boundaries.
   */
  @Test
  void testHiraganaTermMatchesInsideUnspacedHiraganaRun() {
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("GREET", "\u304A\u306F\u3088\u3046")), false);

    // ohayou gozaimasu, no spaces
    final String text = "\u304A\u306F\u3088\u3046\u3054\u3056\u3044\u307E\u3059";
    final List<GlossaryMatch> matches = matcher.match(text);

    Assertions.assertEquals(1, matches.size());
    Assertions.assertEquals(new Span(0, 4), matches.get(0).span());
  }

  /**
   * Rejects a term inside longer katakana text but accepts a preceding Han boundary.
   */
  @Test
  void testKatakanaRunStillHidesEmbeddedTerm() {
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("TOWER", "\u30BF\u30EF\u30FC")), false);

    // toukyou tawaa as one katakana run: no hit inside it
    Assertions.assertTrue(
        matcher.match("\u30C8\u30A6\u30AD\u30E7\u30A6\u30BF\u30EF\u30FC").isEmpty());
    // the same term bounded by Han neighbors is a word of its own
    final String bounded = "\u6771\u4EAC\u30BF\u30EF\u30FC";
    final List<GlossaryMatch> matches = matcher.match(bounded);
    Assertions.assertEquals(1, matches.size());
    Assertions.assertEquals(new Span(2, 5), matches.get(0).span());
  }

  /**
   * Rejects a term inside longer Hangul text but accepts a space boundary.
   */
  @Test
  void testHangulRunStillHidesEmbeddedTerm() {
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("KO", "\uD55C\uAD6D")), false);

    // hangugeo as one Hangul run: no hit inside it
    Assertions.assertTrue(matcher.match("\uD55C\uAD6D\uC5B4").isEmpty());
    final List<GlossaryMatch> matches = matcher.match("\uD55C\uAD6D \uC5B4");
    Assertions.assertEquals(1, matches.size());
    Assertions.assertEquals(new Span(0, 2), matches.get(0).span());
  }

  /**
   * Checks Han source boundaries when a normalizer is configured.
   */
  @Test
  void testHanBoundaryAppliesToNormalizedHitsInOriginalCoordinates() {
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("Q1490", "\u6771\u4EAC")), false,
        WhitespaceCharSequenceNormalizer.getInstance());

    final String text = "\u79C1\u306F\u6771\u4EAC\u306B";
    final List<GlossaryMatch> matches = matcher.match(text);
    Assertions.assertEquals(1, matches.size());
    Assertions.assertEquals(new Span(2, 4), matches.get(0).span());
  }

  /**
   * Rejects a prefix ending inside an English contraction.
   */
  @Test
  void testContractionInteriorIsNotAWordBoundary() {
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("PREFIX", "can")), false);

    Assertions.assertTrue(matcher.match("can't").isEmpty());
  }

  /**
   * Returns the source contraction span for an expanded phrase.
   *
   * @param term The registered expansion.
   * @param text The contracted source text.
   * @param expectedStart The source start offset.
   * @param expectedEnd The source end offset.
   */
  @ParameterizedTest
  @CsvSource(value = {
      "can not|can't|0|5",
      "will not|won't|0|5",
      "we are|we\u2019re|0|5"
  }, delimiter = '|')
  void testOffsetAwareEnglishContractionExpansion(String term, String text,
      int expectedStart, int expectedEnd) {
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("EXPANDED", term)), true,
        EnglishContractionCharSequenceNormalizer.getInstance());

    final List<GlossaryMatch> matches = matcher.match(text);

    Assertions.assertEquals(1, matches.size());
    Assertions.assertEquals(new Span(expectedStart, expectedEnd), matches.get(0).span());
    Assertions.assertEquals(text,
        text.substring(matches.get(0).span().getStart(), matches.get(0).span().getEnd()));
  }

  /**
   * Rejects matches that cover only part of a source contraction.
   *
   * @param term The registered fragment.
   * @param text The contraction.
   */
  @ParameterizedTest
  @CsvSource(value = {
      "can|can't",
      "not|can't",
      "will|won't"
  }, delimiter = '|')
  void testContractionExpansionDoesNotExposeSubwordHit(String term, String text) {
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("PREFIX", term)), false,
        EnglishContractionCharSequenceNormalizer.getInstance());

    Assertions.assertTrue(matcher.match(text).isEmpty());
  }

  /**
   * Does not expand ambiguous apostrophe-s forms.
   */
  @Test
  void testContractionExpansionLeavesAmbiguousSuffixUnmatched() {
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("AMBIGUOUS", "he is")), false,
        EnglishContractionCharSequenceNormalizer.getInstance());

    Assertions.assertTrue(matcher.match("he's").isEmpty());
  }

  /**
   * Rejects a match ending before a combining mark or variation selector.
   *
   * @param term The registered prefix.
   * @param text The source including the extending mark.
   */
  @ParameterizedTest
  @CsvSource(value = {
      "cafe|cafe\u0301",
      "\u845B|\u845B\uFE00"
  }, delimiter = '|')
  void testExtendingMarkStaysWithItsBase(String term, String text) {
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("PART", term)), false);

    Assertions.assertTrue(matcher.match(text).isEmpty());
  }

  /**
   * Rejects prefixes within connector-delimited words and acronyms.
   *
   * @param term The registered prefix.
   * @param text The complete source word.
   */
  @ParameterizedTest
  @CsvSource(value = {
      "cat|cat_food",
      "U|U.S.A"
  }, delimiter = '|')
  void testUaxWordJoiningPunctuationBlocksPartialHit(String term, String text) {
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("PART", term)), false);

    Assertions.assertTrue(matcher.match(text).isEmpty());
  }

  /**
   * Checks punctuation boundaries before spaces and Unicode letters.
   *
   * @param text The punctuated source text.
   * @param expectedMatches The expected match count.
   */
  @ParameterizedTest
  @CsvSource(value = {
      "cat.|1",
      "cat. dog|1",
      "cat.\u00E9|0",
      "cat,\u00E9|1",
      "cat:\u00E9|0"
  }, delimiter = '|')
  void testMidWordPunctuationBoundaryDependsOnFollowingLetter(String text,
      int expectedMatches) {
    Assertions.assertEquals(expectedMatches, CAT_MATCHER.match(text).size());
  }

  /**
   * Checks initial punctuation and punctuation joining Unicode letters.
   *
   * @param text The punctuated source text.
   * @param expectedMatches The expected match count.
   */
  @ParameterizedTest
  @CsvSource(value = {
      ".cat|1",
      "\u00E9.cat|0"
  }, delimiter = '|')
  void testMidWordPunctuationBoundaryDependsOnPrecedingLetter(String text,
      int expectedMatches) {
    Assertions.assertEquals(expectedMatches, CAT_MATCHER.match(text).size());
  }

  /**
   * Rejects a match ending inside a joined emoji sequence.
   */
  @Test
  void testEmojiZwjSequenceDoesNotExposeAnInteriorBoundary() {
    final String man = "\uD83D\uDC68";
    final String familyPrefix = man + "\u200D\uD83D\uDC69";
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("MAN", man)), false);

    Assertions.assertTrue(matcher.match(familyPrefix).isEmpty());
  }

  /**
   * Returns UTF-16 offsets for a supplementary Han ideograph.
   */
  @Test
  void testSupplementaryHanTermMatchesBetweenHanNeighbors() {
    final String term = "\uD840\uDC00"; // U+20000
    final String text = "\u4E00" + term + "\u4E8C";
    final AhoCorasickGlossaryMatcher matcher = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("SUPPLEMENTARY", term)), false);

    final List<GlossaryMatch> matches = matcher.match(text);

    Assertions.assertEquals(1, matches.size());
    Assertions.assertEquals(new Span(1, 3), matches.get(0).span());
  }

  /**
   * Rejects invalid registration collections and null input.
   */
  @Test
  void testInvalidMatcherArguments() {
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new AhoCorasickGlossaryMatcher(null, false));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new AhoCorasickGlossaryMatcher(List.of(), false));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new AhoCorasickGlossaryMatcher(Collections.singletonList(null), false));
    Assertions.assertThrows(IllegalArgumentException.class, () -> CAT_MATCHER.match(null));
  }

  /**
   * Rejects missing or blank entry fields.
   */
  @Test
  void testInvalidGlossaryEntryArguments() {
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new GlossaryEntry(null, "term"));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new GlossaryEntry("", "term"));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new GlossaryEntry(" ", "term"));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new GlossaryEntry("id", null));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new GlossaryEntry("id", ""));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new GlossaryEntry("id", " "));
  }

  /**
   * Rejects missing spans and missing or blank match fields.
   */
  @Test
  void testInvalidGlossaryMatchArguments() {
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new GlossaryMatch(null, "id", "term"));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new GlossaryMatch(new Span(0, 4), null, "term"));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new GlossaryMatch(new Span(0, 4), " ", "term"));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new GlossaryMatch(new Span(0, 4), "id", null));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new GlossaryMatch(new Span(0, 4), "id", " "));
  }
}
