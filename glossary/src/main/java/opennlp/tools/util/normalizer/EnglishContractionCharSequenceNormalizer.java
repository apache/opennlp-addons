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

package opennlp.tools.util.normalizer;

import java.io.Serial;
import java.util.Arrays;

import opennlp.tools.tokenize.uax29.WordSegmenter;
import opennlp.tools.util.StringUtil;

/**
 * Expands unambiguous English contractions into whitespace-separated words while
 * retaining an alignment to the source text. For example, {@code can't} becomes
 * {@code can not}, and {@code we're} becomes {@code we are}. ASCII, typographic,
 * modifier-letter, and fullwidth apostrophes are accepted.
 *
 * <p>A contraction must occupy a complete word under the default
 * <a href="https://www.unicode.org/reports/tr29/#Word_Boundaries">UAX #29 word
 * boundaries</a>. Quotation marks around a word are preserved. Portions of longer
 * words, identifiers, and apostrophe-linked forms such as {@code can't've} are not
 * expanded.</p>
 *
 * <p>Ambiguous {@code 's}, {@code 'd}, and {@code ain't} forms remain unchanged.
 * This English-specific step is not included in the default normalization chain.</p>
 *
 * <p>With {@link #normalizeAligned(CharSequence)}, a span covering all expanded words
 * maps back to the complete original contraction, including its apostrophe.</p>
 *
 * @since 3.0.0
 */
public final class EnglishContractionCharSequenceNormalizer implements OffsetAwareNormalizer {

  @Serial
  private static final long serialVersionUID = 5698236849033803551L;

  /** Replacement suffix for an unambiguous negative contraction. */
  private static final String NOT_SUFFIX = " not";

  /** Validation message shared by both normalization entry points. */
  private static final String TEXT_NULL_MESSAGE = "text must not be null";

  /** Auxiliary stems that form an unambiguous negative contraction with {@code n't}. */
  private static final String[] NEGATIVE_STEMS = {
      "are", "could", "dare", "did", "do", "does", "had", "has", "have", "is", "may",
      "might", "must", "need", "ought", "should", "was", "were", "would"
  };

  /** Subjects whose {@code 're} suffix unambiguously expands to {@code are}. */
  private static final String[] ARE_SUBJECTS = {
      "how", "there", "they", "we", "what", "when", "where", "who", "why", "you"
  };

  /** Subjects and auxiliaries whose {@code 've} suffix expands to {@code have}. */
  private static final String[] HAVE_SUBJECTS = {
      "could", "i", "might", "must", "should", "they", "we", "who", "would", "you"
  };

  /** Subjects whose {@code 'll} suffix unambiguously expands to {@code will}. */
  private static final String[] WILL_SUBJECTS = {
      "he", "how", "i", "it", "she", "that", "there", "they", "we", "what", "when",
      "where", "who", "why", "you"
  };

  /** The shared stateless instance. */
  private static final EnglishContractionCharSequenceNormalizer INSTANCE =
      new EnglishContractionCharSequenceNormalizer();

  /** Prevents external instantiation; use {@link #getInstance()}. */
  private EnglishContractionCharSequenceNormalizer() {
  }

  /** {@return the shared, stateless instance} */
  public static EnglishContractionCharSequenceNormalizer getInstance() {
    return INSTANCE;
  }

  /** {@inheritDoc} */
  @Override
  public CharSequence normalize(CharSequence text) {
    if (text == null) {
      throw new IllegalArgumentException(TEXT_NULL_MESSAGE);
    }
    final Contraction first = nextContraction(text, 0);
    if (first == null) {
      return text;
    }
    final int[] boundaries = WordSegmenter.boundaries(text);
    StringBuilder normalized = null;
    int cursor = 0;
    Contraction contraction = first;
    while (contraction != null) {
      if (isCompleteWord(contraction, boundaries)) {
        if (normalized == null) {
          normalized = new StringBuilder(text.length() + 8);
        }
        normalized.append(text, cursor, contraction.start());
        appendContraction(normalized, text, contraction);
        cursor = contraction.end();
      }
      contraction = nextContraction(text, contraction.end());
    }
    return normalized == null ? text : normalized.append(text, cursor, text.length()).toString();
  }

