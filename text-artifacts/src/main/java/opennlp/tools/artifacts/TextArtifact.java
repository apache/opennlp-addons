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

package opennlp.tools.artifacts;

import opennlp.tools.util.Span;
import opennlp.tools.util.StringUtil;

/**
 * One damaged or suspicious character sequence in a text: the {@link Span} it covers in
 * the original text and its artifact type.
 *
 * <p>The type is an open string: a detector may report a type beyond the constants
 * declared here. The span always refers to the text as the caller supplied it, never to
 * a normalized or repaired form.</p>
 *
 * @param span The location of the artifact in the original text. Must not be
 *             {@code null}.
 * @param type The artifact type, for example {@link #TYPE_MOJIBAKE}. Must not be
 *             {@code null} or blank.
 *
 * @since 3.0.0
 */
public record TextArtifact(Span span, String type) {

  /** A run of U+FFFD replacement characters. */
  public static final String TYPE_REPLACEMENT = "replacement";

  /** A run of C0 or C1 control characters that are not whitespace. */
  public static final String TYPE_CONTROL = "control";

  /** A run of Unicode noncharacters, code points reserved for internal use. */
  public static final String TYPE_NONCHARACTER = "noncharacter";

  /** A run of unpaired UTF-16 surrogates, impossible in well-formed text. */
  public static final String TYPE_UNPAIRED_SURROGATE = "unpaired-surrogate";

  /** A run of Unicode private-use code points. */
  public static final String TYPE_PRIVATE_USE = "private-use";

  /**
   * A run of explicit bidirectional control characters, which can visually reorder
   * text away from its logical order.
   */
  public static final String TYPE_BIDI_CONTROL = "bidi-control";

  /**
   * Zero-width characters reported by a detector's contextual rules.
   */
  public static final String TYPE_ZERO_WIDTH = "zero-width";

  /**
   * A run from the
   * <a href="https://www.unicode.org/charts/PDF/UE0000.pdf">Unicode Tags block</a>
   * reported by a detector's contextual rules.
   */
  public static final String TYPE_UNICODE_TAG = "unicode-tag";

  /**
   * A possible encoding error from reading UTF-8 bytes through a single-byte decoding.
   */
  public static final String TYPE_MOJIBAKE = "mojibake";

  /**
   * Validates the artifact.
   *
   * @throws IllegalArgumentException Thrown if {@code span} is {@code null}, or
   *         {@code type} is {@code null} or blank.
   */
  public TextArtifact {
    if (span == null) {
      throw new IllegalArgumentException("span must not be null");
    }
    if (type == null || StringUtil.isBlank(type)) {
      throw new IllegalArgumentException("type must not be null or blank");
    }
  }
}
