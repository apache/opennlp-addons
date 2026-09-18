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

package opennlp.geo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.DocumentAnnotator;
import opennlp.tools.document.LayerKey;
import opennlp.tools.document.Layers;
import opennlp.tools.geo.AttributeValue;
import opennlp.tools.geo.ContainmentChain;
import opennlp.tools.geo.GazetteerEntry;
import opennlp.tools.geo.GeoResolution;
import opennlp.tools.geo.GeocodeAnnotator;
import opennlp.tools.geo.PlaceAncestor;
import opennlp.tools.geo.PlaceHierarchy;
import opennlp.tools.util.Span;
import opennlp.tools.util.StringUtil;

/**
 * Adds a {@link ContainmentChain} to each expandable geocoded mention, preserving
 * the original span. The configured gazetteer attribute supplies the identifier
 * passed to {@link PlaceHierarchy}; the default is the Who's On First attribute.
 *
 * <p>Location annotations are grouped by start and end offsets. The first annotation
 * determines the chain. Span types do not affect this grouping. A missing join attribute or an
 * empty hierarchy result produces no annotation, even if a later candidate is
 * expandable. An empty locations layer produces an empty containment layer.</p>
 *
 * <p>Location annotations must match the resolution's character offsets.
 * Input validation precedes hierarchy lookups. Provider failures propagate to
 * the application without returning a partial document.</p>
 *
 * <p>Concurrent calls are supported when the supplied hierarchy supports them.</p>
 *
 * @since 3.0.0
 */
public class HierarchyAnnotator implements DocumentAnnotator {

  /** Containment chains on their resolved mention spans. */
  public static final LayerKey<ContainmentChain> CONTAINMENT =
      Layers.key("containment", ContainmentChain.class);

  private final PlaceHierarchy hierarchy;
  private final String attributeKey;

  /**
   * Initializes the annotator joining on the Who's On First attribute.
   *
   * @param hierarchy The place hierarchy. Must not be {@code null}.
   * @throws IllegalArgumentException Thrown if {@code hierarchy} is {@code null}.
   */
  public HierarchyAnnotator(PlaceHierarchy hierarchy) {
    this(hierarchy, GazetteerEntry.ATTRIBUTE_KEY_WHOSONFIRST);
  }

  /**
   * Initializes the annotator.
   *
   * @param hierarchy The place hierarchy. Must not be {@code null}.
   * @param attributeKey The attribute containing the hierarchy identifier.
   *                     Must not be {@code null} or blank.
   * @throws IllegalArgumentException Thrown if {@code hierarchy} is {@code null} or
   *         {@code attributeKey} is {@code null} or blank.
   */
  public HierarchyAnnotator(PlaceHierarchy hierarchy, String attributeKey) {
    if (hierarchy == null) {
      throw new IllegalArgumentException("hierarchy must not be null");
    }
    if (attributeKey == null || StringUtil.isBlank(attributeKey)) {
      throw new IllegalArgumentException("attributeKey must not be null or blank");
    }
    this.hierarchy = hierarchy;
    this.attributeKey = attributeKey;
  }

  /**
   * {@inheritDoc}
   *
   * @throws IllegalArgumentException If the containment layer already exists, location
   *         and resolution offsets do not match, or the hierarchy returns a null list,
   *         a null ancestor or the queried place as an ancestor.
   */
  @Override
  public Document annotate(Document document) {
    if (document == null) {
      throw new IllegalArgumentException("document must not be null");
    }
    if (!document.layers().contains(GeocodeAnnotator.LOCATIONS)) {
      throw new IllegalArgumentException("document lacks the required layer "
          + GeocodeAnnotator.LOCATIONS);
    }
    if (document.layers().contains(CONTAINMENT)) {
      throw new IllegalArgumentException("document already contains layer " + CONTAINMENT);
    }
    final List<Annotation<GeoResolution>> locations = document.get(GeocodeAnnotator.LOCATIONS);
    for (final Annotation<GeoResolution> location : locations) {
      if (offsets(location.span()) != offsets(location.value().mention())) {
        throw new IllegalArgumentException("location and resolution offsets must match");
      }
    }
    final List<Annotation<ContainmentChain>> chains = new ArrayList<>();
    final Set<Long> expanded = new HashSet<>();
    for (final Annotation<GeoResolution> location : locations) {
      if (!expanded.add(offsets(location.span()))) {
        continue;
      }
      final AttributeValue joinId =
          location.value().entry().attributes().get(attributeKey);
      if (joinId == null) {
        continue;
      }
      final List<PlaceAncestor> ancestors = hierarchy.ancestors(joinId.value());
      if (ancestors == null) {
        throw new IllegalArgumentException("hierarchy must not return null ancestors");
      }
      if (!ancestors.isEmpty()) {
        final ContainmentChain chain = new ContainmentChain(ancestors);
        for (final PlaceAncestor ancestor : chain.ancestors()) {
          if (joinId.value().equals(ancestor.id())) {
            throw new IllegalArgumentException("ancestors must exclude the queried place: "
                + joinId.value());
          }
        }
        chains.add(new Annotation<>(location.span(), chain));
      }
    }
    return document.with(CONTAINMENT, chains);
  }

  /**
   * Combines the start and end offsets without the span type.
   *
   * @param span The mention span.
   * @return The combined offsets.
   */
  private long offsets(Span span) {
    return ((long) span.getStart() << 32) | span.getEnd();
  }

  /** {@inheritDoc} */
  @Override
  public Set<LayerKey<?>> requires() {
    return Set.of(GeocodeAnnotator.LOCATIONS);
  }

  /** {@inheritDoc} */
  @Override
  public Set<LayerKey<?>> provides() {
    return Set.of(CONTAINMENT);
  }
}
