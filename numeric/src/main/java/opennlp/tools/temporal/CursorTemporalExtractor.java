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

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.SignStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import opennlp.tools.extraction.NumberNotation;
import opennlp.tools.extraction.NumberScan;
import opennlp.tools.temporal.TemporalExpression.Granularity;
import opennlp.tools.temporal.TemporalExpression.Origin;
import opennlp.tools.util.Span;
import opennlp.tools.util.StringUtil;

/**
 * A model-free {@link TemporalExtractor} for calendar dates and relative expressions.
 *
 * <p>Recognized forms: ISO dates ({@code 2026-07-14}), also as the date part of an
 * timestamp ({@code 2026-07-14T09:30:00Z}), reported at day granularity over
 * the date part only; written dates in both orders,
 * with optional comma and ordinal suffix ({@code July 14, 2026}, {@code 14th July 2026},
 * {@code Jul 14 2026}); month and year ({@code July 2026}); and quarters
 * ({@code Q3 2024}). Month names are matched case-insensitively as full names or
 * three-letter abbreviations. Absolute mentions require years from 1000 through 2999
 * and valid calendar days.</p>
 *
 * <p>Clock suffixes require {@code T} and hours/minutes, with optional seconds,
 * a dot or comma fraction of the last component, and {@code Z} or a
 * {@code +05:30} style offset. Lowercase {@code t} and {@code z} are accepted.
 * Hour 24 requires zero minutes, seconds, and fraction. Second 60 is accepted
 * without checking a leap-second calendar. Offset hours are 00 through 23 and
 * minutes are 00 through 59. The reported date is not converted to UTC.</p>
 *
 * <p>With a reference date, {@link #extract(CharSequence, LocalDate)} additionally
 * resolves relative expressions: {@code today}, {@code yesterday}, {@code tomorrow};
 * {@code last}, {@code this}, or {@code next} day, week, month, quarter, or year; and
 * counts from 0 to 99 of those units with {@code ago} or a leading {@code in}.
 * Results have {@link TemporalExpression.Origin#RELATIVE} origin and the requested
 * granularity. Without a reference date, relative expressions are omitted.</p>
 *
 * <p>Reference and resolved dates support the full {@link LocalDate} range. An expression
 * that exceeds this range is omitted without preventing subsequent matches. Normalized
 * years have at least 4 digits, a negative sign before year zero, and a positive sign
 * after year 9999. Week values use the ISO week-based year.</p>
 *
 * <p>Not recognized: named weekdays such as {@code next Tuesday}, times of day (the
 * time part of a timestamp is not reported), years without a month, day-and-month
 * without a year, and numeric dates with slashes. Phrases use one ASCII space between
 * components. The extractor uses no regular expressions or mutable per-call fields
 * and can be shared between threads.</p>
 *
 * @see DateTimeFormatter#ISO_LOCAL_DATE
 * @see DateTimeFormatter#ISO_WEEK_DATE
 * @see <a href="https://www.rfc-editor.org/rfc/rfc3339.html#section-5.6">RFC 3339 clock fields and offsets</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc3339.html#appendix-A">ISO 8601 time syntax</a>
 * @since 3.0.0
 */
public class CursorTemporalExtractor implements TemporalExtractor {

  private static final Map<String, Integer> MONTHS = months();

  private static final int MIN_YEAR = 1000;
  private static final int MAX_YEAR = 2999;

  private static final String DAY_UNIT = "day";

  private static final DateTimeFormatter YEAR_FORMAT = new DateTimeFormatterBuilder()
      .appendValue(ChronoField.YEAR, 4, 10, SignStyle.EXCEEDS_PAD)
      .toFormatter(Locale.ROOT);

  private static final DateTimeFormatter MONTH_FORMAT = new DateTimeFormatterBuilder()
      .append(YEAR_FORMAT).appendLiteral('-').appendValue(ChronoField.MONTH_OF_YEAR, 2)
      .toFormatter(Locale.ROOT);

  private static final DateTimeFormatter QUARTER_FORMAT = new DateTimeFormatterBuilder()
      .append(YEAR_FORMAT).appendLiteral("-Q").appendValue(IsoFields.QUARTER_OF_YEAR)
      .toFormatter(Locale.ROOT);