  /** {@inheritDoc} */
  @Override
  public AlignedText normalizeAligned(CharSequence text) {
    if (text == null) {
      throw new IllegalArgumentException(TEXT_NULL_MESSAGE);
    }
    final String original = text.toString();
    final StringBuilder normalized = new StringBuilder(original.length() + 8);
    final Alignment.Builder alignment = new Alignment.Builder(original.length());
    int cursor = 0;
    Contraction contraction = nextContraction(original, cursor);
    final int[] boundaries = contraction == null ? null : WordSegmenter.boundaries(original);
    while (contraction != null) {
      if (isCompleteWord(contraction, boundaries)) {
        appendEqual(normalized, alignment, original, cursor, contraction.start());
        appendContraction(normalized, alignment, original, contraction);
        cursor = contraction.end();
      }
      contraction = nextContraction(original, contraction.end());
    }
    appendEqual(normalized, alignment, original, cursor, original.length());
    return new AlignedText(original, normalized.toString(), alignment.build(original.length()));
  }

  /**
   * Checks the candidate's start and end against the source word boundaries.
   *
   * @param contraction The candidate expansion.
   * @param boundaries The sorted source word boundaries.
   * @return Whether the candidate covers a complete word.
   */
  private boolean isCompleteWord(Contraction contraction, int[] boundaries) {
    return Arrays.binarySearch(boundaries, contraction.start()) >= 0
        && Arrays.binarySearch(boundaries, contraction.end()) >= 0;
  }

  /**
   * Finds the next supported contraction at or after an offset.
   *
   * @param text The text to inspect.
   * @param from The inclusive search offset.
   * @return The next contraction, or {@code null} when none remains.
   */
  private Contraction nextContraction(CharSequence text, int from) {
    for (int apostrophe = from; apostrophe < text.length(); apostrophe++) {
      if (isApostrophe(text.charAt(apostrophe))) {
        final Contraction contraction = contractionAt(text, apostrophe);
        if (contraction != null) {
          return contraction;
        }
      }
    }
    return null;
  }

  /**
   * Resolves a contraction whose apostrophe is at a known offset.
   *
   * @param text The source text.
   * @param apostrophe The apostrophe offset.
   * @return The supported contraction, or {@code null} when the form is ambiguous or unknown.
   */
  private Contraction contractionAt(CharSequence text, int apostrophe) {
    final int start = asciiWordStart(text, apostrophe);
    final int end = asciiWordEnd(text, apostrophe + 1);
    if (start == apostrophe || end == apostrophe + 1) {
      return null;
    }
    final boolean upper = isAllUpperAscii(text, start, end);
    if (matchesAsciiIgnoreCase(text, start, end, "can't")) {
      return new Contraction(start, apostrophe, end, null, caseReplacement(NOT_SUFFIX, upper));
    }
    if (matchesAsciiIgnoreCase(text, start, end, "won't")) {
      return new Contraction(start, apostrophe - 1, end,
          caseWord("will", text, start, apostrophe - 1), caseReplacement(NOT_SUFFIX, upper));
    }
    if (matchesAsciiIgnoreCase(text, start, end, "shan't")) {
      return new Contraction(start, apostrophe - 1, end,
          caseWord("shall", text, start, apostrophe - 1), caseReplacement(NOT_SUFFIX, upper));
    }
    if (matchesAsciiIgnoreCase(text, start, end, "let's")) {
      return new Contraction(start, apostrophe, end, null, caseReplacement(" us", upper));
    }
    if (apostrophe > start && asciiEqualsIgnoreCase(text.charAt(apostrophe - 1), 'n')
        && matchesAsciiIgnoreCase(text, apostrophe + 1, end, "t")
        && containsAsciiIgnoreCase(NEGATIVE_STEMS, text, start, apostrophe - 1)) {
      return new Contraction(start, apostrophe - 1, end, null, caseReplacement(NOT_SUFFIX, upper));
    }
    if (matchesAsciiIgnoreCase(text, apostrophe + 1, end, "re")
        && containsAsciiIgnoreCase(ARE_SUBJECTS, text, start, apostrophe)) {
      return new Contraction(start, apostrophe, end, null, caseReplacement(" are", upper));
    }
    if (matchesAsciiIgnoreCase(text, apostrophe + 1, end, "ve")
        && containsAsciiIgnoreCase(HAVE_SUBJECTS, text, start, apostrophe)) {
      return new Contraction(start, apostrophe, end, null, caseReplacement(" have", upper));
    }
    if (matchesAsciiIgnoreCase(text, apostrophe + 1, end, "ll")
        && containsAsciiIgnoreCase(WILL_SUBJECTS, text, start, apostrophe)) {
      return new Contraction(start, apostrophe, end, null, caseReplacement(" will", upper));
    }
    if (matchesAsciiIgnoreCase(text, start, apostrophe, "i")
        && matchesAsciiIgnoreCase(text, apostrophe + 1, end, "m")) {
      return new Contraction(start, apostrophe, end, null, caseReplacement(" am", upper));
    }
    return null;
  }

