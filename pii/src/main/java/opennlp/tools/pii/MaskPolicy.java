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

/**
 * Configures how {@link Masker} replaces characters within a span.
 * {@link #of(char)} masks the complete span. {@link #keepingFormat()} preserves
 * non-alphanumeric characters, and {@link #keepingTrailing(int)} preserves trailing
 * letters or digits.
 *
 * <p>Masking preserves UTF-16 length: a supplementary code point uses 2 mask characters.
 * Other layer offsets remain valid for the masked text.</p>
 *
 * <p>Instances are immutable and safe to share between threads.</p>
 *
 * @since 3.0.0
 */
public final class MaskPolicy {

  private final char mask;
  private final boolean keepFormat;
  private final int keepTrailing;

  /**
   * Initializes a policy with validated options.
   *
   * @param mask The replacement character.
   * @param keepFormat Whether non-alphanumeric characters remain visible.
   * @param keepTrailing The trailing letter or digit count to retain.
   */
  private MaskPolicy(char mask, boolean keepFormat, int keepTrailing) {
    this.mask = mask;
    this.keepFormat = keepFormat;
    this.keepTrailing = keepTrailing;
  }

  /**
   * Creates a policy that masks the complete span.
   *
   * @param mask The replacement character. Must not be a surrogate.
   * @return The non-null policy.
   * @throws IllegalArgumentException Thrown if {@code mask} is a surrogate character.
   */
  public static MaskPolicy of(char mask) {
    if (Character.isSurrogate(mask)) {
      throw new IllegalArgumentException("mask must not be a surrogate character");
    }
    return new MaskPolicy(mask, false, 0);
  }

  /**
   * Returns a policy that masks only Unicode letters and digits. Spaces, punctuation,
   * symbols and other non-alphanumeric characters remain visible.
   *
   * @return A new non-null policy; this instance is unchanged.
   */
  public MaskPolicy keepingFormat() {
    return new MaskPolicy(mask, true, keepTrailing);
  }

  /**
   * Returns a policy that preserves the final {@code count} letters or digits of each
   * span. The remaining positions follow the other policy options. Counting uses
   * complete code points.
   *
   * @param count The number of trailing letters or digits to keep. Must not be
   *              negative. A span with fewer letters or digits remains readable in
   *              those positions.
   * @return A new non-null policy; this instance is unchanged.
   * @throws IllegalArgumentException Thrown if {@code count} is negative.
   */
  public MaskPolicy keepingTrailing(int count) {
    if (count < 0) {
      throw new IllegalArgumentException("count must not be negative");
    }
    return new MaskPolicy(mask, keepFormat, count);
  }

  /**
   * Applies the policy to one span's text.
   *
   * @param spanText The original text covered by the span.
   * @return The replacement, always the same length as {@code spanText} in UTF-16
   *         units.
   */
  String apply(String spanText) {
    final StringBuilder out = new StringBuilder(spanText);
    apply(spanText, out, 0);
    return out.toString();
  }

  /**
   * Masks positions selected from an original span without restoring prior redactions.
   *
   * @param spanText The original text covered by the span.
   * @param out The document text with any earlier redactions applied.
   * @param offset The span's start in {@code out}.
   */
  void apply(String spanText, StringBuilder out, int offset) {
    int alphanumeric = 0;
    for (int i = 0; i < spanText.length(); ) {
      final int cp = spanText.codePointAt(i);
      if (Character.isLetterOrDigit(cp)) {
        alphanumeric++;
      }
      i += Character.charCount(cp);
    }
    final int firstKept = alphanumeric - keepTrailing;
    int seen = 0;
    for (int i = 0; i < spanText.length(); ) {
      final int cp = spanText.codePointAt(i);
      final int units = Character.charCount(cp);
      final boolean redact = Character.isLetterOrDigit(cp) ? seen++ < firstKept : !keepFormat;
      if (redact) {
        out.setCharAt(offset + i, mask);
        if (units == 2) {
          out.setCharAt(offset + i + 1, mask);
        }
      }
      i += units;
    }
  }
}