  private static final DateTimeFormatter WEEK_FORMAT = new DateTimeFormatterBuilder()
      .appendValue(IsoFields.WEEK_BASED_YEAR, 4, 10, SignStyle.EXCEEDS_PAD)
      .appendLiteral("-W").appendValue(IsoFields.WEEK_OF_WEEK_BASED_YEAR, 2)
      .toFormatter(Locale.ROOT);

  /** The length of an extended-format ISO date, {@code 2026-07-14}. */
  private static final int ISO_DATE_LENGTH = 10;

  private static final int HOURS_PER_DAY = 24;
  private static final int MINUTES_PER_HOUR = 60;
  private static final int SECONDS_PER_MINUTE = 60;
  private static final char TIME_SEPARATOR = ':';
  private static final char FRACTION_DOT = '.';
  private static final char FRACTION_COMMA = ',';

  /**
   * How many letters a candidate keyword or month name may have before the scan gives
   * up: one more than {@code september}, the longest word this extractor recognizes.
   */
  private static final int MAX_WORD_LENGTH = 10;

  /**
   * {@inheritDoc}
   *
   * <p>Only absolute mentions are reported; relative expressions need the reference
   * date of {@link #extract(CharSequence, LocalDate)}.</p>
   */
  @Override
  public List<TemporalExpression> extract(CharSequence text) {
    if (text == null) {
      throw new IllegalArgumentException("text must not be null");
    }
    return scan(text, null);
  }

  /**
   * {@inheritDoc}
   *
   * <p>Expressions outside the {@link LocalDate} range are omitted.</p>
   */
  @Override
  public List<TemporalExpression> extract(CharSequence text, LocalDate reference) {
    if (reference == null) {
      throw new IllegalArgumentException("reference must not be null");
    }
    if (text == null) {
      throw new IllegalArgumentException("text must not be null");
    }
    return scan(text, reference);
  }

  /**
   * The shared forward scan.
   *
   * @param text The text to scan. Must not be {@code null}.
   * @param reference The date relative expressions resolve against, or {@code null} to
   *                  not recognize relative expressions at all.
   * @return The mentions in text order, non-overlapping. Never {@code null}.
   */
  private List<TemporalExpression> scan(CharSequence text, LocalDate reference) {
    final List<TemporalExpression> mentions = new ArrayList<>();
    int i = 0;
    while (i < text.length()) {
      final TemporalExpression mention = matchAt(text, i, reference);
      if (mention != null) {
        mentions.add(mention);
        i = mention.span().getEnd();
      } else {
        i += Character.charCount(Character.codePointAt(text, i));
      }
    }
    return Collections.unmodifiableList(mentions);
  }

  /**
   * Tries the mention shapes at one position: ISO date, day-first, or a relative
   * count at a digit; quarter, month-first, or a relative keyword at a letter.
   *
   * @param text The text being scanned.
   * @param start The offset the candidate mention would start at.
   * @param reference The reference date, or {@code null} to skip the relative shapes.
   * @return The mention starting at {@code start}, or {@code null} when none matches.
   */
  private TemporalExpression matchAt(CharSequence text, int start, LocalDate reference) {
    final char c = NumberScan.charAt(text, start);
    if ((!NumberScan.isAsciiDigit(c) && !Character.isLetter(c))
        || !NumberScan.boundaryBefore(text, start)) {
      return null;
    }
    if (NumberScan.isAsciiDigit(c)) {
      if (NumberScan.continuesNumber(text, start, NumberNotation.LATIN_US)) {
        return null;
      }
      final TemporalExpression iso = isoDate(text, start);
      if (iso != null) {
        return iso;
      }
      final TemporalExpression dayFirst = dayFirst(text, start);
      if (dayFirst != null) {
        return dayFirst;
      }
      return reference == null ? null : countAgo(text, start, reference);
    }
    if ((c == 'Q' || c == 'q') && NumberScan.isAsciiDigit(NumberScan.charAt(text, start + 1))) {
      return quarter(text, start);
    }
    if (Character.isLetter(c)) {
      final TemporalExpression monthFirst = monthFirst(text, start);
      if (monthFirst != null) {
        return monthFirst;
      }
      return reference == null ? null : relativeKeyword(text, start, reference);
    }
    return null;
  }

