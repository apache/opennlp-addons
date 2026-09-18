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
import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.temporal.TemporalExpression.Granularity;
import opennlp.tools.util.Span;
import opennlp.tools.util.StringUtil;

/** Tests absolute calendar values, date spellings, and ordinal suffix boundaries. */
class CursorTemporalCalendarTest {

  private final CursorTemporalExtractor extractor = new CursorTemporalExtractor();

  /**
   * Supported written dates match JDK calendar validation, including century leap rules.
   *
   * @param year The year to exercise across all months and days.
   */
  @ParameterizedTest
  @ValueSource(ints = {1_000, 1_600, 1_700, 1_900, 2_000, 2_024, 2_100, 2_400, 2_999})
  void testGeneratedCalendarDates(int year) {
    for (int month = 1; month <= 12; month++) {
      final YearMonth calendar = YearMonth.of(year, month);
      final String name = Month.of(month).getDisplayName(TextStyle.FULL, Locale.ENGLISH);
      for (int day = 1; day <= 31; day++) {
        final String dayFirst = day + " " + name + " " + year;
        final String monthFirst = name + " " + day + ", " + year;
        if (day > calendar.lengthOfMonth()) {
          Assertions.assertEquals(List.of(), extractor.extract(monthFirst), monthFirst);
          final int monthStart = dayFirst.indexOf(name);
          Assertions.assertEquals(List.of(new TemporalExpression(
              new Span(monthStart, dayFirst.length()), calendar.toString(), Granularity.MONTH)),
              extractor.extract(dayFirst), dayFirst);
          continue;
        }
        final LocalDate date = calendar.atDay(day);
        assertDay(date.toString(), date);
        assertDay(dayFirst, date);
        assertDay(monthFirst, date);
        final String ordinal = ordinal(day);
        assertDay(day + ordinal + " " + name + " " + year, date);
        assertDay(name + " " + day + ordinal + ", " + year, date);
        assertDay(day + StringUtil.toUpperCase(ordinal) + " " + name + " " + year, date);
      }
    }
  }

  /**
   * Unsupported absolute years and invalid dates are not repaired.
   *
   * @param text The invalid date expression.
   */
  @ParameterizedTest
  @ValueSource(strings = {"0999-12-31", "3000-01-01", "January 0999", "January 3000",
      "Q1 0999", "Q4 3000", "2024-00-01", "2024-13-01", "2024-01-00", "2024-01-32",
      "February 0, 2024", "July 32, 2024"})
  void testInvalidAbsoluteDates(String text) {
    Assertions.assertEquals(List.of(), extractor.extract(text));
  }

  /**
   * Ordinal tokens cannot be extended with digits or combining marks.
   *
   * @param suffix The unsupported ordinal suffix.
   */
  @ParameterizedTest
  @ValueSource(strings = {"xx", "\u017Ft", "st2", "st\u0301", "st\uD801\uDC00", "st_item"})
  void testOrdinalContinuations(String suffix) {
    final String text = "1" + suffix + " July 2026";
    Assertions.assertEquals(List.of(new TemporalExpression(
        new Span(text.indexOf("July"), text.length()), "2026-07", Granularity.MONTH)),
        extractor.extract(text));
  }

  /**
   * Abbreviated and mixed-case month names retain complete spans.
   *
   * @param text The date expression.
   * @param expected The expected ISO date.
   */
  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {"29 FEB 2024|2024-02-29", "29th fEb 2024|2024-02-29",
      "FEB 29TH, 2024|2024-02-29", "1st Jan 1000|1000-01-01", "Dec 31st, 2999|2999-12-31"})
  void testMonthNames(String text, String expected) {
    assertDay(text, LocalDate.parse(expected));
  }

  /**
   * Builds the English ordinal suffix for a day of the month.
   *
   * @param day The day from 1 to 31.
   * @return The suffix without digits.
   */
  private String ordinal(int day) {
    if (day >= 11 && day <= 13) {
      return "th";
    }
    return switch (day % 10) {
      case 1 -> "st";
      case 2 -> "nd";
      case 3 -> "rd";
      default -> "th";
    };
  }

  /**
   * Checks a complete absolute day mention.
   *
   * @param text The expression being parsed.
   * @param date The expected calendar date.
   */
  private void assertDay(String text, LocalDate date) {
    Assertions.assertEquals(List.of(new TemporalExpression(new Span(0, text.length()),
        date.toString(), Granularity.DAY)), extractor.extract(text), text);
  }
}
