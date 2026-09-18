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

package opennlp.tools.noise;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

import opennlp.tools.util.Span;
import opennlp.tools.util.StringUtil;

/**
 * Scores whitespace-delimited ASCII tokens using character-pattern heuristics.
 *
 * <p>After common ASCII punctuation is trimmed, cores shorter than 3 characters or
 * containing non-ASCII characters are skipped. Signals include consonant runs,
 * repeated characters, low vowel proportions, and letter-digit transitions. One
 * signal produces {@link NoiseSpan#SEVERITY_DAMAGED}; multiple signals produce
 * {@link NoiseSpan#SEVERITY_GIBBERISH}. Long base64-alphabet tokens with symbols or
 * frequent case changes produce {@link NoiseSpan#SEVERITY_BINARYISH}.</p>
 *
 * <p>Dictionary-accepted words are omitted. For remaining tokens with no other signal,
 * an OCR substitution producing a dictionary word results in
 * {@link NoiseSpan#SEVERITY_MISSPELLED}. This category requires a dictionary.</p>
 *
 * <p>Technical identifiers can produce false positives. Damaged text with ordinary
 * letter patterns can produce false negatives. Tokens overlapping an exclusion are
 * omitted. Results separated by whitespace are merged unless that whitespace is
 * excluded.</p>
 *
 * <p>The scorer is stateless beyond its dictionary and safe for concurrent use when
 * the dictionary is.</p>
 *
 * @since 3.0.0
 */
public final class StructuralNoiseScorer implements NoiseScorer {

  /** Minimum core length to examine. */
  private static final int MIN_CORE_LENGTH = 3;

  /** Minimum length for a binary-content result. */
  private static final int BINARYISH_MIN_LENGTH = 24;

  /** Core length corresponding to the maximum binary-content score. */
  private static final double BINARYISH_SATURATION_LENGTH = 48.0;

  /** Minimum adjacent case changes in an alphabetic binary-content candidate. */
  private static final int BINARYISH_MIN_CASE_FLIPS = 12;

  /** Minimum case changes for a long alphanumeric token containing digits. */
  private static final int BINARYISH_MIN_ALNUM_CASE_FLIPS = 8;

  /** Minimum consecutive consonants for a structural signal. */
  private static final int CONSONANT_RUN_SIGNAL = 7;

  /** Minimum consecutive repetitions for a structural signal. */
  private static final int REPEAT_RUN_SIGNAL = 4;

  /** Maximum positive vowel proportion for a structural signal. */
  private static final double LOW_VOWEL_RATIO = 0.10;

  /** A vowelless core needs at least this many letters to raise a signal. */
  private static final int VOWELLESS_MIN_LETTERS = 5;

  /** Minimum letter count for the positive vowel-proportion test. */
  private static final int LOW_VOWEL_MIN_LETTERS = 8;

  /** Minimum letter-digit transitions for a structural signal. */
  private static final int INTERLEAVE_SIGNAL = 4;

  /** Minimum core length for the letter-digit transition test. */
  private static final int INTERLEAVE_MIN_LENGTH = 8;

  /** Minimum structural signal count for the gibberish category. */
  private static final int GIBBERISH_MIN_SIGNALS = 2;

  /** The signal count at which the gibberish score reaches {@code 1.0}. */
  private static final double GIBBERISH_SATURATION_SIGNALS = 4.0;

  /** The score of a single-signal finding. */
  private static final double DAMAGED_SCORE = 0.5;

  /** The score of a core one confusion repair away from a dictionary word. */
  private static final double MISSPELLED_SCORE = 0.9;

  /**
   * OCR substitutions to try when checking dictionary repairs, as source and replacement.
   */
  private static final String[][] CONFUSIONS = {
      {"rn", "m"}, {"m", "rn"},
      {"vv", "w"}, {"w", "vv"},
      {"cl", "d"},
      {"1", "l"}, {"l", "1"},
      {"0", "o"}, {"o", "0"},
      {"5", "s"},
  };

  private final Predicate<CharSequence> dictionary;

  /** Initializes the scorer without dictionary-based spelling checks. */
  public StructuralNoiseScorer() {
    this.dictionary = null;
  }