  /**
   * Matches the keyword-led relative forms: the day words {@code today},
   * {@code yesterday}, and {@code tomorrow}; {@code last}, {@code this}, or
   * {@code next} followed by a unit; and {@code in} followed by a count and a unit.
   *
   * @param text The text being scanned.
   * @param start The offset of the first letter of the candidate keyword.
   * @param reference The date the expression resolves against. Must not be {@code null}.
   * @return The resolved mention, or {@code null} when no relative form starts here.
   */
  private TemporalExpression relativeKeyword(CharSequence text, int start,
      LocalDate reference) {
    final Word keyword = word(text, start);
    if (keyword == null) {
      return null;
    }
    switch (keyword.lower()) {
      case "today":
        return shifted(start, keyword.end(), DAY_UNIT, 0, reference);
      case "yesterday":
        return shifted(start, keyword.end(), DAY_UNIT, -1, reference);
      case "tomorrow":
        return shifted(start, keyword.end(), DAY_UNIT, 1, reference);
      case "last":
      case "this":
      case "next": {
        if (NumberScan.charAt(text, keyword.end()) != ' ') {
          return null;
        }
        final Word unit = word(text, keyword.end() + 1);
        if (unit == null) {
          return null;
        }
        final int steps = switch (keyword.lower()) {
          case "last" -> -1;
          case "next" -> 1;
          default -> 0;
        };
        return shifted(start, unit.end(), unit.lower(), steps, reference);
      }
      case "in": {
        if (NumberScan.charAt(text, keyword.end()) != ' ') {
          return null;
        }
        final NumberInText count = shortNumber(text, keyword.end() + 1);
        if (count == null || NumberScan.charAt(text, count.end()) != ' ') {
          return null;
        }
        final Word unit = word(text, count.end() + 1);
        if (unit == null) {
          return null;
        }
        return shifted(start, unit.end(), singular(unit.lower()), count.value(),
            reference);
      }
      default:
        return null;
    }
  }

  /**
   * Matches {@code 3 days ago} and its week, month, and year siblings.
   *
   * @param text The text being scanned.
   * @param start The offset of the first digit of the count.
   * @param reference The date the expression resolves against. Must not be {@code null}.
   * @return The resolved mention, or {@code null} when no counted form starts here.
   */
  private TemporalExpression countAgo(CharSequence text, int start, LocalDate reference) {
    final NumberInText count = shortNumber(text, start);
    if (count == null || NumberScan.charAt(text, count.end()) != ' ') {
      return null;
    }
    final Word unit = word(text, count.end() + 1);
    if (unit == null || NumberScan.charAt(text, unit.end()) != ' ') {
      return null;
    }
    final Word ago = word(text, unit.end() + 1);
    if (ago == null || !"ago".equals(ago.lower())) {
      return null;
    }
    return shifted(start, ago.end(), singular(unit.lower()), -count.value(),
        reference);
  }

  /**
   * Resolves a unit word shifted by a number of steps against the reference, at the
   * granularity the unit names.
   *
   * @param start The offset the mention starts at.
   * @param end The exclusive offset the mention ends at.
   * @param unit The singular unit word, for example {@code week}.
   * @param steps How many units to shift, negative into the past.
   * @param reference The date the expression resolves against. Must not be {@code null}.
   * @return The resolved mention, or {@code null} for an unknown unit or a date outside
   *         the {@link LocalDate} range.
   */
  private TemporalExpression shifted(int start, int end, String unit,
      int steps, LocalDate reference) {
    final Granularity granularity = switch (unit) {
      case DAY_UNIT -> Granularity.DAY;
      case "week" -> Granularity.WEEK;
      case "month" -> Granularity.MONTH;
      case "quarter" -> Granularity.QUARTER;
      case "year" -> Granularity.YEAR;
      default -> null;
    };
    if (granularity == null) {
      return null;
    }
    final LocalDate date;
    try {
      date = switch (granularity) {
        case DAY -> reference.plusDays(steps);
        case WEEK -> reference.plusWeeks(steps);
        case MONTH -> reference.plusMonths(steps);
        case QUARTER -> reference.plusMonths(3L * steps);
        case YEAR -> reference.plusYears(steps);
      };
    } catch (DateTimeException e) {
      return null;
    }
    return resolved(start, end, date, granularity);
  }

