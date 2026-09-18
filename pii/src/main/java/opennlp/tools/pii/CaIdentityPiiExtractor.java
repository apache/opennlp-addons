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

/**
 * Extracts labeled Canadian
 * <a href="https://www.canada.ca/en/employment-social-development/services/sin.html">
 * Social Insurance Number</a> candidates.
 *
 * <p>Accepts 9 ASCII digits, compact or in 3 groups of 3 separated by a single
 * space or hyphen. Both separators must be present and match. Candidates must pass the
 * Luhn check and directly follow an ASCII case-insensitive {@code SIN},
 * {@code Social Insurance Number} or {@code Social Insurance No.} label. A label must not
 * continue a Unicode letter or digit. Whitespace and {@code :}, {@code #}, {@code =}
 * or {@code .} may separate the label and value.</p>
 *
 * <p>This detector is opt-in. Format and checksum validation do not establish that a
 * number was issued. Normalization removes numeric separators.</p>
 *
 * <p>Instances have no per-call state and may be shared between threads.</p>
 *
 * @since 3.0.0
 */
public final class CaIdentityPiiExtractor implements PiiExtractor {

  private static final String[] LABELS =
      {"social insurance number", "social insurance no", "sin"};
  private static final int DIGITS = 9;
  private static final int GROUP_DIGITS = 3;

  /** Initializes an extractor for Canadian Social Insurance Numbers. */
  public CaIdentityPiiExtractor() {
  }

  /**
   * {@inheritDoc}
   *
   * <p>Reports one mention per labeled, checksum-valid SIN, in text order.</p>
   */
  @Override
  public List<PiiMention> extract(CharSequence text) {
    if (text == null) {
      throw new IllegalArgumentException("text must not be null");
    }
    final List<Hits.Hit> hits = new ArrayList<>();
    for (int i = 0; i < text.length(); i++) {
      if (!Ascii.isDigit(text.charAt(i)) || !Boundaries.onNumberStart(text, i)
          || !hasLabel(text, i)) {
        continue;
      }
      final StringBuilder normalized = new StringBuilder(DIGITS);
      final int end = readDigits(text, i, normalized);
      if (end < 0 || !Luhn.valid(normalized, DIGITS)) {
        continue;
      }
      Hits.add(hits, i, end, PiiMention.TYPE_CA_SIN, normalized.toString());
      i = end - 1;
    }
    return Hits.resolve(hits);
  }

  /**
   * Checks for one of the required labels immediately before a candidate.
   *
   * @param text The text being scanned.
   * @param start The first digit of the candidate.
   * @return {@code true} if an isolated recognized label precedes the candidate.
   */
  private boolean hasLabel(CharSequence text, int start) {
    int end = start;
    while (end > 0 && isLabelSeparator(text.charAt(end - 1))) {
      end--;
    }
    for (final String label : LABELS) {
      final int labelStart = end - label.length();
      if (labelStart >= 0 && Ascii.equalsIgnoreCase(text, labelStart, label)
          && Boundaries.onWordStart(text, labelStart)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Parses a compact 9-digit candidate or 3 equal groups with matching separators.
   *
   * @param text The text being scanned.
   * @param start The first digit.
   * @param normalized The digit collector.
   * @return The exclusive candidate end, or {@code -1} if the form is invalid.
   */
  private int readDigits(CharSequence text, int start, StringBuilder normalized) {
    int p = start;
    char separator = 0;
    for (int count = 0; count < DIGITS; count++) {
      if (count == GROUP_DIGITS && p < text.length()
          && (text.charAt(p) == '-' || text.charAt(p) == ' ')) {
        separator = text.charAt(p);
      }
      if (count > 0 && count % GROUP_DIGITS == 0 && separator != 0) {
        if (p >= text.length() || text.charAt(p) != separator) {
          return -1;
        }
        p++;
      }
      if (p >= text.length() || !Ascii.isDigit(text.charAt(p))) {
        return -1;
      }
      normalized.append(text.charAt(p++));
    }
    if (!Boundaries.onEnd(text, p)) {
      return -1;
    }
    if (p + 1 < text.length() && (text.charAt(p) == '-' || text.charAt(p) == ' ')
        && Ascii.isDigit(text.charAt(p + 1))) {
      return -1;
    }
    return p;
  }

  /**
   * Tests for punctuation permitted between a label and value.
   *
   * @param c The character.
   * @return {@code true} for accepted label separators.
   */
  private boolean isLabelSeparator(char c) {
    return Character.isWhitespace(c) || c == ':' || c == '#' || c == '=' || c == '.';
  }
}
