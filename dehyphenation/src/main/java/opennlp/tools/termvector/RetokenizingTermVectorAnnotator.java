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

package opennlp.tools.termvector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.DocumentAnnotator;
import opennlp.tools.document.LayerKey;
import opennlp.tools.tokenize.Tokenizer;
import opennlp.tools.util.Span;
import opennlp.tools.util.normalizer.AlignedText;
import opennlp.tools.util.normalizer.OffsetAwareNormalizer;

/**
 * Builds term vectors by tokenizing normalized document text.
 *
 * <p>Use this annotator when normalization changes token boundaries, for example
 * when dehyphenation joins {@code "litiga-\ntion"} into {@code "litigation"}.
 * {@link TermVectorAnnotator} instead uses an existing token layer.</p>
 *
 * <p>Terms are grouped by normalized text in tokenizer output order. Empty token
 * spans are omitted. In {@link TermVectorAnnotator.Mode#FULL full mode},
 * occurrence spans map back to original-text offsets through the normalizer's
 * alignment. A joined word's span includes the deleted hyphen and line break.</p>
 *
 * <p>No input layers are required. Existing layers and document text are preserved.
 * Thread safety depends on the supplied normalizer and tokenizer.</p>
 *
 * @since 3.0.0
 */
public class RetokenizingTermVectorAnnotator implements DocumentAnnotator {

  private final OffsetAwareNormalizer normalizer;
  private final Tokenizer tokenizer;
  private final TermVectorAnnotator.Mode mode;

  /**
   * Initializes a {@link TermVectorAnnotator.Mode#FULL full mode} annotator.
   *
   * @param normalizer The normalizer that defines term identity, applied to the whole
   *                   document text with its alignment recorded. Must not be {@code null}.
   * @param tokenizer The tokenizer applied to the normalized text. Must not be
   *                  {@code null}.
   * @throws IllegalArgumentException Thrown if {@code normalizer} or {@code tokenizer} is
   *         {@code null}.
   */
  public RetokenizingTermVectorAnnotator(OffsetAwareNormalizer normalizer, Tokenizer tokenizer) {
    this(normalizer, tokenizer, TermVectorAnnotator.Mode.FULL);
  }

  /**
   * Initializes an annotator.
   *
   * @param normalizer The normalizer that defines term identity, applied to the whole
   *                   document text with its alignment recorded. Must not be {@code null}.
   * @param tokenizer The tokenizer applied to the normalized text. Must not be
   *                  {@code null}.
   * @param mode How much each {@link TermVector} records. Must not be {@code null}.
   * @throws IllegalArgumentException Thrown if any argument is {@code null}.
   */
  public RetokenizingTermVectorAnnotator(OffsetAwareNormalizer normalizer, Tokenizer tokenizer,
      TermVectorAnnotator.Mode mode) {
    if (normalizer == null) {
      throw new IllegalArgumentException("normalizer must not be null");
    }
    if (tokenizer == null) {
      throw new IllegalArgumentException("tokenizer must not be null");
    }
    if (mode == null) {
      throw new IllegalArgumentException("mode must not be null");
    }
    this.normalizer = normalizer;
    this.tokenizer = tokenizer;
    this.mode = mode;
  }

  /**
   * Normalizes and tokenizes the text, then adds {@link TermVectorAnnotator#TERM_VECTORS}.
   * Empty token spans are omitted in both modes. If no terms remain, the layer is empty.
   *
   * @param document The document to annotate. Must not be {@code null}.
   * @return A new {@link Document} with the {@link TermVectorAnnotator#TERM_VECTORS} layer
   *         added. Never {@code null}.
   * @throws IllegalArgumentException Thrown if {@code document} is {@code null} or already
   *         carries the {@link TermVectorAnnotator#TERM_VECTORS} layer.
   */
  @Override
  public Document annotate(Document document) {
    if (document == null) {
      throw new IllegalArgumentException("document must not be null");
    }
    final AlignedText aligned = normalizer.normalizeAligned(document.text());
    final String normalized = aligned.normalizedString();
    final Span[] tokens = tokenizer.tokenizePos(normalized);
    return document.with(TermVectorAnnotator.TERM_VECTORS,
        mode == TermVectorAnnotator.Mode.FULL
            ? fullVectors(aligned, normalized, tokens)
            : countVectors(normalized, tokens));
  }

  /** {@inheritDoc} */
  @Override
  public Set<LayerKey<?>> requires() {
    return Set.of();
  }

  /** {@inheritDoc} */
  @Override
  public Set<LayerKey<?>> provides() {
    return Set.of(TermVectorAnnotator.TERM_VECTORS);
  }

  /**
   * Aggregates the normalized token spans into full term vectors, mapping each occurrence
   * span back to the original text through the alignment.
   *
   * @param aligned The normalized document text with its alignment.
   * @param normalized The normalized document text.
   * @param tokens The token spans in the normalized text.
   * @return One annotation per distinct term, in first-occurrence order.
   */
  private List<Annotation<TermVector>> fullVectors(AlignedText aligned, String normalized,
      Span[] tokens) {
    final Map<String, List<Span>> spansByTerm = new LinkedHashMap<>();
    for (final Span token : tokens) {
      final String term = termOf(normalized, token);
      if (!term.isEmpty()) {
        spansByTerm.computeIfAbsent(term, key -> new ArrayList<>())
            .add(aligned.toOriginalSpan(token.getStart(), token.getEnd()));
      }
    }
    final List<Annotation<TermVector>> vectors = new ArrayList<>(spansByTerm.size());
    for (final Map.Entry<String, List<Span>> entry : spansByTerm.entrySet()) {
      vectors.add(Annotation.of(TermVector.withSpans(entry.getKey(), entry.getValue())));
    }
    return vectors;
  }

  /**
   * Counts non-empty terms without storing occurrence offsets.
   *
   * @param normalized The normalized document text.
   * @param tokens The token spans in the normalized text.
   * @return One annotation per distinct term, in first-occurrence order.
   */
  private List<Annotation<TermVector>> countVectors(String normalized, Span[] tokens) {
    final Map<String, Integer> frequencies = new LinkedHashMap<>();
    for (final Span token : tokens) {
      final String term = termOf(normalized, token);
      if (!term.isEmpty()) {
        frequencies.merge(term, 1, Integer::sum);
      }
    }
    final List<Annotation<TermVector>> vectors = new ArrayList<>(frequencies.size());
    for (final Map.Entry<String, Integer> entry : frequencies.entrySet()) {
      vectors.add(Annotation.of(TermVector.count(entry.getKey(), entry.getValue())));
    }
    return vectors;
  }

  /**
   * Extracts the normalized text covered by a token.
   *
   * @param normalized The normalized document text.
   * @param token The token span in the normalized text.
   * @return The term string, possibly empty.
   */
  private String termOf(String normalized, Span token) {
    return normalized.substring(token.getStart(), token.getEnd());
  }
}