  /**
   * Appends a contraction expansion without building an alignment.
   *
   * @param output The normalized output.
   * @param text The source text.
   * @param contraction The contraction to expand.
   */
  private void appendContraction(StringBuilder output, CharSequence text,
      Contraction contraction) {
    if (contraction.prefixReplacement() == null) {
      output.append(text, contraction.start(), contraction.prefixEnd());
    } else {
      output.append(contraction.prefixReplacement());
    }
    output.append(contraction.suffixReplacement());
  }

  /**
   * Appends a contraction expansion and records its source alignment.
   *
   * @param output The normalized output.
   * @param alignment The receiving alignment builder.
   * @param text The source text.
   * @param contraction The contraction to expand.
   */
  private void appendContraction(StringBuilder output, Alignment.Builder alignment,
      CharSequence text, Contraction contraction) {
    final int prefixLength = contraction.prefixEnd() - contraction.start();
    if (contraction.prefixReplacement() == null) {
      output.append(text, contraction.start(), contraction.prefixEnd());
      alignment.equal(prefixLength);
    } else {
      output.append(contraction.prefixReplacement());
      alignment.replace(prefixLength, contraction.prefixReplacement().length());
    }
    output.append(contraction.suffixReplacement());
    alignment.replace(contraction.end() - contraction.prefixEnd(),
        contraction.suffixReplacement().length());
  }

  /**
   * Copies an unchanged range into the output and alignment.
   *
   * @param output The normalized output.
   * @param alignment The receiving alignment builder.
   * @param text The source text.
   * @param start The inclusive source offset.
   * @param end The exclusive source offset.
   */
  private void appendEqual(StringBuilder output, Alignment.Builder alignment,
      CharSequence text, int start, int end) {
    output.append(text, start, end);
    alignment.equal(end - start);
  }

  /**
   * Finds the start of the ASCII-letter run before an apostrophe.
   *
   * @param text The source text.
   * @param from The apostrophe offset.
   * @return The inclusive word start.
   */
  private int asciiWordStart(CharSequence text, int from) {
    int start = from;
    while (start > 0 && isAsciiLetter(text.charAt(start - 1))) {
      start--;
    }
    return start;
  }

  /**
   * Finds the end of the ASCII-letter run after an apostrophe.
   *
   * @param text The source text.
   * @param from The first offset after the apostrophe.
   * @return The exclusive word end.
   */
  private int asciiWordEnd(CharSequence text, int from) {
    int end = from;
    while (end < text.length() && isAsciiLetter(text.charAt(end))) {
      end++;
    }
    return end;
  }

  /**
   * Checks whether a character is an accepted apostrophe form.
   *
   * @param value The character to inspect.
   * @return {@code true} for an ASCII, typographic, modifier-letter, or fullwidth apostrophe.
   */
  private boolean isApostrophe(char value) {
    return value == '\'' || value == '\u2019' || value == '\u02BC' || value == '\uFF07';
  }

  /**
   * Checks whether a character is an ASCII letter.
   *
   * @param value The character to inspect.
   * @return {@code true} for {@code A-Z} or {@code a-z}.
   */
  private boolean isAsciiLetter(char value) {
    return value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z';
  }

  /**
   * Checks whether all ASCII letters in a word are uppercase.
   *
   * @param text The source text.
   * @param start The inclusive word start.
   * @param end The exclusive word end.
   * @return {@code true} when every letter is uppercase.
   */
  private boolean isAllUpperAscii(CharSequence text, int start, int end) {
    for (int i = start; i < end; i++) {
      final char value = text.charAt(i);
      if (value >= 'a' && value <= 'z') {
        return false;
      }
    }
    return true;
  }