  /**
   * Reduces a plural unit word to its singular; other words pass through.
   *
   * @param unit The unit word, for example {@code weeks}.
   * @return The singular form, for example {@code week}. Never {@code null}.
   */
  private String singular(String unit) {
    return unit.endsWith("s") && unit.length() > 1
        ? unit.substring(0, unit.length() - 1) : unit;
  }

  /**
   * Builds a resolved relative mention at a granularity's ISO value.
   *
   * @param start The offset the mention starts at.
   * @param end The exclusive offset the mention ends at.
   * @param date The resolved date. Must not be {@code null}.
   * @param granularity The granularity to report the date at. Must not be {@code null}.
   * @return The mention. Never {@code null}.
   */
  private TemporalExpression resolved(int start, int end, LocalDate date,
      Granularity granularity) {
    final String value = switch (granularity) {
      case DAY -> date.toString();
      case WEEK -> WEEK_FORMAT.format(date);
      case MONTH -> MONTH_FORMAT.format(date);
      case QUARTER -> QUARTER_FORMAT.format(date);
      case YEAR -> YEAR_FORMAT.format(date);
    };
    return new TemporalExpression(new Span(start, end), value, granularity, Origin.RELATIVE);
  }

  /**
   * Matches an ISO date, including the date part of a supported timestamp.
   *
   * @param text The text being scanned.
   * @param start The offset of the first digit of the year.
   * @return The mention, or {@code null} when no valid ISO date starts here.
   */
  private TemporalExpression isoDate(CharSequence text, int start) {
    final int year = digits(text, start, 4);
    if (year < 0 || NumberScan.charAt(text, start + 4) != '-') {
      return null;
    }
    final int month = digits(text, start + 5, 2);
    if (month < 0 || NumberScan.charAt(text, start + 7) != '-') {
      return null;
    }
    final int day = digits(text, start + 8, 2);
    if (day < 0) {
      return null;
    }
    final int end = start + ISO_DATE_LENGTH;
    if (!NumberScan.boundaryAfter(text, end) && !timeOfDayAt(text, end)) {
      return null;
    }
    return day(start, end, year, month, day);
  }

  /**
   * Checks the clock fields, fraction, offset, and final boundary of a timestamp suffix.
   *
   * @param text The text being scanned.
   * @param start The offset of the candidate {@code T}.
   * @return {@code true} if a time of day starts at {@code start}.
   */
  private boolean timeOfDayAt(CharSequence text, int start) {
    final char separator = NumberScan.charAt(text, start);
    if ((separator != 'T' && separator != 't')
        || NumberScan.charAt(text, start + 3) != TIME_SEPARATOR) {
      return false;
    }
    final int hour = digits(text, start + 1, 2);
    final int minute = digits(text, start + 4, 2);
    if (hour < 0 || hour > HOURS_PER_DAY || minute < 0 || minute >= MINUTES_PER_HOUR) {
      return false;
    }
    int i = start + 6;
    int second = 0;
    if (NumberScan.charAt(text, i) == TIME_SEPARATOR
        && NumberScan.isAsciiDigit(NumberScan.charAt(text, i + 1))) {
      second = digits(text, i + 1, 2);
      if (second < 0 || second > SECONDS_PER_MINUTE) {
        return false;
      }
      i += 3;
    }
    final char decimal = NumberScan.charAt(text, i);
    boolean nonzeroFraction = false;
    if ((decimal == FRACTION_DOT || decimal == FRACTION_COMMA)
        && NumberScan.isAsciiDigit(NumberScan.charAt(text, i + 1))) {
      i++;
      while (NumberScan.isAsciiDigit(NumberScan.charAt(text, i))) {
        nonzeroFraction |= NumberScan.charAt(text, i) != '0';
        i++;
      }
    }
    if (hour == HOURS_PER_DAY && (minute != 0 || second != 0 || nonzeroFraction)) {
      return false;
    }
    final char zone = NumberScan.charAt(text, i);
    if (zone == 'Z' || zone == 'z') {
      i++;
    } else if (zone == '+' || zone == '-') {
      final int offsetHour = digits(text, i + 1, 2);
      final int offsetMinute = digits(text, i + 4, 2);
      if (offsetHour < 0 || offsetHour >= HOURS_PER_DAY
          || NumberScan.charAt(text, i + 3) != TIME_SEPARATOR
          || offsetMinute < 0 || offsetMinute >= MINUTES_PER_HOUR) {
        return false;
      }
      i += 6;
    }
    return timeBoundaryAfter(text, i);
  }

