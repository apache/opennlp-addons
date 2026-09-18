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

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.geo.AttributeValue;
import opennlp.tools.geo.ContainmentChain;
import opennlp.tools.geo.GazetteerEntry;
import opennlp.tools.geo.GeoPoint;
import opennlp.tools.geo.GeoResolution;
import opennlp.tools.geo.GeocodeAnnotator;
import opennlp.tools.geo.PlaceAncestor;
import opennlp.tools.util.Span;

/**
 * Tests containment chains from pre-built location layers.
 */
public class HierarchyAnnotatorTest {

  /**
   * Creates a place record with an optional join identifier.
   *
   * @param name The place name.
   * @param wofId The join identifier, or null to omit the attribute.
   * @return The place record.
   */
  private GazetteerEntry entry(String name, String wofId) {
    final Map<String, AttributeValue> attributes = wofId == null ? Map.of()
        : Map.of(GazetteerEntry.ATTRIBUTE_KEY_WHOSONFIRST,
            new AttributeValue(wofId, "test", "fixture"));
    return new GazetteerEntry("test", name, name, List.of(), new GeoPoint(0.0, 0.0),
        "US", List.of(), 1000, GazetteerEntry.FEATURE_CLASS_CITY, attributes);
  }

  /** @return The neighbourhood, borough and city used by the tests. */
  private ContainmentSpine spine() {
    return ContainmentSpine.builder()
        .add("85865587", "421205765", "Park Slope", "neighbourhood")
        .add("421205765", "85977539", "Brooklyn", "borough")
        .add("85977539", null, "New York", "locality")
        .build();
  }

  /** A resolved mention receives parent places on the original span. */
  @Test
  void testResolvedMentionsExpandIntoTheirChains() {
    final String text = "A stroll through Park Slope.";
    final Span mention = new Span(17, 27);
    final Document document = Document.of(text)
        .with(GeocodeAnnotator.LOCATIONS, List.of(new Annotation<>(mention,
            new GeoResolution(mention, entry("Park Slope", "85865587"), 0.9))));

    final Document annotated = new HierarchyAnnotator(spine()).annotate(document);

    final List<Annotation<ContainmentChain>> chains =
        annotated.get(HierarchyAnnotator.CONTAINMENT);
    Assertions.assertEquals(1, chains.size());
    Assertions.assertEquals(mention, chains.get(0).span());
    Assertions.assertEquals(List.of("Brooklyn", "New York"),
        chains.get(0).value().ancestors().stream().map(PlaceAncestor::name).toList());
  }

  /** Missing attributes and unknown identifiers produce no chain. */
  @Test
  void testMentionsWithoutJoinIdOrChainAreOmitted() {
    final String text = "Atlantis and Brooklyn";
    final Document document = Document.of(text)
        .with(GeocodeAnnotator.LOCATIONS, List.of(
            new Annotation<>(new Span(0, 8),
                new GeoResolution(new Span(0, 8), entry("Atlantis", null), 0.5)),
            new Annotation<>(new Span(13, 21),
                new GeoResolution(new Span(13, 21), entry("Brooklyn", "77"), 0.5))));

    final Document annotated = new HierarchyAnnotator(spine()).annotate(document);

    Assertions.assertTrue(annotated.get(HierarchyAnnotator.CONTAINMENT).isEmpty());
  }

  /** A root place produces a present but empty containment layer. */
  @Test
  void testRootPlaceMentionGetsNoChain() {
    final String text = "New York in one line";
    final Span mention = new Span(0, 8);
    final Document document = Document.of(text)
        .with(GeocodeAnnotator.LOCATIONS, List.of(new Annotation<>(mention,
            new GeoResolution(mention, entry("New York", "85977539"), 0.9))));

    final Document annotated = new HierarchyAnnotator(spine()).annotate(document);

    Assertions.assertTrue(annotated.layers().contains(HierarchyAnnotator.CONTAINMENT));
    Assertions.assertTrue(annotated.get(HierarchyAnnotator.CONTAINMENT).isEmpty());
  }

  /** Repeated mentions receive equal chains on distinct spans. */
  @Test
  void testTwoMentionsOfSamePlaceGetTwoIdenticalChains() {
    final String text = "From Park Slope to Park Slope.";
    final Span first = new Span(5, 15);
    final Span second = new Span(19, 29);
    final Document document = Document.of(text)
        .with(GeocodeAnnotator.LOCATIONS, List.of(
            new Annotation<>(first,
                new GeoResolution(first, entry("Park Slope", "85865587"), 0.9)),
            new Annotation<>(second,
                new GeoResolution(second, entry("Park Slope", "85865587"), 0.9))));

    final Document annotated = new HierarchyAnnotator(spine()).annotate(document);

    final List<Annotation<ContainmentChain>> chains =
        annotated.get(HierarchyAnnotator.CONTAINMENT);
    Assertions.assertEquals(2, chains.size());
    Assertions.assertEquals(first, chains.get(0).span());
    Assertions.assertEquals(second, chains.get(1).span());
    Assertions.assertEquals(chains.get(0).value(), chains.get(1).value());
    Assertions.assertEquals(new ContainmentChain(List.of(
        new PlaceAncestor("421205765", "Brooklyn", "borough"),
        new PlaceAncestor("85977539", "New York", "locality"))),
        chains.get(0).value());
  }

