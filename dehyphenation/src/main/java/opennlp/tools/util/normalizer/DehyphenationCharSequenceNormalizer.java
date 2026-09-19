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

import opennlp.tools.util.StringUtil;

/**
 * Joins words split by a hyphen and a line break, such as
 * {@code "litiga-\ntion"} to {@code "litigation"}.
 *
 * <p>The hyphen must have a letter immediately before it and a forced line break
 * immediately after it. Horizontal whitespace may precede the next letter.
 * Supported hyphens are {@code U+002D}, {@code U+00AD} and {@code U+2010}.
 * {@code U+2011 NON-BREAKING HYPHEN} is excluded.</p>
 *
 * <p>Breaks follow {@link UnicodeWhitespace#lineBreaks()}; CRLF counts as one.
 * Additional line breaks prevent a join. Indentation follows
 * {@link StringUtil#isUnicodeWhitespace(char)}, excluding line breaks.
 * Letter checks support supplementary code points. Offsets use UTF-16 code units.</p>
 *
 * <p>No dictionary is used: {@code "well-\nknown"} becomes {@code "wellknown"}.
 * Text without a matching break is returned without copying. Aligned normalization
 * maps joined-word spans back to the original text, including the deleted break.
 * Use {@link opennlp.tools.termvector.RetokenizingTermVectorAnnotator} to build
 * term vectors from the joined words.</p>
 *
 * @since 3.0.0
 */
public class DehyphenationCharSequenceNormalizer implements OffsetAwareNormalizer {

  @Serial
  private static final long serialVersionUID = 3659585484250150798L;

  private static final String NULL_TEXT_MESSAGE = "The text must not be null.";

  private static final char HYPHEN_MINUS = '-';
  private static final char SOFT_HYPHEN = '\u00AD';

  /** U+2010 HYPHEN. */
  private static final char TYPESET_HYPHEN = '\u2010';

  private static final CodePointSet LINE_BREAKS = UnicodeWhitespace.lineBreakCodePointSet();

  private static final DehyphenationCharSequenceNormalizer INSTANCE =
      new DehyphenationCharSequenceNormalizer();

  /** {@return the shared, stateless instance} */
  public static DehyphenationCharSequenceNormalizer getInstance() {
    return INSTANCE;
  }

  /** {@return the shared instance after deserialization} */
  @Serial
  private Object readResolve() {
    return INSTANCE;
  }

  /**
   * {@inheritDoc}
   *
   * Returns {@code text} without copying when no join is needed.
   */
  @Override
  public CharSequence normalize(CharSequence text) {
    if (text == null) {
      throw new IllegalArgumentException(NULL_TEXT_MESSAGE);
    }
    final int length = text.length();
    StringBuilder out = null;
    int cursor = 0;
    int i = 0;
    while (i < length) {
      final int joinEnd = joinEditEnd(text, i);
      if (joinEnd >= 0) {
        if (out == null) {
          out = new StringBuilder(length);
        }
        out.append(text, cursor, i);
        cursor = i = joinEnd;
      } else {
        i++;
      }
    }
    return out == null ? text : out.append(text, cursor, length).toString();
  }

  /**
   * {@inheritDoc}
   *
   * Joined-word spans map back to the original text, including the deleted break.
   */
  @Override
  public AlignedText normalizeAligned(CharSequence text) {
    if (text == null) {
      throw new IllegalArgumentException(NULL_TEXT_MESSAGE);
    }
    final int length = text.length();
    final StringBuilder out = new StringBuilder(length);
    final Alignment.Builder alignment = new Alignment.Builder(length);
    int cursor = 0;
    int i = 0;
    while (i < length) {
      final int joinEnd = joinEditEnd(text, i);
      if (joinEnd >= 0) {
        out.append(text, cursor, i);
        alignment.equal(i - cursor);
        alignment.replace(joinEnd - i, 0);
        cursor = i = joinEnd;
      } else {
        i++;
      }
    }
    out.append(text, cursor, length);
    alignment.equal(length - cursor);
    return new AlignedText(text, out.toString(), alignment.build(length));
  }

  /**
   * Checks for a hyphenation break between letters.
   *
   * @param text The input text.
   * @param hyphen The candidate hyphen offset.
   * @return The offset after the deletion, or {@code -1} if no join is possible.
   */
  private int joinEditEnd(CharSequence text, int hyphen) {
    final char c = text.charAt(hyphen);
    if (c != HYPHEN_MINUS && c != SOFT_HYPHEN && c != TYPESET_HYPHEN) {
      return -1;
    }
    if (hyphen == 0 || !Character.isLetter(Character.codePointBefore(text, hyphen))) {
      return -1;
    }
    final int length = text.length();
    int end = hyphen + 1;
    if (end >= length) {
      return -1;
    }
    final char breakStart = text.charAt(end);
    if (!isLineBreak(breakStart)) {
      return -1;
    }
    end++;
    if (breakStart == '\r' && end < length && text.charAt(end) == '\n') {
      end++;  // CRLF is one break, not two
    }
    while (end < length && isHorizontalWhitespace(text.charAt(end))) {
      end++;
    }
    if (end >= length || !Character.isLetter(Character.codePointAt(text, end))) {
      return -1;
    }
    return end;
  }

  /**
   * Checks for a forced line break.
   *
   * @param c The character to check.
   * @return Whether the character is in {@link #LINE_BREAKS}.
   */
  private boolean isLineBreak(char c) {
    return LINE_BREAKS.contains(c);
  }

  /**
   * Checks for indentation characters.
   *
   * @param c The character to check.
   * @return Whether the character is Unicode whitespace but not a forced line break.
   */
  private boolean isHorizontalWhitespace(char c) {
    return StringUtil.isUnicodeWhitespace(c) && !isLineBreak(c);
  }
}