  /**
   * Applies all-uppercase casing to an inserted suffix when the source word is uppercase.
   *
   * @param lower The lowercase replacement.
   * @param upper Whether the source word is uppercase.
   * @return The replacement in the source word's casing.
   */
  private String caseReplacement(String lower, boolean upper) {
    return upper ? StringUtil.toUpperCase(lower) : lower;
  }

  /**
   * Applies lowercase, title-case, or uppercase styling to an irregular replacement stem.
   *
   * @param lower The lowercase replacement stem.
   * @param text The source text.
   * @param start The inclusive source stem start.
   * @param end The exclusive source stem end.
   * @return The replacement styled like the source stem.
   */
  private String caseWord(String lower, CharSequence text, int start, int end) {
    if (isAllUpperAscii(text, start, end)) {
      return StringUtil.toUpperCase(lower);
    }
    if (text.charAt(start) >= 'A' && text.charAt(start) <= 'Z') {
      return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
    return lower;
  }

  /**
   * Finds a case-insensitive ASCII slice in a sorted allowlist without allocating a copy.
   *
   * @param values The sorted lowercase allowlist.
   * @param text The source text.
   * @param start The inclusive slice start.
   * @param end The exclusive slice end.
   * @return {@code true} when the slice equals an allowlisted value.
   */
  private boolean containsAsciiIgnoreCase(String[] values, CharSequence text, int start, int end) {
    int low = 0;
    int high = values.length - 1;
    while (low <= high) {
      final int middle = (low + high) >>> 1;
      final int comparison = compareAsciiIgnoreCase(text, start, end, values[middle]);
      if (comparison < 0) {
        high = middle - 1;
      } else if (comparison > 0) {
        low = middle + 1;
      } else {
        return true;
      }
    }
    return false;
  }

  /**
   * Compares an ASCII slice with a lowercase value without allocating.
   *
   * @param text The source text.
   * @param start The inclusive slice start.
   * @param end The exclusive slice end.
   * @param lower The lowercase comparison value.
   * @return A negative, zero, or positive value under lexicographic ordering.
   */
  private int compareAsciiIgnoreCase(CharSequence text, int start, int end, String lower) {
    final int length = end - start;
    final int common = Math.min(length, lower.length());
    for (int i = 0; i < common; i++) {
      final char left = asciiLowerCase(text.charAt(start + i));
      final char right = lower.charAt(i);
      if (left != right) {
        return left - right;
      }
    }
    return length - lower.length();
  }

  /**
   * Checks a source slice against an ASCII value without allocating.
   *
   * @param text The source text.
   * @param start The inclusive slice start.
   * @param end The exclusive slice end.
   * @param expected The expected ASCII value.
   * @return {@code true} when the values match without regard to ASCII case.
   */
  private boolean matchesAsciiIgnoreCase(CharSequence text, int start, int end, String expected) {
    if (end - start != expected.length()) {
      return false;
    }
    for (int i = 0; i < expected.length(); i++) {
      final char source = text.charAt(start + i);
      final char target = expected.charAt(i);
      if (isApostrophe(target)) {
        if (!isApostrophe(source)) {
          return false;
        }
      } else if (!asciiEqualsIgnoreCase(source, target)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Compares two ASCII letters without regard to case.
   *
   * @param left The first character.
   * @param right The second character.
   * @return {@code true} when the characters are ASCII-case-equivalent.
   */
  private boolean asciiEqualsIgnoreCase(char left, char right) {
    return asciiLowerCase(left) == asciiLowerCase(right);
  }

  /**
   * Lowercases one ASCII letter without locale-sensitive mapping.
   *
   * @param value The character to fold.
   * @return The lowercase ASCII value, or the original non-uppercase character.
   */
  private char asciiLowerCase(char value) {
    return value >= 'A' && value <= 'Z' ? (char) (value + ('a' - 'A')) : value;
  }

  /** {@return the shared instance after Java deserialization} */
  @Serial
  private Object readResolve() {
    return INSTANCE;
  }

  /** One supported contraction and its two alignment-preserving replacement blocks. */
  private record Contraction(int start, int prefixEnd, int end, String prefixReplacement,
                             String suffixReplacement) {
  }
}
