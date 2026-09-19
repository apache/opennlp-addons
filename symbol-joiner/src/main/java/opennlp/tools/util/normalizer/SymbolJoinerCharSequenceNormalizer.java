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

/**
 * Replaces a supported single-symbol input with a fixed English word.
 *
 * <p>Mappings are {@code &} to {@code and}, {@code +} to {@code plus}, {@code @}
 * to {@code at}, {@code %} to {@code percent}, {@code §} to {@code section},
 * {@code ¶} to {@code paragraph}, {@code °} to {@code degree}, {@code ©} to
 * {@code copyright}, {@code ®} to {@code registered}, and {@code ™} to
 * {@code trademark}. These are matching conventions, not context-dependent
 * readings of the symbols. Currency symbols are not mapped.</p>
 *
 * <p>The complete input must be one supported symbol. This normalizer does not
 * tokenize or trim: {@code R&D}, {@code TSR®}, {@code (TM)}, and {@code " & "}
 * are unchanged. For a phrase such as {@code Dungeons & Dragons}, tokenize it
 * separately and normalize each token, preserving symbol tokens.</p>
 *
 * <p>Unchanged input is returned as the supplied object. Matching does not convert
 * the input to a String. The shared instance is stateless and thread-safe.</p>
 *
 * @since 3.0.0
 */
public class SymbolJoinerCharSequenceNormalizer implements CharSequenceNormalizer {

  @Serial
  private static final long serialVersionUID = -6772513786580257420L;

  /** Shared stateless normalizer. */
  private static final SymbolJoinerCharSequenceNormalizer INSTANCE =
      new SymbolJoinerCharSequenceNormalizer();

  /**
   * Returns the shared normalizer.
   *
   * @return The stateless instance.
   */
  public static SymbolJoinerCharSequenceNormalizer getInstance() {
    return INSTANCE;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public CharSequence normalize(CharSequence text) {
    if (text == null) {
      throw new IllegalArgumentException("text must not be null");
    }
    if (text.length() != 1) {
      return text;
    }
    return switch (text.charAt(0)) {
      case '&' -> "and";
      case '+' -> "plus";
      case '@' -> "at";
      case '%' -> "percent";
      case '§' -> "section";
      case '¶' -> "paragraph";
      case '°' -> "degree";
      case '©' -> "copyright";
      case '®' -> "registered";
      case '™' -> "trademark";
      default -> text;
    };
  }

  /**
   * Restores the shared instance after deserialization.
   *
   * @return The shared normalizer.
   */
  @Serial
  private Object readResolve() {
    return INSTANCE;
  }
}
