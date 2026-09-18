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

package opennlp.tools.money;

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
 * Extracts monetary mentions from the original document text into {@link #MONEY}.
 *
 * <p>No input layers are required. Results must be non-null, in text order,
 * non-overlapping, and within the text bounds. Values and spans are retained without
 * normalization or reordering.</p>
 *
 * @since 3.0.0
 */
public class MoneyAnnotator implements DocumentAnnotator {

  /**
   * Monetary mentions; each annotation covers one mention and carries the normalized
   * {@link MoneyAmount}.
   */
  public static final LayerKey<MoneyAmount> MONEY = Layers.key("money-amounts", MoneyAmount.class);

  private final MoneyExtractor extractor;

  /**
   * Initializes the adapter.
   *
   * @param extractor The extractor to delegate to. Must not be {@code null}.
   * @throws IllegalArgumentException Thrown if {@code extractor} is {@code null}.
   */
  public MoneyAnnotator(MoneyExtractor extractor) {
    if (extractor == null) {
      throw new IllegalArgumentException("extractor must not be null");
    }
    this.extractor = extractor;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Adds the validated extraction results under {@link #MONEY}.</p>
   *
   * @throws IllegalArgumentException Thrown if the output layer is present or the
   *         extractor returns a null result, null mention, or invalid span sequence.
   */
  @Override
  public Document annotate(Document document) {
    if (document == null) {
      throw new IllegalArgumentException("document must not be null");
    }
    if (document.layers().contains(MONEY)) {
      throw new IllegalArgumentException("layer is already present: " + MONEY);
    }
    final CharSequence text = document.text();
    final int textLength = text.length();
    final List<MoneyAmount> extracted = extractor.extract(text);
    if (extracted == null) {
      throw new IllegalArgumentException("extractor returned a null result");
    }
    final List<Annotation<MoneyAmount>> mentions = new ArrayList<>();
    int previousEnd = 0;
    for (final MoneyAmount amount : extracted) {
      if (amount == null) {
        throw new IllegalArgumentException("extractor returned a null mention");
      }
      final Span span = amount.span();
      if (span.getEnd() > textLength) {
        throw new IllegalArgumentException("extractor returned a span beyond the text: " + span);
      }
      if (span.getStart() < previousEnd) {
        throw new IllegalArgumentException("extractor returned an unordered or overlapping span: " + span);
      }
      previousEnd = span.getEnd();
      mentions.add(new Annotation<>(span, amount));
    }
    return document.with(MONEY, mentions);
  }

  /** {@inheritDoc} */
  @Override
  public Set<LayerKey<?>> provides() {
    return Set.of(MONEY);
  }
}