  /**
   * Distinguishes trailing punctuation from additional clock fields or fractions.
   *
   * @param text The text being scanned.
   * @param start The offset after a complete time suffix.
   * @return Whether the time ends here without a numeric or word continuation.
   */
  private boolean timeBoundaryAfter(CharSequence text, int start) {
    int i = start;
    char next = NumberScan.charAt(text, i);
    while (next == TIME_SEPARATOR || next == FRACTION_DOT || next == FRACTION_COMMA) {
      next = NumberScan.charAt(text, ++i);
    }
    return next != '+' && next != '-' && NumberScan.boundaryAfter(text, i);
  }

  /**
   * Matches {@code 14 July 2026} and {@code 14th July 2026}.
   *
   * @param text The text being scanned.
   * @param start The offset of the first digit of the day.
   * @return The mention, or {@code null} when no valid day-first date starts here.
   */
  private TemporalExpression dayFirst(CharSequence text, int start) {
    final NumberInText day = shortNumber(text, start);
    if (day == null) {
      return null;
    }
    int i = skipOrdinal(text, day.end());
    if (NumberScan.charAt(text, i) != ' ') {
      return null;
    }
    final Word month = word(text, i + 1);
    if (month == null || !MONTHS.containsKey(month.lower())
        || NumberScan.charAt(text, month.end()) != ' ') {
      return null;
    }
    final NumberInText year = yearAt(text, month.end() + 1);
    if (year == null) {
      return null;
    }
    return day(start, year.end(), year.value(), MONTHS.get(month.lower()), day.value());
  }

  /**
   * Matches {@code July 14, 2026}, {@code Jul 14 2026}, and {@code July 2026}.
   *
   * @param text The text being scanned.
   * @param start The offset of the first letter of the month name.
   * @return The mention, or {@code null} when no valid month-first date starts here.
   */
  private TemporalExpression monthFirst(CharSequence text, int start) {
    final Word month = word(text, start);
    if (month == null || !MONTHS.containsKey(month.lower())
        || NumberScan.charAt(text, month.end()) != ' ') {
      return null;
    }
    final int monthOfYear = MONTHS.get(month.lower());
    final NumberInText year = yearAt(text, month.end() + 1);
    if (year != null) {
      return new TemporalExpression(new Span(start, year.end()),
          MONTH_FORMAT.format(YearMonth.of(year.value(), monthOfYear)),
          Granularity.MONTH);
    }
    final NumberInText day = shortNumber(text, month.end() + 1);
    if (day == null) {
      return null;
    }
    int i = skipOrdinal(text, day.end());
    if (NumberScan.charAt(text, i) == ',') {
      i++;
    }
    if (NumberScan.charAt(text, i) != ' ') {
      return null;
    }
    final NumberInText dayYear = yearAt(text, i + 1);
    if (dayYear == null) {
      return null;
    }
    return day(start, dayYear.end(), dayYear.value(), monthOfYear, day.value());
  }

  /**
   * Matches {@code Q3 2024}.
   *
   * @param text The text being scanned.
   * @param start The offset of the {@code Q}.
   * @return The mention, or {@code null} when no valid quarter starts here.
   */
  private TemporalExpression quarter(CharSequence text, int start) {
    final int number = NumberScan.charAt(text, start + 1) - '0';
    if (number < 1 || number > 4 || NumberScan.charAt(text, start + 2) != ' ') {
      return null;
    }
    final NumberInText year = yearAt(text, start + 3);
    if (year == null) {
      return null;
    }
    return new TemporalExpression(new Span(start, year.end()),
        QUARTER_FORMAT.format(YearMonth.of(year.value(), (number - 1) * 3 + 1)),
        Granularity.QUARTER);
  }

  /**
   * Builds a calendar-validated day mention.
   *
   * @param start The offset the mention starts at.
   * @param end The exclusive offset the mention ends at.
   * @param year The year of the mention.
   * @param month The month of the year, one based.
   * @param dayOfMonth The day of the month, one based.
   * @return The mention, or {@code null} when the year is out of range or the date does
   *         not exist in the calendar.
   */
  private TemporalExpression day(int start, int end,
      int year, int month, int dayOfMonth) {
    if (year < MIN_YEAR || year > MAX_YEAR) {
      return null;
    }
    final LocalDate date;
    try {
      date = LocalDate.of(year, month, dayOfMonth);
    } catch (DateTimeException e) {
      return null;
    }
    return new TemporalExpression(new Span(start, end),
        date.toString(),
        Granularity.DAY);
  }

