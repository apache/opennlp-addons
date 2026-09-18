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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.DocumentAnnotator;
import opennlp.tools.document.LayerKey;
import opennlp.tools.document.Layers;

/**
 * Adds glossary matches to a document as the {@link #GLOSSARY} annotation layer.
 *
 * <p>The matcher receives the document text; no input annotation layers are required.
 * An existing glossary layer is rejected before matching begins.</p>
 *
 * @since 3.0.0
 */
public final class GlossaryAnnotator implements DocumentAnnotator {

  /**
   * Glossary hits; each annotation covers one hit and carries its {@link GlossaryMatch}.
   */
  public static final LayerKey<GlossaryMatch> GLOSSARY =
      Layers.key("glossary", GlossaryMatch.class);

  /** The matcher that produces the hits this annotator records as a layer. */
  private final GlossaryMatcher matcher;

  /**
   * Initializes the adapter.
   *
   * @param matcher The matcher to delegate to. Must not be {@code null}.
   * @throws IllegalArgumentException Thrown if {@code matcher} is {@code null}.
   */
  public GlossaryAnnotator(GlossaryMatcher matcher) {
    if (matcher == null) {
      throw new IllegalArgumentException("matcher must not be null");
    }
    this.matcher = matcher;
  }

  /**
   * {@inheritDoc} Adds the {@link #GLOSSARY} layer, including when no matches are found.
   *
   * @throws IllegalArgumentException Thrown if the document already contains
   *         {@link #GLOSSARY}.
   */
  @Override
  public Document annotate(Document document) {
    if (document == null) {
      throw new IllegalArgumentException("document must not be null");
    }
    if (document.layers().contains(GLOSSARY)) {
      throw new IllegalArgumentException("layer is already present: " + GLOSSARY);
    }
    final List<Annotation<GlossaryMatch>> hits = new ArrayList<>();
    for (final GlossaryMatch match : matcher.match(document.text())) {
      hits.add(new Annotation<>(match.span(), match));
    }
    return document.with(GLOSSARY, hits);
  }

  /** {@inheritDoc} */
  @Override
  public Set<LayerKey<?>> provides() {
    return Set.of(GLOSSARY);
  }
}