  /** The first candidate determines the chain for a mention. */
  @Test
  void testMultiCandidateMentionGetsOneChainFromTheBestCandidate() {
    final String text = "A week in Paris.";
    final Span mention = new Span(10, 15);
    final ContainmentSpine spine = ContainmentSpine.builder()
        .add("101", "102", "Paris", "locality")
        .add("102", null, "France", "country")
        .add("201", "202", "Paris", "locality")
        .add("202", null, "United States", "country")
        .build();
    final Document document = Document.of(text)
        .with(GeocodeAnnotator.LOCATIONS, List.of(
            new Annotation<>(mention,
                new GeoResolution(mention, entry("Paris", "101"), 0.7)),
            new Annotation<>(mention,
                new GeoResolution(mention, entry("Paris", "201"), 0.3))));

    final Document annotated = new HierarchyAnnotator(spine).annotate(document);

    final List<Annotation<ContainmentChain>> chains =
        annotated.get(HierarchyAnnotator.CONTAINMENT);
    Assertions.assertEquals(1, chains.size());
    Assertions.assertEquals(mention, chains.get(0).span());
    Assertions.assertEquals(new ContainmentChain(List.of(
        new PlaceAncestor("102", "France", "country"))),
        chains.get(0).value());
  }

  /** A missing attribute on the first candidate prevents expansion of later candidates. */
  @Test
  void testUnexpandableBestCandidateSuppressesTheMentionsChain() {
    final String text = "A week in Paris.";
    final Span mention = new Span(10, 15);
    final Document document = Document.of(text)
        .with(GeocodeAnnotator.LOCATIONS, List.of(
            new Annotation<>(mention,
                new GeoResolution(mention, entry("Paris", null), 0.7)),
            new Annotation<>(mention,
                new GeoResolution(mention, entry("Paris", "85865587"), 0.3))));

    final Document annotated = new HierarchyAnnotator(spine()).annotate(document);

    Assertions.assertTrue(annotated.get(HierarchyAnnotator.CONTAINMENT).isEmpty());
  }

  /** Null hierarchy and document arguments are rejected. */
  @Test
  void testNullHierarchyAndNullDocumentAreRejected() {
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new HierarchyAnnotator(null));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new HierarchyAnnotator(spine()).annotate(null));
  }

  /**
   * Blank keys, including no-break spaces, are rejected.
   *
   * @param attributeKey The invalid key.
   */
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t", "\u00A0"})
  void testBlankAttributeKeyIsRejected(String attributeKey) {
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new HierarchyAnnotator(spine(), attributeKey));
  }

  /** A custom join key overrides the default key. */
  @Test
  void testCustomAttributeKeyJoinsOnThatAttribute() {
    final String text = "A stroll through Park Slope.";
    final Span mention = new Span(17, 27);
    final Map<String, AttributeValue> attributes = Map.of(
        GazetteerEntry.ATTRIBUTE_KEY_WHOSONFIRST, new AttributeValue("77", "test", "fixture"),
        GazetteerEntry.ATTRIBUTE_KEY_GEONAMES, new AttributeValue("85865587", "test", "fixture"));
    final GazetteerEntry entry = new GazetteerEntry("test", "Park Slope", "Park Slope",
        List.of(), new GeoPoint(0.0, 0.0), "US", List.of(), 1000,
        GazetteerEntry.FEATURE_CLASS_CITY, attributes);
    final Document document = Document.of(text)
        .with(GeocodeAnnotator.LOCATIONS, List.of(new Annotation<>(mention,
            new GeoResolution(mention, entry, 0.9))));

    final Document annotated =
        new HierarchyAnnotator(spine(), GazetteerEntry.ATTRIBUTE_KEY_GEONAMES).annotate(document);

    final List<Annotation<ContainmentChain>> chains =
        annotated.get(HierarchyAnnotator.CONTAINMENT);
    Assertions.assertEquals(1, chains.size());
    Assertions.assertEquals(List.of("Brooklyn", "New York"),
        chains.get(0).value().ancestors().stream().map(PlaceAncestor::name).toList());
    Assertions.assertTrue(new HierarchyAnnotator(spine())
        .annotate(document).get(HierarchyAnnotator.CONTAINMENT).isEmpty());
  }

  /** The error names the missing input layer. */
  @Test
  void testAbsentLocationsLayerThrowsWithExactMessage() {
    final IllegalArgumentException e = Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> new HierarchyAnnotator(spine()).annotate(Document.of("bare")));
    Assertions.assertEquals(
        "document lacks the required layer opennlp:locations<GeoResolution>", e.getMessage());
  }

  /** Typed and untyped spans with matching offsets identify one mention. */
  @Test
  void testTypedAndUntypedSpansOverSameOffsetsAreOneMention() {
    final String text = "A stroll through Park Slope.";
    final Span typed = new Span(17, 27, "location");
    final Span untyped = new Span(17, 27);
    final Document document = Document.of(text)
        .with(GeocodeAnnotator.LOCATIONS, List.of(
            new Annotation<>(typed,
                new GeoResolution(typed, entry("Park Slope", "85865587"), 0.9)),
            new Annotation<>(untyped,
                new GeoResolution(untyped, entry("Park Slope", "421205765"), 0.4))));

    final Document annotated = new HierarchyAnnotator(spine()).annotate(document);

    final List<Annotation<ContainmentChain>> chains =
        annotated.get(HierarchyAnnotator.CONTAINMENT);
    Assertions.assertEquals(1, chains.size());
    Assertions.assertEquals(List.of("Brooklyn", "New York"),
        chains.get(0).value().ancestors().stream().map(PlaceAncestor::name).toList());
  }
}
