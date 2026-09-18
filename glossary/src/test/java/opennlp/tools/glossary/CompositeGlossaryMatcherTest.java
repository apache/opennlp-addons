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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import opennlp.tools.util.Span;

import static opennlp.tools.glossary.GlossaryTestSupport.englishStemmingAnalyzer;

/**
 * Tests delegate priority, overlap selection, and source ordering.
 */
public class CompositeGlossaryMatcherTest {

  /**
   * Prefers exact matches when the exact matcher has higher priority.
   */
  @Test
  void testExactWinsOverInflectedOnSameStretch() {
    final AhoCorasickGlossaryMatcher exact = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("EXACT", "hot dog")), true);
    final TermAnalyzingGlossaryMatcher inflected = new TermAnalyzingGlossaryMatcher(
        List.of(new GlossaryEntry("INFLECTED", "hot dog")),
        englishStemmingAnalyzer());
    final CompositeGlossaryMatcher composite = new CompositeGlossaryMatcher(
        List.of(exact, inflected));

    final List<GlossaryMatch> matches = composite.match("hot dog");

    Assertions.assertEquals(1, matches.size());
    Assertions.assertEquals("EXACT", matches.get(0).id());
    Assertions.assertEquals("hot dog", matches.get(0).term());
    Assertions.assertEquals(new Span(0, 7), matches.get(0).span());
  }

  /**
   * Includes inflected matches where they do not overlap exact matches.
   */
  @Test
  void testInflectedFillsGapsExactMisses() {
    final List<GlossaryEntry> glossary = List.of(
        new GlossaryEntry("FOOD", "hot dog"),
        new GlossaryEntry("CITY", "New York City"));
    final AhoCorasickGlossaryMatcher exact =
        new AhoCorasickGlossaryMatcher(glossary, true);
    final TermAnalyzingGlossaryMatcher inflected =
        new TermAnalyzingGlossaryMatcher(glossary, englishStemmingAnalyzer());
    final CompositeGlossaryMatcher composite = new CompositeGlossaryMatcher(
        List.of(exact, inflected));

    final String text = "hot dogs in New York City";
    final List<GlossaryMatch> matches = composite.match(text);

    Assertions.assertEquals(2, matches.size());
    Assertions.assertEquals("FOOD", matches.get(0).id());
    Assertions.assertEquals("hot dog", matches.get(0).term());
    Assertions.assertEquals(new Span(0, 8), matches.get(0).span());
    Assertions.assertEquals("CITY", matches.get(1).id());
    Assertions.assertEquals("New York City", matches.get(1).term());
    Assertions.assertEquals("New York City", text.substring(
        matches.get(1).span().getStart(), matches.get(1).span().getEnd()));
  }

  /**
   * Prefers inflected matches when the inflected matcher has higher priority.
   */
  @Test
  void testInflectedWinsWhenOrderedFirst() {
    final AhoCorasickGlossaryMatcher exact = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("EXACT", "hot dog")), true);
    final TermAnalyzingGlossaryMatcher inflected = new TermAnalyzingGlossaryMatcher(
        List.of(new GlossaryEntry("INFLECTED", "hot dog")),
        englishStemmingAnalyzer());
    final CompositeGlossaryMatcher composite = new CompositeGlossaryMatcher(
        List.of(inflected, exact));

    final List<GlossaryMatch> matches = composite.match("hot dog");

    Assertions.assertEquals(1, matches.size());
    Assertions.assertEquals("INFLECTED", matches.get(0).id());
    Assertions.assertEquals("hot dog", matches.get(0).term());
    Assertions.assertEquals(new Span(0, 7), matches.get(0).span());
  }

  /**
   * Accepts adjacent, non-overlapping matches.
   */
  @Test
  void testTouchingSpansBothSurvive() {
    final GlossaryMatcher first = text -> List.of(
        new GlossaryMatch(new Span(0, 3), "A", "foo"));
    final GlossaryMatcher second = text -> List.of(
        new GlossaryMatch(new Span(3, 6), "B", "bar"));
    final CompositeGlossaryMatcher composite = new CompositeGlossaryMatcher(
        List.of(first, second));

    final List<GlossaryMatch> matches = composite.match("foobar");

    Assertions.assertEquals(2, matches.size());
    Assertions.assertEquals("A", matches.get(0).id());
    Assertions.assertEquals(new Span(0, 3), matches.get(0).span());
    Assertions.assertEquals("B", matches.get(1).id());
    Assertions.assertEquals(new Span(3, 6), matches.get(1).span());
  }

  /**
   * Rejects overlapping lower-priority spans and accepts adjacent spans.
   *
   * @param start The lower-priority start offset.
   * @param end The lower-priority end offset.
   * @param accepted Whether the lower-priority match should be included.
   */
  @ParameterizedTest
  @CsvSource({"0,10,false", "10,20,false", "7,12,false", "0,20,false",
      "5,15,false", "0,5,true", "15,20,true"})
  void testLowerPriorityOverlap(int start, int end, boolean accepted) {
    final GlossaryMatch priority = new GlossaryMatch(new Span(5, 15), "HIGH", "kept");
    final GlossaryMatcher high = text -> List.of(priority);
    final GlossaryMatch candidate = new GlossaryMatch(new Span(start, end), "LOW", "candidate");
    final GlossaryMatcher low = text -> List.of(candidate);
    final CompositeGlossaryMatcher composite = new CompositeGlossaryMatcher(
        List.of(high, low));

    final List<GlossaryMatch> matches = composite.match("01234567890123456789");

    Assertions.assertEquals(accepted ? 2 : 1, matches.size());
    Assertions.assertEquals(accepted, matches.contains(candidate));
    Assertions.assertTrue(matches.contains(priority));
  }

  /**
   * Applies Span intersection rules to zero-length matches from custom delegates.
   *
   * @param highStart The higher-priority start offset.
   * @param highEnd The higher-priority end offset.
   * @param lowStart The lower-priority start offset.
   * @param lowEnd The lower-priority end offset.
   * @param accepted Whether to include the lower-priority match.
   */
  @ParameterizedTest
  @CsvSource({"0,0,0,0,false", "0,0,0,3,false", "0,3,3,3,false", "3,3,3,6,false",
      "0,3,3,6,true", "2,4,1,1,true", "2,4,5,5,true", "0,0,1,1,true",
      "2,4,3,3,false", "2,4,2,2,false"})
  void testZeroLengthIntersections(int highStart, int highEnd, int lowStart, int lowEnd,
      boolean accepted) {
    final GlossaryMatch priority = new GlossaryMatch(new Span(highStart, highEnd), "HIGH", "high");
    final GlossaryMatch candidate = new GlossaryMatch(new Span(lowStart, lowEnd), "LOW", "low");
    Assertions.assertEquals(!accepted, priority.span().intersects(candidate.span()));
    final CompositeGlossaryMatcher matcher = new CompositeGlossaryMatcher(List.of(
        text -> List.of(priority), text -> List.of(candidate)));
    final List<GlossaryMatch> matches = matcher.match("abcdef");
    Assertions.assertTrue(matches.contains(priority));
    Assertions.assertEquals(accepted ? 2 : 1, matches.size());
    Assertions.assertEquals(accepted, matches.contains(candidate));
  }

  /**
   * Orders accepted matches by source offset, independent of delegate priority.
   */
  @Test
  void testResultOrderedByAscendingStartOffset() {
    final GlossaryMatcher high = text -> List.of(
        new GlossaryMatch(new Span(10, 15), "LATE", "later"));
    final GlossaryMatcher low = text -> List.of(
        new GlossaryMatch(new Span(0, 5), "EARLY", "earlier"));
    final CompositeGlossaryMatcher composite = new CompositeGlossaryMatcher(
        List.of(high, low));

    final List<GlossaryMatch> matches = composite.match("0123456789012345");

    Assertions.assertEquals(2, matches.size());
    Assertions.assertEquals("EARLY", matches.get(0).id());
    Assertions.assertEquals(new Span(0, 5), matches.get(0).span());
    Assertions.assertEquals("LATE", matches.get(1).id());
    Assertions.assertEquals(new Span(10, 15), matches.get(1).span());
    Assertions.assertTrue(
        matches.get(0).span().getStart() < matches.get(1).span().getStart());
  }

  /**
   * Returns the result of a single delegate.
   */
  @Test
  void testSingleDelegatePassthrough() {
    final AhoCorasickGlossaryMatcher exact = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("CITY", "New York City")), true);
    final CompositeGlossaryMatcher composite = new CompositeGlossaryMatcher(
        List.of(exact));

    final String text = "Flights to New York City are full.";
    final List<GlossaryMatch> direct = exact.match(text);
    final List<GlossaryMatch> merged = composite.match(text);

    Assertions.assertEquals(direct.size(), merged.size());
    Assertions.assertEquals(direct.get(0).id(), merged.get(0).id());
    Assertions.assertEquals(direct.get(0).term(), merged.get(0).term());
    Assertions.assertEquals(direct.get(0).span(), merged.get(0).span());
  }

  /**
   * Applies priority and source ordering to nested composites.
   */
  @Test
  void testNestedCompositeAsDelegate() {
    final GlossaryMatcher innerHigh = text -> List.of(
        new GlossaryMatch(new Span(0, 3), "INNER", "foo"));
    final GlossaryMatcher innerLow = text -> List.of(
        new GlossaryMatch(new Span(0, 3), "DROPPED", "foo"));
    final CompositeGlossaryMatcher inner = new CompositeGlossaryMatcher(
        List.of(innerHigh, innerLow));
    final GlossaryMatcher outerLow = text -> List.of(
        new GlossaryMatch(new Span(3, 6), "OUTER", "bar"));
    final CompositeGlossaryMatcher outer = new CompositeGlossaryMatcher(
        List.of(inner, outerLow));

    final List<GlossaryMatch> matches = outer.match("foobar");

    Assertions.assertEquals(2, matches.size());
    Assertions.assertEquals("INNER", matches.get(0).id());
    Assertions.assertEquals(new Span(0, 3), matches.get(0).span());
    Assertions.assertEquals("OUTER", matches.get(1).id());
    Assertions.assertEquals(new Span(3, 6), matches.get(1).span());
  }

  /**
   * Excludes duplicate matches from a repeated delegate.
   */
  @Test
  void testSameDelegateTwiceAddsNothing() {
    final GlossaryMatcher delegate = text -> List.of(
        new GlossaryMatch(new Span(0, 3), "A", "foo"));
    final CompositeGlossaryMatcher composite = new CompositeGlossaryMatcher(
        List.of(delegate, delegate));

    final List<GlossaryMatch> matches = composite.match("foobar");

    Assertions.assertEquals(1, matches.size());
    Assertions.assertEquals("A", matches.get(0).id());
    Assertions.assertEquals(new Span(0, 3), matches.get(0).span());
  }

  /**
   * Construction rejects a null list, an empty list, and a list containing null.
   */
  @Test
  void testInvalidConstructorArguments() {
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new CompositeGlossaryMatcher(null));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new CompositeGlossaryMatcher(List.of()));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new CompositeGlossaryMatcher(Collections.singletonList(null)));
  }

  /**
   * Rejects null input.
   */
  @Test
  void testNullTextThrows() {
    final CompositeGlossaryMatcher composite = new CompositeGlossaryMatcher(
        List.of(text -> List.of()));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> composite.match(null));
  }

  /**
   * Returns an empty list when no delegate finds matches.
   */
  @Test
  void testNoHitsAndEmptyTextYieldEmptyList() {
    final CompositeGlossaryMatcher composite = new CompositeGlossaryMatcher(
        List.of(text -> List.of()));
    final List<GlossaryMatch> miss = composite.match("nothing here");
    final List<GlossaryMatch> empty = composite.match("");
    Assertions.assertNotNull(miss);
    Assertions.assertNotNull(empty);
    Assertions.assertTrue(miss.isEmpty());
    Assertions.assertTrue(empty.isEmpty());
  }

  /**
   * Copies the delegate list at construction.
   */
  @Test
  void testConstructorDefensivelyCopiesDelegateList() {
    final AhoCorasickGlossaryMatcher exact = new AhoCorasickGlossaryMatcher(
        List.of(new GlossaryEntry("CITY", "New York City")), true);
    final List<GlossaryMatcher> delegates = new ArrayList<>();
    delegates.add(exact);
    final CompositeGlossaryMatcher composite =
        new CompositeGlossaryMatcher(delegates);
    delegates.clear();

    final List<GlossaryMatch> matches =
        composite.match("Flights to New York City are full.");
    Assertions.assertEquals(1, matches.size());
    Assertions.assertEquals("CITY", matches.get(0).id());
  }
}
