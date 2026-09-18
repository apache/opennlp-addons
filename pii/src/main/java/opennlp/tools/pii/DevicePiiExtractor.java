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
 * Extracts labeled International Mobile Equipment Identity (IMEI) candidates.
 *
 * <p>An IMEI is reported only when an ASCII case-insensitive {@code IMEI} label directly
 * precedes it, separated only by whitespace or {@code :}, {@code #}, or {@code =}. The
 * label must not continue a Unicode letter or digit. The candidate must contain
 * 15 ASCII digits and pass the Luhn check specified by
 * <a href="https://imeidb.gsma.com/imei/resources/documents/TS.06-v22.0.pdf">
 * GSMA TS.06</a>. Single spaces or hyphens may separate digit groups, but may not be
 * mixed.</p>
 *
 * <p>This detector is opt-in. Format and checksum validation do not establish that an
 * identifier was assigned to a device. Normalization removes numeric separators.</p>
 *
 * <p>Instances have no per-call state and may be shared between threads.</p>
 *
 * @since 3.0.0
 */
public final class DevicePiiExtractor implements PiiExtractor {

  private static final String LABEL = "imei";
  private static final int DIGITS = 15;

  /** Initializes an extractor for IMEIs. */
  public DevicePiiExtractor() {
  }

  /**
   * {@inheritDoc}
   *
   * <p>Reports one mention per labeled, checksum-valid IMEI, in text order.</p>
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
      Hits.add(hits, i, end, PiiMention.TYPE_IMEI, normalized.toString());
      i = end - 1;
    }
    return Hits.resolve(hits);
  }

  /**
   * Checks for the required label immediately before a candidate.
   *
   * @param text The text being scanned.
   * @param start The first digit of the candidate.
   * @return {@code true} if an isolated {@code IMEI} label precedes the candidate.
   */
  private boolean hasLabel(CharSequence text, int start) {
    int end = start;
    while (end > 0 && isLabelSeparator(text.charAt(end - 1))) {
      end--;
    }
    final int labelStart = end - LABEL.length();
    return labelStart >= 0 && Ascii.equalsIgnoreCase(text, labelStart, LABEL)
        && Boundaries.onWordStart(text, labelStart);
  }

  /**
   * Parses 15 digits with optional consistent separators.
   *
   * @param text The text being scanned.
   * @param start The first digit.
   * @param normalized The digit collector.
   * @return The exclusive candidate end, or {@code -1} if the form is invalid.
   */
  private int readDigits(CharSequence text, int start, StringBuilder normalized) {
    int p = start;
    char separator = 0;
    while (p < text.length() && normalized.length() < DIGITS) {
      final char c = text.charAt(p);
      if (Ascii.isDigit(c)) {
        normalized.append(c);
        p++;
      } else if ((c == '-' || c == ' ') && p + 1 < text.length()
          && Ascii.isDigit(text.charAt(p + 1))) {
        if (separator != 0 && separator != c) {
          return -1;
        }
        separator = c;
        p++;
      } else {
        break;
      }
    }
    if (normalized.length() != DIGITS || !Boundaries.onEnd(text, p)) {
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
    return Character.isWhitespace(c) || c == ':' || c == '#' || c == '=';
  }
}
