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

package opennlp.tools.embeddings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.DocumentAnnotator;
import opennlp.tools.document.LayerKey;
import opennlp.tools.util.Span;

/**
 * Embeds the covered text of each source annotation through a {@link TextEmbedder}
 * and adds a layer of {@code float[]} values on the same original-text spans.
 *
 * <p>The provided layer's identifier is the source layer's identifier prefixed with
 * {@code "embeddings:"}, for example {@code embeddings:opennlp:tokens} for a source
 * layer with the identifier {@code opennlp:tokens}. Token and sentence embeddings can
 * coexist under distinct keys. Two embedders over the same source produce the same key;
 * they cannot both add their output to one document. Read the output through
 * {@link #layer()}.</p>
 *
 * <p>The embedder receives the covered text of each source annotation, taken from
 * {@link Document#text()} by the annotation's span; the annotation's stored value is
 * never consulted. The source layer must be positional and present. An empty source
 * layer produces an empty vector layer without calling the embedder.</p>
 *
 * <p>For each nonempty source layer, one {@link TextEmbedder#embedAll(List)} call embeds
 * distinct covered texts in first-appearance order. Equal texts share the returned
 * vector. The batch must contain one non-null vector per distinct text, each of length
 * {@link TextEmbedder#dimension()}. No resizing, pooling, normalization, or component
 * filtering is applied.</p>
 *
 * <p>Arrays are stored without copying. Callers and embedders must not modify them after
 * annotation. The annotator keeps no per-document state and supports concurrent use
 * when the embedder does.</p>
 *
 * @since 3.0.0
 */
public class EmbeddingAnnotator implements DocumentAnnotator {

  private final TextEmbedder embedder;
  private final LayerKey<String> source;
  private final LayerKey<float[]> layer;

  /**
   * Initializes the annotator and derives the provided layer from the source layer by
   * prefixing the source layer's identifier with {@code "embeddings:"}.
   *
   * @param embedder The embedder to delegate to. Must not be {@code null}.
   * @param source The layer whose annotations are embedded, for example the token or
   *               sentence layer. Must not be {@code null} and must be
   *               {@link LayerKey.Scope#POSITIONAL positional}, because every annotation
   *               is embedded by its span.
   * @throws IllegalArgumentException Thrown if a parameter is {@code null} or if
   *         {@code source} is not positional.
   */
  public EmbeddingAnnotator(TextEmbedder embedder, LayerKey<String> source) {
    if (embedder == null) {
      throw new IllegalArgumentException("embedder must not be null");
    }
    if (source == null) {
      throw new IllegalArgumentException("source must not be null");
    }
    if (source.scope() != LayerKey.Scope.POSITIONAL) {
      throw new IllegalArgumentException("source must be a positional layer: " + source);
    }
    this.embedder = embedder;
    this.source = source;
    this.layer = LayerKey.of("embeddings:" + source.id(), float[].class);
  }

  /**
   * @return The layer this instance provides: one vector per source annotation, on the
   *         source annotation's span. The layer's identifier is the source layer's
   *         identifier prefixed with {@code "embeddings:"}. Never {@code null}.
   */
  public LayerKey<float[]> layer() {
    return layer;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Adds the vectors under {@link #layer()} after validating the embedding batch.</p>
   *
   * @throws IllegalArgumentException Thrown if the output layer already exists or the
   *         embedder returns a null batch, an incorrect number of vectors, a null vector,
   *         or a vector whose length differs from its declared dimension.
   */
  @Override
  public Document annotate(Document document) {
    if (document == null) {
      throw new IllegalArgumentException("document must not be null");
    }
    if (!document.layers().contains(source)) {
      throw new IllegalArgumentException("document lacks the required layer " + source);
    }
    if (document.layers().contains(layer)) {
      throw new IllegalArgumentException("layer already present: " + layer);
    }
    final CharSequence text = document.text();
    final List<Annotation<String>> annotations = document.get(source);
    if (annotations.isEmpty()) {
      return document.with(layer, List.of());
    }
    // Map source annotations to the first occurrence of their covered text.
    final Map<String, Integer> distinctIndex = new HashMap<>();
    final List<String> distinct = new ArrayList<>();
    final int[] route = new int[annotations.size()];
    for (int i = 0; i < annotations.size(); i++) {
      final Span span = annotations.get(i).span();
      final String covered = text.subSequence(span.getStart(), span.getEnd()).toString();
      final Integer seen = distinctIndex.putIfAbsent(covered, distinct.size());
      if (seen == null) {
        distinct.add(covered);
        route[i] = distinct.size() - 1;
      } else {
        route[i] = seen;
      }
    }
    final float[][] embedded = embedder.embedAll(distinct);
    if (embedded == null) {
      throw new IllegalArgumentException("embedder returned a null batch");
    }
    if (embedded.length != distinct.size()) {
      throw new IllegalArgumentException("embedder returned " + embedded.length
          + " vectors for " + distinct.size() + " texts; embedAll must return one vector "
          + "per input, in input order");
    }
    final int dimension = embedder.dimension();
    for (int i = 0; i < embedded.length; i++) {
      if (embedded[i] == null) {
        throw new IllegalArgumentException("embedder returned a null vector at batch index " + i);
      }
      if (embedded[i].length != dimension) {
        throw new IllegalArgumentException("embedder returned a vector of dimension "
            + embedded[i].length + " at batch index " + i + "; expected " + dimension);
      }
    }
    final List<Annotation<float[]>> vectors = new ArrayList<>(annotations.size());
    for (int i = 0; i < annotations.size(); i++) {
      vectors.add(new Annotation<>(annotations.get(i).span(), embedded[route[i]]));
    }
    return document.with(layer, vectors);
  }

  /**
   * {@inheritDoc}
   *
   * <p>The single-element set holding the source layer given at construction.</p>
   */
  @Override
  public Set<LayerKey<?>> requires() {
    return Set.of(source);
  }

  /**
   * {@inheritDoc}
   *
   * <p>The single-element set holding {@link #layer()}.</p>
   */
  @Override
  public Set<LayerKey<?>> provides() {
    return Set.of(layer);
  }
}
