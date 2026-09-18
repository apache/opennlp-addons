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

package opennlp.tools.pii;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Extracts United Kingdom NHS number and German tax identification number candidates.
 * This detector is opt-in.
 *
 * <p>Recognized forms:</p>
 * <ul>
 *   <li>NHS number: 10 ASCII digits, compact or grouped as {@code 3 3 4}. The final digit
 *   must pass the modulus 11 check from the
 *   <a href="https://www.datadictionary.nhs.uk/attributes/nhs_number.html">NHS data
 *   dictionary</a>, using weights 10 down to 2 over the first 9 digits. A check value
 *   of 11 becomes zero; a check value of 10 is rejected. Uniform digit runs are rejected.</li>
 *   <li>German tax identification number: 11 ASCII digits, compact or grouped as
 *   {@code 2 3 3 3}, with an ISO 7064 MOD 11,10 check digit. The
 *   <a href="https://download.elster.de/download/schnittstellen/Pruefung_der_Steuer_und_Steueridentifikatsnummer.pdf">
 *   ELSTER validation rules, section 2.2</a>, require a nonzero leading digit for production
 *   IDs. This detector excludes official leading-zero test IDs. Among the initial 10 digits,
 *   a single digit occurs 2 or 3 times; remaining digits occur at most once. 3 adjacent
 *   occurrences are prohibited.</li>
 * </ul>
 *
 * <p>Groups require matching single ASCII spaces or hyphens. Other separators, including
 * control characters, are rejected. Numeric grouping is checked before the compact form.</p>
 *
 * <p>Normalization removes separators. A match checks format and number rules, not
 * assignment to a person. Unrelated numeric identifiers can match these rules.</p>
 *
 * <p>Both types are reported by default; the {@link #EuIdentityPiiExtractor(Set)} constructor
 * limits extraction to a subset.</p>
 *
 * <p>Instances have no per-call state and may be shared between threads.</p>
 *
 * @since 3.0.0
 */
public final class EuIdentityPiiExtractor implements PiiExtractor {

  private static final Set<String> ALL_TYPES =
      Set.of(PiiMention.TYPE_UK_NHS, PiiMention.TYPE_DE_STEUER_ID);

  private static final int NHS_DIGITS = 10;

  /** The digit counts of the {@code 3 3 4} grouping an NHS number is printed in. */
  private static final int[] NHS_GROUPS = {3, 3, 4};

  private static final int NHS_MODULUS = 11;

  private static final int STEUER_ID_DIGITS = 11;

  /** The digit counts of the {@code 2 3 3 3} grouping a tax number is printed in. */
  private static final int[] STEUER_ID_GROUPS = {2, 3, 3, 3};

  private static final int STEUER_ID_MODULUS = 11;
  private static final int DECIMAL_MODULUS = 10;

  /** Maximum permitted frequency for a digit among the initial 10 tax-number digits. */
  private static final int STEUER_ID_MAX_REPEATS = 3;

  private final Set<String> types;

  /**
   * Initializes an extractor that reports both types.
   */
  public EuIdentityPiiExtractor() {
    this.types = ALL_TYPES;
  }

  /**
   * Initializes an extractor limited to a subset of the types.
   *
   * @param types The types to report: {@link PiiMention#TYPE_UK_NHS} or
   *              {@link PiiMention#TYPE_DE_STEUER_ID}. Must be non-null and non-empty,
   *              without null or unrecognized entries.
   * @throws IllegalArgumentException Thrown if {@code types} is {@code null} or empty, or
   *         contains a null or unrecognized type.
   */
  public EuIdentityPiiExtractor(Set<String> types) {
    if (types == null || types.isEmpty()) {
      throw new IllegalArgumentException("types must not be null or empty");
    }
    for (final String type : types) {
      if (type == null || !ALL_TYPES.contains(type)) {
        throw new IllegalArgumentException("types contains an unrecognized type: " + type);
      }
    }
    this.types = Set.copyOf(types);
  }

  /**
   * {@inheritDoc}
   *
   * <p>Enabled types are scanned separately and their candidates are resolved together.</p>
   */
  @Override
  public List<PiiMention> extract(CharSequence text) {
    if (text == null) {
      throw new IllegalArgumentException("text must not be null");
    }
    final List<Hits.Hit> hits = new ArrayList<>();
    if (types.contains(PiiMention.TYPE_DE_STEUER_ID)) {
      scan(text, hits, STEUER_ID_GROUPS, STEUER_ID_DIGITS, PiiMention.TYPE_DE_STEUER_ID);
    }
    if (types.contains(PiiMention.TYPE_UK_NHS)) {
      scan(text, hits, NHS_GROUPS, NHS_DIGITS, PiiMention.TYPE_UK_NHS);
    }
    return Hits.resolve(hits);
  }

  /**
   * Finds an identifier type, checking the grouped form before the compact form.
   *
   * @param text The text to scan.
   * @param hits The candidate collector.
   * @param groups The digit counts of the grouped form.
   * @param digits The number of digits the identifier has.
   * @param type The type to report.
   */
  private void scan(CharSequence text, List<Hits.Hit> hits, int[] groups, int digits,
      String type) {
    final int[] value = new int[digits];
    for (int i = 0; i < text.length(); i++) {
      if (!Ascii.isDigit(text.charAt(i)) || !Boundaries.onNumberStart(text, i)) {
        continue;
      }
      int end = readGrouped(text, i, groups, value);
      if (end < 0) {
        end = readBare(text, i, digits, value);
      }
      if (end < 0 || !valid(value, type)) {
        continue;
      }
      final StringBuilder normalized = new StringBuilder(digits);
      for (final int digit : value) {
        normalized.append((char) ('0' + digit));
      }
      Hits.add(hits, i, end, type, normalized.toString());
      // The loop increment resumes the scan at the exclusive match end.
      i = end - 1;
    }
  }

  /**
   * Parses an identifier in the printed grouping.
   *
   * @param text The text being scanned.
   * @param start The offset to read from.
   * @param groups The digit counts of the groups.
   * @param value Receives the digits when the read succeeds.
   * @return The exclusive end offset, or {@code -1} if the grouped form does not start here.
   */
  private int readGrouped(CharSequence text, int start, int[] groups, int[] value) {
    int p = start;
    int digits = 0;
    char separator = 0;
    for (int g = 0; g < groups.length; g++) {
      if (g > 0) {
        if (p >= text.length()) {
          return -1;
        }
        final char c = text.charAt(p);
        if ((c != ' ' && c != '-') || (g > 1 && c != separator)) {
          return -1;
        }
        separator = c;
        p++;
      }
      for (int d = 0; d < groups[g]; d++) {
        if (p >= text.length() || !Ascii.isDigit(text.charAt(p))) {
          return -1;
        }
        value[digits++] = text.charAt(p) - '0';
        p++;
      }
    }
    return onNumberEnd(text, p) ? p : -1;
  }

  /**
   * Parses an identifier written as a continuous run of digits.
   *
   * @param text The text being scanned.
   * @param start The offset to read from.
   * @param digits The number of digits the identifier has.
   * @param value Receives the digits when the read succeeds.
   * @return The exclusive end offset, or {@code -1} if a run of the requested length does not
   *         start here.
   */
  private int readBare(CharSequence text, int start, int digits, int[] value) {
    final int end = start + digits;
    if (end > text.length() || !onNumberEnd(text, end)) {
      return -1;
    }
    for (int i = start; i < end; i++) {
      if (!Ascii.isDigit(text.charAt(i))) {
        return -1;
      }
      value[i - start] = text.charAt(i) - '0';
    }
    return end;
  }

  /**
   * Validates a candidate's digits for the selected type.
   *
   * @param value The digits.
   * @param type The type to validate for.
   * @return {@code true} if the candidate passes this type's format checks.
   */
  private boolean valid(int[] value, String type) {
    return PiiMention.TYPE_UK_NHS.equals(type) ? validNhs(value) : validSteuerId(value);
  }

  /**
   * Applies the NHS modulus 11 check and rejects uniform digit runs.
   *
   * @param value The 10 digits.
   * @return {@code true} if the check digit passes and the digits are not uniform.
   */
  private boolean validNhs(int[] value) {
    boolean uniform = true;
    for (int i = 1; i < NHS_DIGITS; i++) {
      uniform &= value[i] == value[0];
    }
    if (uniform) {
      return false;
    }
    int sum = 0;
    for (int i = 0; i < NHS_DIGITS - 1; i++) {
      sum += value[i] * (NHS_DIGITS - i);
    }
    final int complement = NHS_MODULUS - sum % NHS_MODULUS;
    final int check = complement == NHS_MODULUS ? 0 : complement;
    return check != DECIMAL_MODULUS && check == value[NHS_DIGITS - 1];
  }

  /**
   * Applies the rules of a German tax identification number: the leading digit, the digit
   * repetition rule, and the ISO 7064 MOD 11,10 check digit.
   *
   * @param value The 11 digits.
   * @return {@code true} if the digits satisfy the number rules and checksum.
   */
  private boolean validSteuerId(int[] value) {
    if (value[0] == 0 || !repetitionValid(value)) {
      return false;
    }
    int product = DECIMAL_MODULUS;
    for (int i = 0; i < STEUER_ID_DIGITS - 1; i++) {
      int sum = (value[i] + product) % DECIMAL_MODULUS;
      if (sum == 0) {
        sum = DECIMAL_MODULUS;
      }
      product = sum * 2 % STEUER_ID_MODULUS;
    }
    final int complement = STEUER_ID_MODULUS - product;
    final int check = complement == DECIMAL_MODULUS ? 0 : complement;
    return check == value[STEUER_ID_DIGITS - 1];
  }

  /**
   * Applies the digit repetition rule to the initial 10 digits. A single digit occurs 2 or
   * 3 times, remaining digits occur at most once, and 3 adjacent occurrences are prohibited.
   *
   * @param value The 11 digits.
   * @return {@code true} if the digits are spread as the rule demands.
   */
  private boolean repetitionValid(int[] value) {
    final int[] counts = new int[DECIMAL_MODULUS];
    for (int i = 0; i < STEUER_ID_DIGITS - 1; i++) {
      counts[value[i]]++;
    }
    int repeated = -1;
    for (int digit = 0; digit < counts.length; digit++) {
      if (counts[digit] < 2) {
        continue;
      }
      if (repeated >= 0 || counts[digit] > STEUER_ID_MAX_REPEATS) {
        return false;
      }
      repeated = digit;
    }
    if (repeated < 0) {
      return false;
    }
    if (counts[repeated] < STEUER_ID_MAX_REPEATS) {
      return true;
    }
    for (int i = 0; i + 2 < STEUER_ID_DIGITS - 1; i++) {
      if (value[i] == repeated && value[i + 1] == repeated && value[i + 2] == repeated) {
        return false;
      }
    }
    return true;
  }

  /**
   * Checks a numeric candidate's end boundary and a possible following digit group.
   *
   * @param text The text being scanned.
   * @param end The candidate end, exclusive.
   * @return {@code true} if the candidate may end here.
   */
  private boolean onNumberEnd(CharSequence text, int end) {
    if (!Boundaries.onEnd(text, end)) {
      return false;
    }
    if (end + 1 >= text.length()) {
      return true;
    }
    final char next = text.charAt(end);
    return (next != '.' && next != ',' && next != '-' && next != ' ')
        || !Ascii.isDigit(text.charAt(end + 1));
  }
}