  /**
   * Initializes the scorer with a dictionary.
   *
   * @param dictionary Accepts the lowercase form of a known word. Must not be
   *                   {@code null}.
   * @throws IllegalArgumentException Thrown if {@code dictionary} is {@code null}.
   */
  public StructuralNoiseScorer(Predicate<CharSequence> dictionary) {
    if (dictionary == null) {
      throw new IllegalArgumentException("dictionary must not be null");
    }
    this.dictionary = dictionary;
  }

  /** {@inheritDoc} */
  @Override
  public List<NoiseSpan> score(CharSequence text, Collection<Span> exclude) {
    if (text == null) {
      throw new IllegalArgumentException("text must not be null");
    }
    if (exclude == null) {
      throw new IllegalArgumentException("exclude must not be null");
    }
    for (final Span span : exclude) {
      if (span == null) {
        throw new IllegalArgumentException("exclude must not contain null");
      }
      if (span.getEnd() > text.length()) {
        throw new IllegalArgumentException("exclude spans must fit within text");
      }
    }
    final List<NoiseSpan> findings = new ArrayList<>();
    final int length = text.length();
    int i = 0;
    while (i < length) {
      if (StringUtil.isWhitespace(text.charAt(i))) {
        i++;
        continue;
      }
      final int start = i;
      while (i < length && !StringUtil.isWhitespace(text.charAt(i))) {
        i++;
      }
      if (!overlapsAny(start, i, exclude)) {
        final NoiseSpan scored = scoreToken(text, start, i);
        if (scored != null) {
          findings.add(scored);
        }
      }
    }
    return merge(findings, text, exclude);
  }

  /**
   * Scores one token.
   *
   * @param text The text.
   * @param start The token start.
   * @param end The token end.
   * @return The finding, or {@code null} for a clean token.
   */
  private NoiseSpan scoreToken(CharSequence text, int start, int end) {
    final String token = text.subSequence(start, end).toString();
    final int from = leadingPunctuation(token);
    final String core = trimPunctuation(token, from);
    if (core.length() < MIN_CORE_LENGTH || !isAscii(core)) {
      return null;
    }
    final int coreStart = start + from;
    final Span span = new Span(coreStart, coreStart + core.length());
    final String lower = StringUtil.toLowerCase(core);
    if (dictionary != null && dictionary.test(lower)) {
      return null;
    }
    final NoiseSpan binaryish = binaryish(core, span);
    if (binaryish != null) {
      return binaryish;
    }
    final NoiseSpan structural = structural(lower, span);
    if (structural != null) {
      return structural;
    }
    return misspelled(lower, span);
  }

  /**
   * Checks the base64 alphabet, token length, and adjacent case changes.
   *
   * @param core The token without surrounding punctuation.
   * @param span The token span.
   * @return The finding, or {@code null}.
   */
  private NoiseSpan binaryish(String core, Span span) {
    if (core.length() < BINARYISH_MIN_LENGTH) {
      return null;
    }
    boolean digit = false;
    boolean symbol = false;
    int caseFlips = 0;
    boolean lastUpper = false;
    boolean lastLetter = false;
    for (int i = 0; i < core.length(); i++) {
      final char c = core.charAt(i);
      final boolean upper = c >= 'A' && c <= 'Z';
      final boolean lower = c >= 'a' && c <= 'z';
      if (c >= '0' && c <= '9') {
        digit = true;
      } else if (c == '+' || c == '/' || c == '=') {
        symbol = true;
      } else if (!upper && !lower) {
        return null;
      }
      if ((upper || lower) && lastLetter && upper != lastUpper) {
        caseFlips++;
      }
      lastLetter = upper || lower;
      lastUpper = upper;
    }
    if (symbol || caseFlips >= BINARYISH_MIN_CASE_FLIPS
        || (digit && caseFlips >= BINARYISH_MIN_ALNUM_CASE_FLIPS)) {
      return new NoiseSpan(span, NoiseSpan.SEVERITY_BINARYISH,
          Math.min(1.0, core.length() / BINARYISH_SATURATION_LENGTH));
    }
    return null;
  }

