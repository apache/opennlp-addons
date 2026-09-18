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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import opennlp.tools.document.Document;

/**
 * Replaces PII mentions with numbered labels such as {@code EMAIL-1} and {@code EMAIL-2}.
 *
 * <p>Mentions with the same type and {@link PiiMention#normalized() normalized form}
 * share a label within one rewrite. Types are case-sensitive. Labels uppercase ASCII
 * letters in the type, so custom types such as {@code id} and {@code ID} share a sequence
 * of numbers but receive distinct labels.</p>
 *
 * <p>Numbering restarts for each rewrite. Matching labels in separate documents do not
 * establish a shared identity. Use {@link HmacTokenizer} for cross-document tokens.</p>
 *
 * <p>Labels can change text length. {@link PiiRewrite} maps annotations to output
 * offsets.</p>
 *
 * <p>Text replacement rejects overlapping mentions. Use {@link Masker} to redact
 * their combined spans.</p>
 *
 * <p>Instances are immutable and safe to share between threads: the counters that number
 * the labels live for the duration of one {@code rewrite} call.</p>
 *
 * @since 3.0.0
 */
public final class Pseudonymizer {

  /**
   * Identifies mentions that share a label.
   *
   * @param type The case-sensitive mention type.
   * @param normalized The normalized value.
   */
  private record Identity(String type, String normalized) {
  }

  private final String prefix;
  private final String suffix;

  /**
   * Initializes a pseudonymizer producing labels such as {@code EMAIL-1}.
   */
  public Pseudonymizer() {
    this("", "");
  }

  /**
   * Initializes a pseudonymizer with a prefix and suffix around each label.
   *
   * @param prefix Placed before each label. Must not be {@code null}; may be empty.
   * @param suffix Placed after each label. Must not be {@code null}; may be empty.
   * @throws IllegalArgumentException Thrown if {@code prefix} or {@code suffix} is
   *         {@code null}.
   */
  public Pseudonymizer(String prefix, String suffix) {
    if (prefix == null || suffix == null) {
      throw new IllegalArgumentException("prefix and suffix must not be null");
    }
    this.prefix = prefix;
    this.suffix = suffix;
  }

  /**
   * Replaces each mention with a numbered label.
   *
   * @param text The original text. Must not be {@code null}.
   * @param mentions The mentions to replace, as reported by a {@link PiiExtractor}. Must
   *                 not be {@code null} or contain {@code null}. All spans must be
   *                 within {@code text} and must not overlap.
   * @return The non-null rewrite result.
   * @throws IllegalArgumentException Thrown if an argument is {@code null}, a mention is
   *         {@code null}, a span lies outside the text, or spans overlap.
   */
  public PiiRewrite rewrite(CharSequence text, List<PiiMention> mentions) {
    final Map<Identity, String> labels = new HashMap<>();
    final Map<String, Integer> counters = new HashMap<>();
    return PiiRewrite.replace(text, mentions,
        mention -> label(mention, labels, counters));
  }

  /**
   * Replaces the mentions from a document's {@link PiiAnnotator#PII} layer.
   *
   * @param document The document to rewrite. Must be non-null and have a
   *                 {@link PiiAnnotator#PII} layer with matching annotation and mention
   *                 offsets.
   * @return The non-null rewrite result.
   * @throws IllegalArgumentException Thrown if {@code document} is null, lacks the PII
   *         layer, contains overlapping mentions, or a mention and annotation have
   *         different offsets.
   */
  public PiiRewrite rewrite(Document document) {
    final List<PiiMention> mentions = PiiLayer.mentions(document);
    return rewrite(document.text(), mentions);
  }

  /**
   * Returns the stable per-text label for one normalized mention.
   *
   * @param mention The mention being replaced.
   * @param labels Labels already assigned by type and normalized value.
   * @param counters The sequence numbers by displayed type prefix.
   * @return The existing or newly assigned label.
   */
  private String label(PiiMention mention, Map<Identity, String> labels,
      Map<String, Integer> counters) {
    final Identity key = new Identity(mention.type(), mention.normalized());
    return labels.computeIfAbsent(key, ignored -> {
      final String typePrefix = Ascii.toUpper(mention.type());
      final int number = counters.merge(typePrefix, 1, Integer::sum);
      return prefix + typePrefix + '-' + number + suffix;
    });
  }
}
