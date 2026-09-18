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

package opennlp.tools.quantity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import opennlp.tools.extraction.NumberNotation;
import opennlp.tools.extraction.NumberScan;
import opennlp.tools.money.CursorMoneyExtractor;
import opennlp.tools.money.MoneyAmount;
import opennlp.tools.money.MoneyExtractor;
import opennlp.tools.util.Span;

/**
 * A model-free {@link QuantityExtractor} recognizing numbers with a percent marker or
 * a unit token, without regular expressions.
 *
 * <p>Recognized forms: percentages as {@code 50%}, {@code 3.5 %}, or {@code 50 percent}
 * (all reported with the unit {@code %}); unit quantities with the unit immediately
 * attached ({@code 2.5km}) or separated by one space ({@code 80 kg}); and an optional
 * leading minus. Digit grouping follows the shared strict rule. A number grouped in a
 * convention the scanner cannot parse, for example the Indian-grouped {@code 1,00,000},
 * is rejected entirely rather than truncated to a wrong value, and the
 * separator-adjoined tail of such a number never seeds a mention of its own. A bare
 * number without a percent marker or unit is never a quantity.</p>
 *
 * <p>Numbers are read in one {@link NumberNotation}, {@link NumberNotation#LATIN_US} by
 * default, so {@code 1,250 GB} is a little over a thousand gigabytes. A document written
 * in the European convention is read by an extractor built for it, in which
 * {@code 1.250 GB} means the same. Inputs valid in both notations are interpreted using
 * the selected one; invalid grouping is rejected.</p>
 *
 * <p>Units are matched exactly, case-sensitively, against a curated default set of
 * common measurement tokens; ambiguous English words such as {@code in} are deliberately
 * excluded. Callers extend or replace the set through
 * {@link #CursorQuantityExtractor(Set)}. No unit conversion is performed.</p>
 *
 * <p>Currency amounts recognized by {@link CursorMoneyExtractor} with the default symbol
 * table and the selected number notation take precedence over quantities, including
 * custom units. Thus {@code $3m} is not reported as 3 meters. Currency amounts are scanned
 * first; a forward quantity scan then excludes overlapping spans.</p>
 *
 * <p>The extractor holds no per-call state and is safe to share between threads.</p>
 *
 * @since 3.0.0
 */
public class CursorQuantityExtractor implements QuantityExtractor {

  private static final Set<String> DEFAULT_UNITS = Set.of(
      "km", "m", "cm", "mm", "nm", "mi", "ft", "yd",
      "kg", "g", "mg", "t", "lb", "lbs", "oz",
      "L", "mL", "ml",
      "ms", "ns", "min", "hr",
      "KB", "MB", "GB", "TB", "PB",
      "Hz", "kHz", "MHz", "GHz",
      "kW", "MW", "GW", "kWh",
      "mph", "kph");

  private static final String PERCENT = "%";
  private static final String PERCENT_WORD = "percent";

  private static final int MAX_UNIT_LENGTH = 6;

  /**
   * How many letters a candidate unit token may have before the scan gives up: one more
   * than {@link #PERCENT_WORD}, the longest token that is not a unit from the set.
   */
  private static final int MAX_TOKEN_LENGTH = 8;

  private final Set<String> units;

  private final NumberNotation notation;
  private final MoneyExtractor moneyExtractor;

  /**
   * Initializes the extractor with the default unit set and
   * {@link NumberNotation#LATIN_US}.
   */
  public CursorQuantityExtractor() {
    this(DEFAULT_UNITS, NumberNotation.LATIN_US);
  }

  /**
   * Initializes the extractor with the default unit set and a number notation.
   *
   * @param notation The written convention numbers group digits and mark fractions in.
   *                 Must not be {@code null}.
   * @throws IllegalArgumentException Thrown if {@code notation} is {@code null}.
   */
  public CursorQuantityExtractor(NumberNotation notation) {
    this(DEFAULT_UNITS, notation);
  }

  /**
   * Initializes the extractor with a custom unit set and {@link NumberNotation#LATIN_US}.
   *
   * @param units The unit tokens to recognize, matched exactly and case-sensitively.
   *              Must not be {@code null} or empty, and no token may be {@code null},
   *              empty, longer than six UTF-16 code units, or contain anything but
   *              Unicode letters.
   * @throws IllegalArgumentException Thrown if the set is {@code null}, empty, or
   *         contains an invalid token.
   */
  public CursorQuantityExtractor(Set<String> units) {
    this(units, NumberNotation.LATIN_US);
  }

  /**
   * Initializes the extractor with a custom unit set and a number notation.
   *
   * @param units The unit tokens to recognize, matched exactly and case-sensitively.
   *              Must not be {@code null} or empty, and no token may be {@code null},
   *              empty, longer than six UTF-16 code units, or contain anything but
   *              Unicode letters.
   * @param notation The written convention numbers group digits and mark fractions in.
   *                 Must not be {@code null}.
   * @throws IllegalArgumentException Thrown if the set is {@code null}, empty, contains
   *         an invalid token, or {@code notation} is {@code null}.
   */
  public CursorQuantityExtractor(Set<String> units, NumberNotation notation) {
    if (units == null || units.isEmpty()) {
      throw new IllegalArgumentException("units must not be null or empty");
    }
    for (final String unit : units) {
      if (!validUnit(unit)) {
        throw new IllegalArgumentException("not a valid unit token: " + unit);
      }
    }
    if (notation == null) {
      throw new IllegalArgumentException("notation must not be null");
    }
    this.units = Set.copyOf(units);
    this.notation = notation;
    this.moneyExtractor = new CursorMoneyExtractor(notation);
  }

