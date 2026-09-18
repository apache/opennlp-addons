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

package opennlp.tools.temporal;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.DocumentAnalyzer;
import opennlp.tools.temporal.TemporalExpression.Granularity;
import opennlp.tools.temporal.TemporalExpression.Origin;
import opennlp.tools.util.Span;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the recognized calendar shapes, their normalized values, and the reject cases,
 * in particular calendar validation and the month-name words that double as ordinary
 * English.
 */
public class CursorTemporalExtractorTest {

  /** The reference date every relative test resolves against; it falls in ISO week 29. */
  private static final LocalDate REFERENCE = LocalDate.of(2026, 7, 17);

  private final CursorTemporalExtractor extractor = new CursorTemporalExtractor();

  private TemporalExpression single(String text) {
    final List<TemporalExpression> mentions = extractor.extract(text);
    assertEquals(1, mentions.size(), "expected one mention in: " + text);
    return mentions.get(0);
  }

  @ParameterizedTest
  @CsvSource(delimiter = ';', value = {
      "2026-07-14; 2026-07-14; DAY",
      "July 14, 2026; 2026-07-14; DAY",
      "July 14 2026; 2026-07-14; DAY",
      "Jul 14 2026; 2026-07-14; DAY",
      "14 July 2026; 2026-07-14; DAY",
      "14th July 2026; 2026-07-14; DAY",
      "march 3rd, 1999; 1999-03-03; DAY",
      "July 2026; 2026-07; MONTH",
      "SEPTEMBER 2024; 2024-09; MONTH",
      "Q3 2024; 2024-Q3; QUARTER",
      "q1 1999; 1999-Q1; QUARTER"
  })
  void testRecognizedShapesCoverTheFullMention(String text, String value, String granularity) {
    final TemporalExpression mention = single(text);
    assertEquals(new Span(0, text.length()), mention.span(), text);
    assertEquals(value, mention.value(), text);
    assertEquals(Granularity.valueOf(granularity), mention.granularity(), text);
    assertEquals(Origin.ABSOLUTE, mention.origin(), text);
  }

  /**
   * Verifies that a full ISO 8601 timestamp is not silently dropped: the date part is
   * reported as one day mention whose span covers exactly the date, and the time part
   * is skipped rather than extracted, since sub-day granularities are out of scope.
   */
  @Test
  void testIsoTimestampYieldsTheDateAtDayGranularity() {
    final String text = "2026-07-14T09:30:00Z";
    final List<TemporalExpression> mentions = extractor.extract(text);
    assertEquals(1, mentions.size(), text);
    assertEquals(new Span(0, 10), mentions.get(0).span());
    assertEquals("2026-07-14", mentions.get(0).value());
    assertEquals(Granularity.DAY, mentions.get(0).granularity());
  }

  /**
   * Verifies the timestamp handling inside a sentence and with a numeric zone offset
   * instead of {@code Z}: still exactly one mention, covering only the date part, at
   * day granularity.
   */
  @Test
  void testIsoTimestampWithOffsetZoneInsideSentence() {
    final String text = "deployed at 2026-07-14T09:30+05:30 sharp";
    final TemporalExpression mention = single(text);
    assertEquals("2026-07-14", mention.span().getCoveredText(text).toString());
    assertEquals("2026-07-14", mention.value());
    assertEquals(Granularity.DAY, mention.granularity());
  }

  @Test
  void testMentionInsideSentenceHasExactSpan() {
    final String text = "the release shipped on 2026-07-14 after review";
    final TemporalExpression mention = single(text);
    assertEquals("2026-07-14",
        mention.span().getCoveredText(text).toString());
  }

