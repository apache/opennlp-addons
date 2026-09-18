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
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.document.Document;
import opennlp.tools.numeric.NumericPacks;
import opennlp.tools.temporal.TemporalExpression.Granularity;
import opennlp.tools.temporal.TemporalExpression.Origin;
import opennlp.tools.util.Span;

/** Tests relative-date arithmetic and output throughout the LocalDate range. */
class CursorTemporalRangeTest {

  private final CursorTemporalExtractor extractor = new CursorTemporalExtractor();

  /**
   * Normalized years use at least 4 digits and a sign for negative or expanded years.
   *
   * @param year The reference year.
   * @param expected The normalized year component.
   */
  @ParameterizedTest
  @CsvSource({"-999999999,-999999999", "-10000,-10000", "-9999,-9999", "-1000,-1000",
      "-999,-0999", "-10,-0010", "-1,-0001", "0,0000", "1,0001", "9,0009",
      "99,0099", "999,0999", "1000,1000", "2026,2026", "9999,9999",
      "10000,+10000", "999999999,+999999999"})
  void testNormalizedYear(int year, String expected) {
    final LocalDate reference = LocalDate.of(year, 1, 4);
    Assertions.assertAll(
        () -> assertRelative("today", reference, expected + "-01-04", Granularity.DAY),
        () -> assertRelative("this week", reference, expected + "-W01", Granularity.WEEK),
        () -> assertRelative("this month", reference, expected + "-01", Granularity.MONTH),
        () -> assertRelative("this quarter", reference, expected + "-Q1", Granularity.QUARTER),
        () -> assertRelative("this year", reference, expected, Granularity.YEAR));
  }

  /**
   * An expression outside the date range has no result and does not throw.
   *
   * @param maximum Whether to use the latest date, or the earliest date otherwise.
   * @param text The expression that exceeds the supported date range.
   */
  @ParameterizedTest
  @CsvSource({"false,yesterday", "false,last day", "false,last week", "false,last month",
      "false,last quarter", "false,last year", "false,1 day ago", "false,99 days ago",
      "false,99 weeks ago", "false,99 months ago", "false,99 quarters ago", "false,99 years ago",
      "true,tomorrow", "true,next day", "true,next week", "true,next month",
      "true,next quarter", "true,next year", "true,in 1 day", "true,in 99 days",
      "true,in 99 weeks", "true,in 99 months", "true,in 99 quarters", "true,in 99 years"})
  void testOutOfRangeExpression(boolean maximum, String text) {
    final LocalDate reference = maximum ? LocalDate.MAX : LocalDate.MIN;
    Assertions.assertEquals(List.of(),
        Assertions.assertDoesNotThrow(() -> extractor.extract(text, reference)));
  }

  /**
   * Zero shifts and unshifted units remain valid at both date limits.
   *
   * @param maximum Whether to use the latest date, or the earliest date otherwise.
   */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testReferenceDateLimits(boolean maximum) {
    final LocalDate reference = maximum ? LocalDate.MAX : LocalDate.MIN;
    final String isoDate = reference.toString();
    final String year = isoDate.substring(0, isoDate.length() - 6);
    final String weekDate = DateTimeFormatter.ISO_WEEK_DATE.format(reference);
    Assertions.assertAll(
        () -> assertRelative("today", reference, isoDate, Granularity.DAY),
        () -> assertRelative("this day", reference, isoDate, Granularity.DAY),
        () -> assertRelative("in 0 days", reference, isoDate, Granularity.DAY),
        () -> assertRelative("0 days ago", reference, isoDate, Granularity.DAY),
        () -> assertRelative("this week", reference,
            weekDate.substring(0, weekDate.length() - 2), Granularity.WEEK),
        () -> assertRelative("this month", reference,
            isoDate.substring(0, isoDate.length() - 3), Granularity.MONTH),
        () -> assertRelative("this quarter", reference,
            year + (maximum ? "-Q4" : "-Q1"), Granularity.QUARTER),
        () -> assertRelative("this year", reference, year, Granularity.YEAR));
  }

  /**
   * An invalid relative date does not prevent other mentions or document-date selection.
   *
   * @param maximum Whether to use the latest date, or the earliest date otherwise.
   */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testPipelineContinuesAfterOutOfRangeDate(boolean maximum) {
    final LocalDate reference = maximum ? LocalDate.MAX : LocalDate.MIN;
    final String text = "today; " + (maximum ? "tomorrow" : "yesterday") + "; 14 July 2026";
    final Document document = Assertions.assertDoesNotThrow(
        () -> NumericPacks.temporal(reference).analyze(text));
    final List<TemporalExpression> expressions = document.get(TemporalAnnotator.TEMPORALS)
        .stream().map(annotation -> annotation.value()).toList();
    Assertions.assertEquals(List.of(
        new TemporalExpression(new Span(0, 5), reference.toString(), Granularity.DAY, Origin.RELATIVE),
        new TemporalExpression(new Span(text.length() - 12, text.length()),
            "2026-07-14", Granularity.DAY)), expressions);
    Assertions.assertEquals(LocalDate.of(2026, 7, 14),
        document.get(DocumentDateAnnotator.DOCUMENT_DATE).getFirst().value());
  }