  /**
   * Counts structural signals in a lowercase ASCII token.
   *
   * @param core The lowercase token without surrounding punctuation.
   * @param span The token span.
   * @return The finding, or {@code null}.
   */
  private NoiseSpan structural(String core, Span span) {
    int letters = 0;
    int vowels = 0;
    int consonantRun = 0;
    int maxConsonantRun = 0;
    int repeatRun = 1;
    int maxRepeatRun = 1;
    int interleave = 0;
    boolean lastDigit = false;
    boolean lastLetter = false;
    char last = 0;
    for (int i = 0; i < core.length(); i++) {
      final char c = core.charAt(i);
      final boolean letter = c >= 'a' && c <= 'z';
      final boolean digit = c >= '0' && c <= '9';
      if (letter) {
        letters++;
        if (c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u' || c == 'y') {
          vowels++;
          consonantRun = 0;
        } else {
          consonantRun++;
          maxConsonantRun = Math.max(maxConsonantRun, consonantRun);
        }
      } else {
        consonantRun = 0;
      }
      if ((letter && lastDigit) || (digit && lastLetter)) {
        interleave++;
      }
      repeatRun = c == last ? repeatRun + 1 : 1;
      maxRepeatRun = Math.max(maxRepeatRun, repeatRun);
      last = c;
      lastDigit = digit;
      lastLetter = letter;
    }
    int signals = 0;
    if (letters >= VOWELLESS_MIN_LETTERS && vowels == 0) {
      signals++;
    }
    if (letters >= LOW_VOWEL_MIN_LETTERS && vowels > 0
        && (double) vowels / letters <= LOW_VOWEL_RATIO) {
      signals++;
    }
    if (maxConsonantRun >= CONSONANT_RUN_SIGNAL) {
      signals++;
    }
    if (maxRepeatRun >= REPEAT_RUN_SIGNAL) {
      signals++;
    }
    if (core.length() >= INTERLEAVE_MIN_LENGTH && interleave >= INTERLEAVE_SIGNAL) {
      signals++;
    }
    if (signals >= GIBBERISH_MIN_SIGNALS) {
      return new NoiseSpan(span, NoiseSpan.SEVERITY_GIBBERISH,
          Math.min(1.0, signals / GIBBERISH_SATURATION_SIGNALS));
    }
    if (signals > 0) {
      return new NoiseSpan(span, NoiseSpan.SEVERITY_DAMAGED, DAMAGED_SCORE);
    }
    return null;
  }

  /**
   * Checks whether an OCR substitution produces a dictionary word.
   *
   * @param core The lowercase token without surrounding punctuation.
   * @param span The token span.
   * @return The finding, or {@code null} without a dictionary or a repairing
   *         confusion.
   */
  private NoiseSpan misspelled(String core, Span span) {
    if (dictionary == null) {
      return null;
    }
    final StringBuilder candidate = new StringBuilder(core.length());
    for (final String[] confusion : CONFUSIONS) {
      final String from = confusion[0];
      final String to = confusion[1];
      int at = core.indexOf(from);
      while (at >= 0) {
        candidate.setLength(0);
        candidate.append(core, 0, at).append(to).append(core, at + from.length(), core.length());
        if (dictionary.test(candidate.toString())) {
          return new NoiseSpan(span, NoiseSpan.SEVERITY_MISSPELLED, MISSPELLED_SCORE);
        }
        at = core.indexOf(from, at + 1);
      }
    }
    return null;
  }