  @Test
  void testMultipleMentions() {
    final List<TemporalExpression> mentions =
        extractor.extract("from July 2025 to Q2 2026");
    assertEquals(2, mentions.size());
    assertEquals("2025-07", mentions.get(0).value());
    assertEquals("2026-Q2", mentions.get(1).value());
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "2026-13-01",           // no thirteenth month
      "2026-02-30",           // calendar-validated
      "2026-02-29",           // 2026 is not a leap year
      "Jul. 14, 2026",        // abbreviation with a period is out of scope
      "February 30, 2026",    // calendar-validated
      "July 32 2026",         // no thirty-second day
      "it may be 100",        // month word without a calendar context
      "March onwards",        // month word without a year
      "in 2026 alone",        // bare years are out of scope
      "Q5 2024",              // no fifth quarter
      "Q3 24",                // two-digit years are out of scope
      "2026-07",              // ISO month form is out of scope
      "12026-07-14",          // year is part of a longer digit run
      "2026-07-14Tomorrow"    // a T followed by prose letters is no time of day
  })
  void testRejectedShapes(String text) {
    assertTrue(extractor.extract(text).isEmpty(), text);
  }

  /**
   * Verifies that an impossible written day is not silently repaired: calendar
   * validation rejects the day-first reading, and the scan then reports the
   * month-and-year part on its own as a month mention.
   */
  @Test
  void testImpossibleDayLeavesOnlyTheMonthMention() {
    final List<TemporalExpression> mentions = extractor.extract("31 April 2026");
    assertEquals(1, mentions.size());
    assertEquals(new Span(3, 13), mentions.get(0).span());
    assertEquals("2026-04", mentions.get(0).value());
    assertEquals(Granularity.MONTH, mentions.get(0).granularity());
  }

  /**
   * Verifies that the normalized values stay pure ASCII ISO 8601 under a default
   * locale whose numbering system is not Latin: formatting must not localize digits.
   */
  @Test
  void testNormalizedValuesAreAsciiUnderNonLatinDigitLocale() {
    final Locale saved = Locale.getDefault();
    try {
      Locale.setDefault(
          new Locale.Builder().setLanguage("ar").setExtension('u', "nu-arab").build());
      assertEquals("2026-07-14", extractor.extract("14 July 2026").get(0).value());
      assertEquals("2026-07", extractor.extract("July 2026").get(0).value());
      assertEquals("2024-Q3", extractor.extract("Q3 2024").get(0).value());
    } finally {
      Locale.setDefault(saved);
    }
  }

  /**
   * Verifies the scan around a supplementary-plane neighbor: the code point before a
   * mention is read whole, so an emoji is an ordinary boundary and the mention span
   * starts behind its surrogate pair.
   */
  @Test
  void testSupplementaryPlaneNeighborIsABoundary() {
    // U+1F5D3 (spiral calendar) takes two chars; the mention span starts behind it
    final TemporalExpression mention = single("\uD83D\uDDD32026-07-14");
    assertEquals(new Span(2, 12), mention.span());
    assertEquals("2026-07-14", mention.value());
    assertEquals(Granularity.DAY, mention.granularity());
  }

  @Test
  void testNullTextThrows() {
    assertThrows(IllegalArgumentException.class, () -> extractor.extract(null));
  }

  @Test
  void testTemporalExpressionValidation() {
    assertThrows(IllegalArgumentException.class,
        () -> new TemporalExpression(null, "2026-07", Granularity.MONTH));
    assertThrows(IllegalArgumentException.class,
        () -> new TemporalExpression(new Span(0, 1), " ", Granularity.MONTH));
    assertThrows(IllegalArgumentException.class,
        () -> new TemporalExpression(new Span(0, 1), "2026-07", null));
    assertThrows(IllegalArgumentException.class,
        () -> new TemporalExpression(new Span(0, 1), "2026-07",
            Granularity.MONTH, null));
  }

  @Test
  void testAnnotatorProvidesTheTemporalLayer() {
    final Document document = DocumentAnalyzer.builder()
        .add(new TemporalAnnotator(extractor))
        .build()
        .analyze("earnings land on July 24, 2026 before Q3 2026 begins");
    final List<Annotation<TemporalExpression>> temporals =
        document.get(TemporalAnnotator.TEMPORALS);
    assertEquals(2, temporals.size());
    assertEquals("2026-07-24", temporals.get(0).value().value());
    assertEquals(Granularity.QUARTER, temporals.get(1).value().granularity());
  }

  @Test
  void testAnnotatorValidation() {
    assertThrows(IllegalArgumentException.class, () -> new TemporalAnnotator(null));
    assertThrows(IllegalArgumentException.class,
        () -> new TemporalAnnotator(extractor).annotate(null));
  }

  /**
   * Verifies the reference-resolved relative day words: today, yesterday, and
   * tomorrow each resolve to their calendar day against the reference, with exact
   * spans, and the whole family is case-insensitive.
   */
  @Test
  void testRelativeDayWordsResolveAgainstTheReference() {
    final List<TemporalExpression> mentions =
        extractor.extract("filed Yesterday, due tomorrow, signed today", REFERENCE);
    assertEquals(3, mentions.size());
    assertEquals("2026-07-16", mentions.get(0).value());
    assertEquals(Granularity.DAY, mentions.get(0).granularity());
    assertEquals(Origin.RELATIVE, mentions.get(0).origin());
    assertEquals(new Span(6, 15), mentions.get(0).span());
    assertEquals("2026-07-18", mentions.get(1).value());
    assertEquals("2026-07-17", mentions.get(2).value());
  }

  /**
   * Verifies the shifted-unit forms at each unit's own granularity: last week is an
   * ISO week, next month a month, last year a year, and next quarter a quarter, all
   * against {@link #REFERENCE}.
   */
  @Test
  void testShiftedUnitsResolveAtTheirOwnGranularity() {
    final List<TemporalExpression> mentions = extractor.extract(
        "last week we planned; next month it ships; last year it began; next quarter it scales",
        REFERENCE);
    assertEquals(4, mentions.size());
    assertEquals("2026-W28", mentions.get(0).value());
    assertEquals(Granularity.WEEK, mentions.get(0).granularity());
    assertEquals("2026-08", mentions.get(1).value());
    assertEquals(Granularity.MONTH, mentions.get(1).granularity());
    assertEquals("2025", mentions.get(2).value());
    assertEquals(Granularity.YEAR, mentions.get(2).granularity());
    assertEquals("2026-Q4", mentions.get(3).value());
    assertEquals(Granularity.QUARTER, mentions.get(3).granularity());
  }

  /**
   * Verifies the counted forms in both directions: a count with {@code ago} shifts
   * backward and {@code in} with a count shifts forward, each at the unit's own
   * granularity, crossing a month boundary where the arithmetic demands it.
   */
  @Test
  void testCountedShiftsResolveInBothDirections() {
    final List<TemporalExpression> mentions = extractor.extract(
        "3 days ago it rained; in 2 weeks it ships; 14 months ago it started",
        REFERENCE);
    assertEquals(3, mentions.size());
    assertEquals("2026-07-14", mentions.get(0).value());
    assertEquals("2026-W31", mentions.get(1).value());
    assertEquals(Granularity.WEEK, mentions.get(1).granularity());
    assertEquals("2025-05", mentions.get(2).value());
  }

  /**
   * Verifies the guard rails: without a reference date no relative expression is
   * reported at all, near-miss phrasings stay untouched, and absolute mentions keep
   * working with a reference present.
   */
  @Test
  void testRelativeGuardRails() {
    assertTrue(extractor.extract("due yesterday and next month").isEmpty());
    assertTrue(extractor
        .extract("in cold water; last stand; 3 days late", REFERENCE).isEmpty());
    final List<TemporalExpression> mixed =
        extractor.extract("signed 2026-07-14, due tomorrow", REFERENCE);
    assertEquals(2, mixed.size());
    assertEquals("2026-07-14", mixed.get(0).value());
    assertEquals("2026-07-18", mixed.get(1).value());
    assertThrows(IllegalArgumentException.class,
        () -> extractor.extract("text", null));
  }
}