  /**
   * Checks whether a token can be consumed by the unit scanner.
   *
   * @param unit The candidate token, or {@code null}.
   * @return {@code true} if the token is non-blank, no longer than the configured limit,
   *         and consists only of letters representable by the scanner.
   */
  private boolean validUnit(String unit) {
    if (unit == null || unit.isEmpty() || unit.length() > MAX_UNIT_LENGTH) {
      return false;
    }
    for (int i = 0; i < unit.length();) {
      final int cp = unit.codePointAt(i);
      if (!Character.isLetter(cp)) {
        return false;
      }
      i += Character.charCount(cp);
    }
    return true;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Excludes recognized money spans and returns non-overlapping quantity mentions.</p>
   */
  @Override
  public List<Quantity> extract(CharSequence text) {
    if (text == null) {
      throw new IllegalArgumentException("text must not be null");
    }
    final List<MoneyAmount> amounts = moneyExtractor.extract(text);
    final List<Quantity> mentions = new ArrayList<>();
    int i = 0;
    int amountIndex = 0;
    while (i < text.length()) {
      while (amountIndex < amounts.size() && amounts.get(amountIndex).span().getEnd() <= i) {
        amountIndex++;
      }
      if (amountIndex < amounts.size() && amounts.get(amountIndex).span().getStart() <= i) {
        i = amounts.get(amountIndex).span().getEnd();
        continue;
      }
      final Quantity mention = matchAt(text, i);
      if (mention != null) {
        if (amountIndex == amounts.size()
            || mention.span().getEnd() <= amounts.get(amountIndex).span().getStart()) {
          mentions.add(mention);
        }
        i = mention.span().getEnd();
      } else {
        i += Character.charCount(Character.codePointAt(text, i));
      }
    }
    return Collections.unmodifiableList(mentions);
  }

  /**
   * Matches a number followed by a percent marker or unit token at one position. A digit
   * that continues a comma-grouped number the scanner rejected never starts a mention,
   * so the tail {@code 000} of {@code 1,00,000 kg} is not read as {@code 0 kg}.
   *
   * @param text The text being scanned.
   * @param start The offset the candidate mention would start at.
   * @return The mention starting at {@code start}, or {@code null} when none matches.
   */
  private Quantity matchAt(CharSequence text, int start) {
    int i = start;
    boolean negative = false;
    if (NumberScan.charAt(text, i) == '-') {
      negative = true;
      i++;
    }
    if (!NumberScan.isAsciiDigit(NumberScan.charAt(text, i))
        || NumberScan.continuesNumber(text, i, notation)
        || !(negative ? NumberScan.signBoundaryBefore(text, start)
            : NumberScan.boundaryBefore(text, start))) {
      return null;
    }
    final NumberScan.Result number = NumberScan.parse(text, i, false, notation);
    if (number == null) {
      return null;
    }
    final BigDecimal value = negative ? number.value().negate() : number.value();
    final Unit unit = parseUnit(text, number.end());
    if (unit == null) {
      return null;
    }
    return new Quantity(new Span(start, unit.end()), value, unit.token());
  }

  /**
   * Parses the percent marker or unit token after a number: immediately attached, or
   * separated by exactly one space.
   *
   * @param text The text being scanned.
   * @param numberEnd The exclusive offset behind the number.
   * @return The unit, or {@code null} when no percent marker or known unit follows.
   */
  private Unit parseUnit(CharSequence text, int numberEnd) {
    final Unit immediate = unitAt(text, numberEnd);
    if (immediate != null) {
      return immediate;
    }
    if (NumberScan.charAt(text, numberEnd) == ' ') {
      return unitAt(text, numberEnd + 1);
    }
    return null;
  }

  /**
   * Reads a percent sign, the word {@code percent}, or a known unit token at a position.
   *
   * @param text The text being scanned.
   * @param start The offset of the first character of the candidate unit.
   * @return The unit, or {@code null} when {@code start} holds no percent marker and no
   *         known unit token.
   */
  private Unit unitAt(CharSequence text, int start) {
    if (NumberScan.charAt(text, start) == '%') {
      return NumberScan.boundaryAfter(text, start + 1) ? new Unit(PERCENT, start + 1) : null;
    }
    final int end = NumberScan.wordEnd(text, start, MAX_TOKEN_LENGTH);
    if (end < 0) {
      return null;
    }
    final String word = text.subSequence(start, end).toString();
    if (PERCENT_WORD.equalsIgnoreCase(word)) {
      return new Unit(PERCENT, end);
    }
    return units.contains(word) ? new Unit(word, end) : null;
  }

  /** An intermediate parse result: the unit token and the exclusive end offset. */
  private record Unit(String token, int end) {
  }
}