  /**
   * Merges results separated by non-excluded whitespace. The highest severity and
   * the maximum score within that severity are used.
   *
   * @param findings The per-token findings in order.
   * @param text The text, to check that only whitespace separates neighbors.
   * @param exclude The excluded regions.
   * @return The merged findings. Never {@code null}.
   */
  private List<NoiseSpan> merge(List<NoiseSpan> findings, CharSequence text,
      Collection<Span> exclude) {
    final List<NoiseSpan> merged = new ArrayList<>();
    for (final NoiseSpan finding : findings) {
      if (!merged.isEmpty()) {
        final NoiseSpan previous = merged.get(merged.size() - 1);
        if (onlyWhitespaceBetween(text, previous.span().getEnd(),
            finding.span().getStart())
            && !overlapsAny(previous.span().getEnd(), finding.span().getStart(), exclude)) {
          final String severity = worse(previous.severity(), finding.severity());
          final double score;
          if (previous.severity().equals(finding.severity())) {
            score = Math.max(previous.score(), finding.score());
          } else if (severity.equals(previous.severity())) {
            score = previous.score();
          } else {
            score = finding.score();
          }
          merged.set(merged.size() - 1, new NoiseSpan(
              new Span(previous.span().getStart(), finding.span().getEnd()),
              severity, score));
          continue;
        }
      }
      merged.add(finding);
    }
    return merged;
  }

  /**
   * Whether a region holds only whitespace.
   *
   * @param text The text.
   * @param from The inclusive start.
   * @param to The exclusive end.
   * @return {@code true} if every character in between is whitespace.
   */
  private boolean onlyWhitespaceBetween(CharSequence text, int from, int to) {
    for (int i = from; i < to; i++) {
      if (!StringUtil.isWhitespace(text.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  /**
   * Picks the worse of two severities in this scorer's tier order.
   *
   * @param a One severity.
   * @param b The other.
   * @return The worse one.
   */
  private String worse(String a, String b) {
    return rank(a) >= rank(b) ? a : b;
  }

  /**
   * Orders this scorer's tiers.
   *
   * @param severity The severity.
   * @return Its rank, higher is worse; {@code 0} for a tier this scorer does not
   *         report.
   */
  private int rank(String severity) {
    return switch (severity) {
      case NoiseSpan.SEVERITY_BINARYISH -> 4;
      case NoiseSpan.SEVERITY_GIBBERISH -> 3;
      case NoiseSpan.SEVERITY_DAMAGED -> 2;
      case NoiseSpan.SEVERITY_MISSPELLED -> 1;
      default -> 0;
    };
  }

  /**
   * Counts the ASCII punctuation a token starts with.
   *
   * @param token The whitespace-delimited token.
   * @return The index of the first character that is not surrounding punctuation, or
   *         the token length when the token is punctuation throughout.
   */
  private int leadingPunctuation(String token) {
    int start = 0;
    while (start < token.length() && isAsciiPunctuation(token.charAt(start))) {
      start++;
    }
    return start;
  }

  /**
   * Strips trailing ASCII punctuation, keeping the word core.
   *
   * @param token The whitespace-delimited token.
   * @param from The index {@link #leadingPunctuation(String)} returned for the token.
   * @return The core, possibly empty.
   */
  private String trimPunctuation(String token, int from) {
    int end = token.length();
    while (end > from && isAsciiPunctuation(token.charAt(end - 1))) {
      end--;
    }
    return token.substring(from, end);
  }

  /**
   * Whether the character is ASCII punctuation that surrounds words, deliberately
   * excluding the base64 alphabet's {@code +}, {@code /}, and {@code =}.
   *
   * @param c The character.
   * @return {@code true} for surrounding punctuation.
   */
  private boolean isAsciiPunctuation(char c) {
    return c == '.' || c == ',' || c == ';' || c == ':' || c == '!' || c == '?'
        || c == '"' || c == '\'' || c == '(' || c == ')' || c == '[' || c == ']'
        || c == '{' || c == '}';
  }

  /**
   * Whether every character is ASCII.
   *
   * @param token The token.
   * @return {@code true} when no character exceeds 0x7F.
   */
  private boolean isAscii(String token) {
    for (int i = 0; i < token.length(); i++) {
      if (token.charAt(i) > 0x7F) {
        return false;
      }
    }
    return true;
  }

  /**
   * Tests whether a region intersects a non-empty excluded span.
   *
   * @param start The token start.
   * @param end The token end.
   * @param exclude The excluded spans.
   * @return {@code true} on any overlap.
   */
  private boolean overlapsAny(int start, int end, Collection<Span> exclude) {
    for (final Span span : exclude) {
      if (span.getStart() < span.getEnd() && start < span.getEnd() && span.getStart() < end) {
        return true;
      }
    }
    return false;
  }
}
