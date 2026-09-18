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
import java.util.List;
import java.util.Set;

import opennlp.tools.assets.AssetAnnotator;
import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.DocumentAnnotator;
import opennlp.tools.document.DocumentAnnotators;
import opennlp.tools.document.LayerKey;
import opennlp.tools.document.Layers;
import opennlp.tools.util.Span;

/**
 * Adds {@link NoiseScorer} results to a document's {@link #NOISE} layer.
 *
 * <p>In its default mode this annotator requires {@link AssetAnnotator#ASSETS} and
 * excludes those spans from scoring. The layer may be empty, but must be present.
 * Standalone mode does not require or exclude asset annotations.</p>
 *
 * @since 3.0.0
 */
public class NoiseAnnotator implements DocumentAnnotator {

  /**
   * Noise annotations with original-text offsets and {@link NoiseSpan} values.
   */
  public static final LayerKey<NoiseSpan> NOISE = Layers.key("noise", NoiseSpan.class);

  private final NoiseScorer scorer;
  private final boolean excludeAssets;

  /**
   * Initializes the adapter in the default mode: the built-in
   * {@link StructuralNoiseScorer} without a dictionary, excluding detected assets.
   */
  public NoiseAnnotator() {
    this(new StructuralNoiseScorer(), true);
  }

  /**
   * Initializes the adapter.
   *
   * @param scorer The scorer to delegate to. Must not be {@code null}.
   * @param excludeAssets {@code true} to require {@link AssetAnnotator#ASSETS} and
   *                      exclude those spans from scoring; {@code false} to run
   *                      standalone over the whole text.
   * @throws IllegalArgumentException Thrown if {@code scorer} is {@code null}.
   */
  public NoiseAnnotator(NoiseScorer scorer, boolean excludeAssets) {
    if (scorer == null) {
      throw new IllegalArgumentException("scorer must not be null");
    }
    this.scorer = scorer;
    this.excludeAssets = excludeAssets;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Document annotate(Document document) {
    if (document == null) {
      throw new IllegalArgumentException("document must not be null");
    }
    final List<Span> exclude = new ArrayList<>();
    if (excludeAssets) {
      DocumentAnnotators.requireLayers(document, AssetAnnotator.ASSETS);
      for (final Annotation<?> asset : document.get(AssetAnnotator.ASSETS)) {
        exclude.add(asset.span());
      }
    }
    final List<Annotation<NoiseSpan>> found = new ArrayList<>();
    for (final NoiseSpan noise : scorer.score(document.text(), exclude)) {
      found.add(new Annotation<>(noise.span(), noise));
    }
    return document.with(NOISE, found);
  }

  /**
   * {@inheritDoc}
   *
   * <p>The default mode requires {@link AssetAnnotator#ASSETS}; the standalone mode
   * requires nothing.</p>
   */
  @Override
  public Set<LayerKey<?>> requires() {
    return excludeAssets ? Set.of(AssetAnnotator.ASSETS) : Set.of();
  }

  /** {@inheritDoc} */
  @Override
  public Set<LayerKey<?>> provides() {
    return Set.of(NOISE);
  }
}