  /**
   * Reads a four-digit year that is in range and not part of a longer digit run.
   *
   * @param text The text being scanned.
   * @param start The offset of the first digit of the candidate year.
   * @return The year, or {@code null} when no such year starts at {@code start}.
   */
  private NumberInText yearAt(CharSequence text, int start) {
    final int year = digits(text, start, 4);
    if (year < MIN_YEAR || year > MAX_YEAR || !NumberScan.boundaryAfter(text, start + 4)) {
      return null;
    }
    return new NumberInText(year, start + 4);
  }

  /**
   * Reads a one or two digit number that is not part of a longer digit run.
   *
   * @param text The text being scanned.
   * @param start The offset of the first digit of the candidate number.
   * @return The number, or {@code null} when no such number starts at {@code start}.
   */
  private NumberInText shortNumber(CharSequence text, int start) {
    int i = start;
    int value = 0;
    while (NumberScan.isAsciiDigit(NumberScan.charAt(text, i)) && i - start < 3) {
      value = value * 10 + (NumberScan.charAt(text, i) - '0');
      i++;
    }
    final int length = i - start;
    if (length < 1 || length > 2 || NumberScan.isAsciiDigit(NumberScan.charAt(text, i))) {
      return null;
    }
    return new NumberInText(value, i);
  }

  /**
   * Skips an ordinal suffix ({@code st}, {@code nd}, {@code rd}, {@code th}).
   *
   * @param text The text being scanned.
   * @param index The offset just behind the digits of the day.
   * @return The offset behind the suffix, or {@code index} when none follows.
   */
  private int skipOrdinal(CharSequence text, int index) {
    final Word suffix = word(text, index);
    if (suffix == null) {
      return index;
    }
    return switch (suffix.lower()) {
      case "st", "nd", "rd", "th" -> suffix.end();
      default -> index;
    };
  }

  /**
   * Reads exactly {@code width} digits as an int.
   *
   * @param text The text being scanned.
   * @param start The offset of the first digit.
   * @param width How many digits to read.
   * @return The value, or a negative number when the digit run is shorter than
   *         {@code width} or continues past it.
   */
  private int digits(CharSequence text, int start, int width) {
    int value = 0;
    for (int i = start; i < start + width; i++) {
      final char c = NumberScan.charAt(text, i);
      if (!NumberScan.isAsciiDigit(c)) {
        return -1;
      }
      value = value * 10 + (c - '0');
    }
    return NumberScan.isAsciiDigit(NumberScan.charAt(text, start + width)) ? -1 : value;
  }

  /**
   * Reads a lowercased letter run of at most {@link #MAX_WORD_LENGTH} characters.
   *
   * @param text The text being scanned.
   * @param start The offset of the first letter.
   * @return The word, or {@code null} when no letter starts at {@code start} or the run
   *         continues past {@link #MAX_WORD_LENGTH}.
   */
  private Word word(CharSequence text, int start) {
    final int end = NumberScan.wordEnd(text, start, MAX_WORD_LENGTH);
    if (end < 0) {
      return null;
    }
    return new Word(StringUtil.toLowerCase(text.subSequence(start, end)), end);
  }

  /**
   * @return The month numbers by lowercased full name and three-letter abbreviation.
   *         Never {@code null}.
   */
  private static Map<String, Integer> months() {
    final Map<String, Integer> months = new HashMap<>();
    final String[] names = {"january", "february", "march", "april", "may", "june",
        "july", "august", "september", "october", "november", "december"};
    for (int i = 0; i < names.length; i++) {
      months.put(names[i], i + 1);
      months.put(names[i].substring(0, 3), i + 1);
    }
    return Collections.unmodifiableMap(months);
  }

  /** An intermediate parse result: a numeric value and the exclusive end offset. */
  private record NumberInText(int value, int end) {
  }

  /** An intermediate parse result: a lowercased word and the exclusive end offset. */
  private record Word(String lower, int end) {
  }
}
