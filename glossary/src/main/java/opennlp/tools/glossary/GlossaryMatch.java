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

package opennlp.tools.glossary;

import opennlp.tools.util.Span;
import opennlp.tools.util.StringUtil;

/**
 * One glossary hit in a text: the {@link Span} it covers in the original text, the
 * identifier of the matched entry, and the glossary term that matched.
 *
 * <p>The term is the registered form. Normalization or case-insensitive matching may
 * associate it with different source characters. Text inserted by an aligned normalizer
 * can produce a zero-length source span.</p>
 *
 * @param span The location of the hit in the original text. Must not be {@code null}.
 * @param id The identifier of the matched {@link GlossaryEntry}. Must not be
 *           {@code null} or blank.
 * @param term The registered term that matched. Must not be {@code null} or blank.
 *
 * @since 3.0.0
 */
public record GlossaryMatch(Span span, String id, String term) {

  /**
   * Validates the hit.
   *
   * @throws IllegalArgumentException Thrown if {@code span} is {@code null}, or
   *         {@code id} or {@code term} is {@code null} or blank.
   */
  public GlossaryMatch {
    if (span == null) {
      throw new IllegalArgumentException("span must not be null");
    }
    if (id == null || StringUtil.isBlank(id)) {
      throw new IllegalArgumentException("id must not be null or blank");
    }
    if (term == null || StringUtil.isBlank(term)) {
      throw new IllegalArgumentException("term must not be null or blank");
    }
  }

}
