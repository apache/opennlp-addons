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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.DocumentAnnotator;
import opennlp.tools.document.LayerKey;
import opennlp.tools.document.Layers;

/**
 * Scans document text with a {@link PiiExtractor} and writes one {@link #PII} annotation
 * per mention.
 *
 * <p>The annotator uses raw text and requires no input layers. Pass the output layer
 * to {@link Masker} to produce a redacted copy.</p>
 *
 * @since 3.0.0
 */
public final class PiiAnnotator implements DocumentAnnotator {

  /**
   * PII mentions. An annotation and the contained {@link PiiMention} have matching
   * start and end offsets in the document text.
   */
  public static final LayerKey<PiiMention> PII = Layers.key("pii", PiiMention.class);

  private final PiiExtractor extractor;

  /**
   * Initializes the adapter.
   *
   * @param extractor The extractor to delegate to. Must not be {@code null}.
   * @throws IllegalArgumentException Thrown if {@code extractor} is {@code null}.
   */
  public PiiAnnotator(PiiExtractor extractor) {
    if (extractor == null) {
      throw new IllegalArgumentException("extractor must not be null");
    }
    this.extractor = extractor;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Scans the text and adds the PII layer, including an empty layer when no PII is found.</p>
   *
   * @throws IllegalArgumentException Thrown if {@code document} is null, or the extractor
   *         returns a null result, a null mention or a mention outside the document text.
   */
  @Override
  public Document annotate(Document document) {
    if (document == null) {
      throw new IllegalArgumentException("document must not be null");
    }
    final List<Annotation<PiiMention>> mentions = new ArrayList<>();
    for (final PiiMention mention : PiiExtraction.extract(extractor, document.text())) {
      mentions.add(new Annotation<>(mention.span(), mention));
    }
    return document.with(PII, mentions);
  }

  /** {@inheritDoc} */
  @Override
  public Set<LayerKey<?>> provides() {
    return Set.of(PII);
  }
}
