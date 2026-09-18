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
 * A deterministic {@link PiiExtractor}: forward scans over the text, no regular
 * expressions, recognizing email addresses, phone numbers, IBANs, and payment card
 * numbers. IBANs and card numbers are checksum validated. Phone numbers require a
 * {@code +} prefix or visible formatting.
 *
 * <p>Recognized forms:</p>
 * <ul>
 *   <li>Email: a local part of ASCII letters, digits, and {@code . _ % + -} followed by
 *   {@code @} and a dotted domain of at most {@link #DOMAIN_MAX_LENGTH} characters. The
 *   final label must be an
 *   <a href="https://data.iana.org/TLD/tlds-alpha-by-domain.txt">IANA-registered</a>
 *   top-level domain, including punycode forms. Private-use suffixes such as
 *   {@code .internal} or {@code .local} are not reported. The ASCII local part is limited
 *   to 64 characters and the complete mailbox to 254 characters.</li>
 *   <li>Phone: an international form with {@code +} and digits that split into an
 *   assigned calling code and a national number length used by a territory under that
 *   code, or a domestic form with 10 or 11 digits and at least one space, hyphen, or
 *   parenthesis between digits. An unformatted digit run is not a phone number. Dots
 *   are excluded as separators to avoid decimal numbers.</li>
 *   <li>IBAN: 2 uppercase letters, 2 check digits, and more uppercase letters or
 *   digits, optionally in space-separated groups, validated with the
 *   <a href="https://en.wikipedia.org/wiki/International_Bank_Account_Number">ISO 13616</a>
 *   mod-97 check. The country code must be in the ISO 13616 registry and the candidate
 *   must match the length assigned by that registry entry, so a checksum-passing run
 *   with an unregistered country or invalid length is rejected.</li>
 *   <li>Card: 13 to 19 digits, optionally separated by single spaces or hyphens,
 *   validated with the <a href="https://en.wikipedia.org/wiki/Luhn_algorithm">Luhn</a>
 *   check and required to start with a digit between 2 and 6 or with
 *   <a href="https://www.unionpayintl.com/en/mediaCenter/brandCenter/brandEmbodiment/">
 *   UnionPay's 81 prefix</a>. The prefix is checked after removing separators.
 *   When the full run fails the check,
 *   shorter separator-delimited prefixes are tried longest first, so a trailing
 *   separated digit group, such as an expiry date, does not hide the card before
 *   it.</li>
 * </ul>
 *
 * <p>Overlapping candidates are retained in start-offset order, with longer spans first
 * at the same start. Equal spans use the type order email, IBAN, card, phone.
 * Candidates must satisfy the word-boundary checks.</p>
 *
 * <p>Normalized forms: email domains are lowercased while mailbox local-part case is
 * preserved, IBANs keep their uppercase letters and digits with separators removed,
 * and phone and card numbers keep digits only, with a leading {@code +} preserved for
 * phone numbers.</p>
 *
 * <p>All supported types are reported by default; the {@link #CursorPiiExtractor(Set)}
 * constructor limits extraction to a subset.</p>
 *
 * <p>The extractor stores no per-call state and is safe to share between threads.</p>
 *
 * @since 3.0.0
 */
public final class CursorPiiExtractor implements PiiExtractor {

  private static final Set<String> ALL_TYPES = Set.of(PiiMention.TYPE_EMAIL,
      PiiMention.TYPE_PHONE, PiiMention.TYPE_IBAN, PiiMention.TYPE_CARD);

  private static final int IBAN_MAX_LENGTH = 34;
  private static final int IBAN_MODULUS = 97;
  private static final int IBAN_ROTATION = 4;
  private static final int CARD_MIN_DIGITS = 13;
  private static final int CARD_MAX_DIGITS = 19;
  private static final char UNIONPAY_LEADING_DIGIT = '8';
  private static final int PHONE_MAX_DIGITS = 15;
  private static final int PHONE_DOMESTIC_MIN_DIGITS = 10;
  private static final int PHONE_DOMESTIC_MAX_DIGITS = 11;
  private static final int DOMAIN_LABEL_MAX_LENGTH = 63;
  private static final int EMAIL_LOCAL_PART_MAX_LENGTH = 64;
  private static final int EMAIL_MAX_LENGTH = 254;

  /**
   * Maximum domain length in presentation form
   * (<a href="https://datatracker.ietf.org/doc/html/rfc1035">RFC 1035</a>: 255 octets on
   * the wire, 253 in presentation form).
   */
  static final int DOMAIN_MAX_LENGTH = 253;

  private final Set<String> types;

  /**
   * Initializes an extractor that reports all supported types.
   */
  public CursorPiiExtractor() {
    this.types = ALL_TYPES;
  }

  /**
   * Initializes an extractor for selected contact and payment types.
   *
   * @param types The types to report: {@link PiiMention#TYPE_EMAIL},
   *              {@link PiiMention#TYPE_PHONE}, {@link PiiMention#TYPE_IBAN} or
   *              {@link PiiMention#TYPE_CARD}. Must be non-null and non-empty,
   *              without null or unrecognized entries.
   * @throws IllegalArgumentException Thrown if {@code types} is {@code null} or empty,
   *         or contains a null or unrecognized type.
   */
  public CursorPiiExtractor(Set<String> types) {
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
   * <p>Enabled types are scanned independently and overlapping candidates are retained.</p>
   */
  @Override
  public List<PiiMention> extract(CharSequence text) {
    if (text == null) {
      throw new IllegalArgumentException("text must not be null");
    }
    final List<Hits.Hit> hits = new ArrayList<>();
    if (types.contains(PiiMention.TYPE_EMAIL)) {
      scanEmails(text, hits);
    }
    if (types.contains(PiiMention.TYPE_IBAN)) {
      scanIbans(text, hits);
    }
    if (types.contains(PiiMention.TYPE_CARD)) {
      scanCards(text, hits);
    }
    if (types.contains(PiiMention.TYPE_PHONE)) {
      scanPhones(text, hits);
    }
    return Hits.resolve(hits);
  }

  /**
   * Finds email addresses by expanding around each {@code @}. Checks the domain
   * boundary before removing terminal punctuation.
   *
   * @param text The text to scan.
   * @param hits The candidate collector.
   */
  private void scanEmails(CharSequence text, List<Hits.Hit> hits) {
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) != '@') {
        continue;
      }
      int start = i;
      while (start > 0 && isLocalChar(text.charAt(start - 1))) {
        start--;
      }
      if (start == i || !validLocalPart(text, start, i)) {
        continue;
      }
      int end = i + 1;
      while (end < text.length() && isDomainChar(text.charAt(end))) {
        end++;
      }
      if (!Boundaries.onEnd(text, end)) {
        continue;
      }
      while (end > i + 1 && (text.charAt(end - 1) == '.' || text.charAt(end - 1) == '-')) {
        end--;
      }
      if (end == i + 1
          || end - start > EMAIL_MAX_LENGTH
          || !validDomain(text, i + 1, end)
          || (start > 0 && Character.isLetterOrDigit(Character.codePointBefore(text, start)))) {
        continue;
      }
      final StringBuilder normalized = new StringBuilder(end - start);
      normalized.append(text, start, i + 1);
      for (int p = i + 1; p < end; p++) {
        normalized.append(Ascii.toLower(text.charAt(p)));
      }
      Hits.add(hits, start, end, PiiMention.TYPE_EMAIL, normalized.toString());
    }
  }

  /**
   * Checks a local part: not empty, no leading, trailing, or doubled dot.
   *
   * @param text The text being scanned.
   * @param start The local part start, inclusive.
   * @param at The position of the {@code @}.
   * @return {@code true} if the local part is acceptable.
   */
  private boolean validLocalPart(CharSequence text, int start, int at) {
    if (at - start > EMAIL_LOCAL_PART_MAX_LENGTH
        || text.charAt(start) == '.' || text.charAt(at - 1) == '.') {
      return false;
    }
    for (int i = start + 1; i < at; i++) {
      if (text.charAt(i) == '.' && text.charAt(i - 1) == '.') {
        return false;
      }
    }
    return true;
  }

  /**
   * Checks a domain: at most {@link #DOMAIN_MAX_LENGTH} characters, at least 2 labels,
   * each 1 to 63 characters without a leading or trailing hyphen, and a final label that
   * is an {@link IanaTlds IANA-registered} top-level domain.
   *
   * @param text The text being scanned.
   * @param start The domain start, inclusive.
   * @param end The domain end, exclusive.
   * @return {@code true} if the domain is acceptable.
   */
  private boolean validDomain(CharSequence text, int start, int end) {
    if (end - start > DOMAIN_MAX_LENGTH) {
      return false;
    }
    int labels = 0;
    int labelStart = start;
    int tldStart = start;
    for (int i = start; i <= end; i++) {
      if (i == end || text.charAt(i) == '.') {
        final int length = i - labelStart;
        if (length < 1 || length > DOMAIN_LABEL_MAX_LENGTH
            || text.charAt(labelStart) == '-' || text.charAt(i - 1) == '-') {
          return false;
        }
        labels++;
        tldStart = labelStart;
        labelStart = i + 1;
      }
    }
    if (labels < 2) {
      return false;
    }
    return IanaTlds.registered(text, tldStart, end);
  }

  /**
   * Finds uppercase IBAN candidates with a registered country/length and a passing
   * MOD 97 checksum. Spaces may separate groups after the 4-character header.
   *
   * @param text The text to scan.
   * @param hits The candidate collector.
   */
  private void scanIbans(CharSequence text, List<Hits.Hit> hits) {
    for (int i = 0; i < text.length(); i++) {
      if (!Ascii.isUpper(text.charAt(i))
          || (i > 0 && Character.isLetterOrDigit(Character.codePointBefore(text, i)))
          || i + 3 >= text.length()
          || !Ascii.isUpper(text.charAt(i + 1))
          || !Ascii.isDigit(text.charAt(i + 2))
          || !Ascii.isDigit(text.charAt(i + 3))) {
        continue;
      }
      final StringBuilder compact = new StringBuilder();
      final List<int[]> groupEnds = new ArrayList<>();
      int p = i;
      while (p < text.length() && compact.length() <= IBAN_MAX_LENGTH) {
        final char c = text.charAt(p);
        if (Ascii.isUpper(c) || Ascii.isDigit(c)) {
          compact.append(c);
          p++;
        } else if (c == ' ' && p + 1 < text.length()
            && (Ascii.isUpper(text.charAt(p + 1)) || Ascii.isDigit(text.charAt(p + 1)))) {
          groupEnds.add(new int[] {p, compact.length()});
          p++;
        } else {
          break;
        }
      }
      groupEnds.add(new int[] {p, compact.length()});
      final int registeredLength =
          IbanLengths.registeredLength(text.charAt(i), text.charAt(i + 1));
      for (int g = groupEnds.size() - 1; g >= 0; g--) {
        final int textEnd = groupEnds.get(g)[0];
        final int length = groupEnds.get(g)[1];
        if (length != registeredLength || !Boundaries.onEnd(text, textEnd)) {
          continue;
        }
        if (mod97(compact, length) == 1) {
          final String candidate = compact.substring(0, length);
          Hits.add(hits, i, textEnd, PiiMention.TYPE_IBAN, candidate);
          // The loop increment resumes the scan at the exclusive match end.
          i = textEnd - 1;
          break;
        }
      }
    }
  }

  /**
   * Finds card numbers with an accepted prefix and a passing Luhn check. Single
   * spaces and hyphens may separate digits, including prefix digits. Candidates
   * are tried longest first at separator boundaries.
   *
   * @param text The text to scan.
   * @param hits The candidate collector.
   */
  private void scanCards(CharSequence text, List<Hits.Hit> hits) {
    for (int i = 0; i < text.length(); i++) {
      final char first = text.charAt(i);
      if (((first < '2' || first > '6') && first != UNIONPAY_LEADING_DIGIT)
          || !Boundaries.onNumberStart(text, i)) {
        continue;
      }
      final StringBuilder digits = new StringBuilder();
      final List<int[]> groupEnds = new ArrayList<>();
      int lastDigit = -1;
      boolean previousSeparator = false;
      int p = i;
      while (p < text.length() && digits.length() <= CARD_MAX_DIGITS) {
        final char c = text.charAt(p);
        if (Ascii.isDigit(c)) {
          digits.append(c);
          lastDigit = p;
          previousSeparator = false;
          p++;
        } else if ((c == ' ' || c == '-') && !previousSeparator) {
          groupEnds.add(new int[] {lastDigit + 1, digits.length()});
          previousSeparator = true;
          p++;
        } else {
          break;
        }
      }
      groupEnds.add(new int[] {lastDigit + 1, digits.length()});
      for (int g = groupEnds.size() - 1; g >= 0; g--) {
        final int end = groupEnds.get(g)[0];
        final int length = groupEnds.get(g)[1];
        if (length < CARD_MIN_DIGITS || length > CARD_MAX_DIGITS
            || (first == UNIONPAY_LEADING_DIGIT && digits.charAt(1) != '1')
            || !Boundaries.onEnd(text, end)
            || !Luhn.valid(digits, length)) {
          continue;
        }
        final String candidate = digits.substring(0, length);
        Hits.add(hits, i, end, PiiMention.TYPE_CARD, candidate);
        // The loop increment resumes the scan at the exclusive match end.
        i = end - 1;
        break;
      }
    }
  }

  /**
   * Finds international and formatted domestic phone candidates. International lengths
   * use {@link PhoneNumberLengths}. When a longer run fails, separator-delimited prefixes
   * are tried longest first. Paired parentheses are included in the selected span.
   *
   * @param text The text to scan.
   * @param hits The candidate collector.
   */
  private void scanPhones(CharSequence text, List<Hits.Hit> hits) {
    int lastEnd = -1;
    for (int i = 0; i < text.length(); i++) {
      final char c = text.charAt(i);
      final boolean plus = c == '+';
      // Adjacent phones may start at the preceding phone's exclusive end.
      if ((!plus && !Ascii.isDigit(c) && c != '(')
          || (i != lastEnd && !Boundaries.onNumberStart(text, i))
          || (i > 0 && text.charAt(i - 1) == '+')) {
        continue;
      }
      int digits = 0;
      int candidateEnd = i;
      int open = 0;
      int close = 0;
      boolean separated = false;
      boolean previousSeparator = false;
      final StringBuilder digitRun = new StringBuilder();
      // Candidate cuts store the exclusive end, digit count, formatting flag and
      // parenthesis counts.
      final List<int[]> groups = new ArrayList<>();
      int p = plus ? i + 1 : i;
      while (p < text.length() && digits <= PHONE_MAX_DIGITS) {
        final char ch = text.charAt(p);
        if (Ascii.isDigit(ch)) {
          if (digits > 0 && p > i && !Ascii.isDigit(text.charAt(p - 1))) {
            separated = true;
          }
          digits++;
          digitRun.append(ch);
          candidateEnd = p + 1;
          previousSeparator = false;
          p++;
        } else if ((ch == ' ' || ch == '-') && !previousSeparator) {
          groups.add(new int[] {candidateEnd, digits, separated ? 1 : 0, open, close});
          previousSeparator = true;
          p++;
        } else if (ch == '(' && open == 0) {
          open++;
          previousSeparator = false;
          p++;
        } else if (ch == ')' && close == 0 && open == 1) {
          close++;
          candidateEnd = p + 1;
          previousSeparator = false;
          p++;
        } else {
          break;
        }
      }
      groups.add(new int[] {candidateEnd, digits, separated ? 1 : 0, open, close});
      for (int g = groups.size() - 1; g >= 0; g--) {
        final int end = groups.get(g)[0];
        final int count = groups.get(g)[1];
        final boolean visiblySeparated = groups.get(g)[2] == 1;
        if (count == 0 || groups.get(g)[3] != groups.get(g)[4]) {
          continue;
        }
        final String candidate = digitRun.substring(0, count);
        final boolean lengthOk = plus
            ? count <= PHONE_MAX_DIGITS && PhoneNumberLengths.plausibleInternational(candidate)
            : count >= PHONE_DOMESTIC_MIN_DIGITS && count <= PHONE_DOMESTIC_MAX_DIGITS
                && visiblySeparated;
        if (!lengthOk
            || !Boundaries.onEnd(text, end)
            || (end + 1 < text.length() && text.charAt(end) == '.'
                && Ascii.isDigit(text.charAt(end + 1)))) {
          continue;
        }
        Hits.add(hits, i, end, PiiMention.TYPE_PHONE,
            plus ? "+" + candidate : candidate);
        lastEnd = end;
        // The loop increment resumes the scan at the exclusive match end.
        i = end - 1;
        break;
      }
    }
  }

  /**
   * Computes the <a href="https://www.tcmb.gov.tr/wps/wcm/connect/EN/TCMB%20EN/Bottom%20Menu/IBAN/Communique">
   * MOD 97 remainder</a> after moving the 4-character header to the end and converting
   * uppercase letters to decimal values 10 through 35.
   *
   * @param compact The candidate characters without spaces, uppercase letters and digits
   *                only.
   * @param length The candidate length, at least 5.
   * @return The remainder; {@code 1} indicates a passing checksum.
   */
  private int mod97(CharSequence compact, int length) {
    int remainder = 0;
    for (int i = 0; i < length; i++) {
      final char c = compact.charAt((i + IBAN_ROTATION) % length);
      if (Ascii.isDigit(c)) {
        remainder = (remainder * 10 + (c - '0')) % IBAN_MODULUS;
      } else {
        remainder = (remainder * 100 + (c - 'A' + 10)) % IBAN_MODULUS;
      }
    }
    return remainder;
  }

  /**
   * Tests for a character allowed in an email local part.
   *
   * @param c The character.
   * @return {@code true} for ASCII letters, digits, and {@code . _ % + -}.
   */
  private boolean isLocalChar(char c) {
    return Ascii.isLetter(c) || Ascii.isDigit(c)
        || c == '.' || c == '_' || c == '%' || c == '+' || c == '-';
  }

  /**
   * Tests for a character allowed in an email domain.
   *
   * @param c The character.
   * @return {@code true} for ASCII letters, digits, dot, and hyphen.
   */
  private boolean isDomainChar(char c) {
    return Ascii.isLetter(c) || Ascii.isDigit(c) || c == '.' || c == '-';
  }
}