  /**
   * Calendar shifts preserve leap days, week-based years, and transitions around year zero.
   *
   * @param date The ISO reference date.
   * @param text The expression to resolve.
   * @param expected The expected normalized value.
   * @param granularity The expected resolution.
   */
  @ParameterizedTest
  @CsvSource({"2024-03-01,1 day ago,2024-02-29,DAY",
      "2024-02-28,in 1 day,2024-02-29,DAY",
      "2024-02-29,1 year ago,2023,YEAR",
      "2024-01-31,in 1 month,2024-02,MONTH",
      "2021-01-01,this week,2020-W53,WEEK",
      "2021-01-04,last week,2020-W53,WEEK",
      "2020-12-31,next week,2021-W01,WEEK",
      "2026-12-31,next quarter,2027-Q1,QUARTER",
      "0000-01-01,yesterday,-0001-12-31,DAY",
      "-0001-12-31,tomorrow,0000-01-01,DAY",
      "9999-12-31,tomorrow,+10000-01-01,DAY",
      "1000-01-01,last year,0999,YEAR"})
  void testCalendarTransitions(String date, String text, String expected, Granularity granularity) {
    assertRelative(text, LocalDate.parse(date), expected, granularity);
  }

  /**
   * Generated dates compare counted shifts with JDK calendar arithmetic and ISO output.
   *
   * @param granularity The unit to exercise.
   */
  @ParameterizedTest
  @EnumSource(Granularity.class)
  void testGeneratedCalendarShifts(Granularity granularity) {
    final Random random = new Random(20260905L);
    final ChronoUnit unit = switch (granularity) {
      case DAY -> ChronoUnit.DAYS;
      case WEEK -> ChronoUnit.WEEKS;
      case MONTH, QUARTER -> ChronoUnit.MONTHS;
      case YEAR -> ChronoUnit.YEARS;
    };
    final String unitName = switch (granularity) {
      case DAY -> "days";
      case WEEK -> "weeks";
      case MONTH -> "months";
      case QUARTER -> "quarters";
      case YEAR -> "years";
    };
    for (int i = 0; i < 200; i++) {
      final LocalDate reference = LocalDate.ofEpochDay(random.nextLong(
          LocalDate.MIN.toEpochDay() + 36_600, LocalDate.MAX.toEpochDay() - 36_600));
      final int count = random.nextInt(-99, 100);
      final LocalDate expectedDate = reference.plus(granularity == Granularity.QUARTER
          ? count * 3L : count, unit);
      final String date = expectedDate.toString();
      final String year = date.substring(0, date.length() - 6);
      final String weekDate = DateTimeFormatter.ISO_WEEK_DATE.format(expectedDate);
      final String expected = switch (granularity) {
        case DAY -> date;
        case WEEK -> weekDate.substring(0, weekDate.length() - 2);
        case MONTH -> date.substring(0, date.length() - 3);
        case QUARTER -> year + "-Q" + ((expectedDate.getMonthValue() + 2) / 3);
        case YEAR -> year;
      };
      final String text = count < 0 ? -count + " " + unitName + " ago"
          : "in " + count + " " + unitName;
      assertRelative(text, reference, expected, granularity);
    }
  }

  /**
   * Locale settings do not change ISO week fields, signs, or digits.
   *
   * @param tag The locale language tag to set temporarily.
   */
  @ParameterizedTest
  @ValueSource(strings = {"ar-u-nu-arab", "th-TH-u-nu-thai", "en-US"})
  void testLocaleIndependentFormatting(String tag) {
    final Locale previous = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag(tag));
      assertRelative("today", LocalDate.of(-1, 1, 4), "-0001-01-04", Granularity.DAY);
      assertRelative("this week", LocalDate.of(2021, 1, 1), "2020-W53", Granularity.WEEK);
      assertRelative("this quarter", LocalDate.of(10000, 1, 4), "+10000-Q1", Granularity.QUARTER);
      Assertions.assertEquals("2026-01", extractor.extract("January 2026").getFirst().value());
    } finally {
      Locale.setDefault(previous);
    }
  }

  /**
   * A shared extractor formats concurrent documents using their separate reference dates.
   *
   * @throws Exception If a worker fails or is interrupted.
   */
  @Test
  void testConcurrentReferences() throws Exception {
    final List<Callable<Void>> tasks = new ArrayList<>();
    for (int year = -100; year <= 100; year++) {
      final LocalDate reference = LocalDate.of(year, 1, 4);
      tasks.add(() -> {
        final String previousYear = reference.minusYears(1).toString();
        assertRelative("today", reference, reference.toString(), Granularity.DAY);
        assertRelative("last year", reference,
            previousYear.substring(0, previousYear.length() - 6), Granularity.YEAR);
        return null;
      });
    }
    try (var executor = Executors.newFixedThreadPool(4)) {
      for (var result : executor.invokeAll(tasks)) {
        result.get();
      }
    }
  }

  /**
   * Checks the value, origin, granularity, and complete original-text span of a result.
   *
   * @param text The relative expression.
   * @param reference The reference date.
   * @param expected The expected normalized value.
   * @param granularity The expected resolution.
   */
  private void assertRelative(String text, LocalDate reference, String expected,
      Granularity granularity) {
    Assertions.assertEquals(List.of(new TemporalExpression(new Span(0, text.length()),
        expected, granularity, Origin.RELATIVE)), extractor.extract(text, reference),
        () -> text + " at " + reference);
  }
}
