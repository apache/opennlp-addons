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

package opennlp.tools.quantity;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.DocumentAnnotator;
import opennlp.tools.document.LayerKey;
import opennlp.tools.document.Layers;
import opennlp.tools.util.Span;

/**
 * Extracts quantities from the original document text into {@link #QUANTITIES}.
 *
 * <p>No input layers are required. Results must be non-null, in text order,
 * non-overlapping, and within the text bounds. Values and spans are retained without
 * normalization or reordering.</p>
 *
 * @since 3.0.0
 */
public class QuantityAnnotator implements DocumentAnnotator {

  /**
   * Quantity mentions; each annotation covers one mention and carries its
   * {@link Quantity}.
   */
  public static final LayerKey<Quantity> QUANTITIES =
      Layers.key("quantities", Quantity.class);

  private final QuantityExtractor extractor;

  /**
   * Initializes the adapter.
   *
   * @param extractor The extractor to delegate to. Must not be {@code null}.
   * @throws IllegalArgumentException Thrown if {@code extractor} is {@code null}.
   */
  public QuantityAnnotator(QuantityExtractor extractor) {
    if (extractor == null) {
      throw new IllegalArgumentException("extractor must not be null");
    }
    this.extractor = extractor;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Adds the validated extraction results under {@link #QUANTITIES}.</p>
   *
   * @throws IllegalArgumentException Thrown if the output layer is present or the
   *         extractor returns a null result, null mention, or invalid span sequence.
   */
  @Override
  public Document annotate(Document document) {
    if (document == null) {
      throw new IllegalArgumentException("document must not be null");
    }
    if (document.layers().contains(QUANTITIES)) {
      throw new IllegalArgumentException("layer is already present: " + QUANTITIES);
    }
    final CharSequence text = document.text();
    final int textLength = text.length();
    final List<Quantity> extracted = extractor.extract(text);
    if (extracted == null) {
      throw new IllegalArgumentException("extractor returned a null result");
    }
    final List<Annotation<Quantity>> mentions = new ArrayList<>();
    int previousEnd = 0;
    for (final Quantity quantity : extracted) {
      if (quantity == null) {
        throw new IllegalArgumentException("extractor returned a null mention");
      }
      final Span span = quantity.span();
      if (span.getEnd() > textLength) {
        throw new IllegalArgumentException("extractor returned a span beyond the text: " + span);
      }
      if (span.getStart() < previousEnd) {
        throw new IllegalArgumentException("extractor returned an unordered or overlapping span: " + span);
      }
      previousEnd = span.getEnd();
      mentions.add(new Annotation<>(span, quantity));
    }
    return document.with(QUANTITIES, mentions);
  }

  /** {@inheritDoc} */
  @Override
  public Set<LayerKey<?>> provides() {
    return Set.of(QUANTITIES);
  }
}
