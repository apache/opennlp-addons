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
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import opennlp.tools.dictionary.Dictionary;
import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.DocumentAnalyzer;
import opennlp.tools.document.Layers;
import opennlp.tools.geo.AttributeValue;
import opennlp.tools.geo.ContainmentChain;
import opennlp.tools.geo.GazetteerEntry;
import opennlp.tools.geo.GeoPoint;
import opennlp.tools.geo.GeoResolution;
import opennlp.tools.geo.GeocodeAnnotator;
import opennlp.tools.geo.Geocoder;
import opennlp.tools.geo.PlaceAncestor;
import opennlp.tools.namefind.DictionaryNameFinder;
import opennlp.tools.namefind.NameFinderAnnotator;
import opennlp.tools.sentdetect.NewlineSentenceDetector;
import opennlp.tools.sentdetect.SentenceDetectorAnnotator;
import opennlp.tools.tokenize.SimpleTokenizer;
import opennlp.tools.tokenize.TokenizerAnnotator;
import opennlp.tools.util.Span;
import opennlp.tools.util.StringList;

/**
 * Tests containment pipelines with in-memory place records and hierarchy links.
 * No model or external data file is required.
 */
public class HierarchyPipelineExampleTest {

  /**
   * Builds the example hierarchy from neighbourhood to country.
   *
   * @return The spine over the four example places. Never {@code null}.
   */
  private ContainmentSpine exampleSpine() {
    return ContainmentSpine.builder()
        .add("101", "102", "Le Marais", "neighbourhood")
        .add("102", "103", "Paris", "locality")
        .add("103", "104", "Ile-de-France", "region")
        .add("104", null, "France", "country")
        .build();
  }

  /**
   * Creates an example entry with an optional Who's On First join identifier.
   *
   * @param name The place name. Must not be {@code null} or empty.
   * @param wofId The Who's On First identifier, or {@code null} for an entry without
   *              the join attribute.
   * @return The entry. Never {@code null}.
   */
  private GazetteerEntry entry(String name, String wofId) {
    final Map<String, AttributeValue> attributes = wofId == null ? Map.of()
        : Map.of(GazetteerEntry.ATTRIBUTE_KEY_WHOSONFIRST,
            new AttributeValue(wofId, "test", "fixture"));
    return new GazetteerEntry("example", name, name, List.of(),
        new GeoPoint(48.859, 2.361), "FR", List.of(), 0,
        GazetteerEntry.FEATURE_CLASS_CITY, attributes);
  }

  /**
   * A resolved mention gets the parent places in order on the original span.
   */
  @Test
  void testMentionExpandsIntoItsExactContainmentChain() {
    final String text = "We wandered through Le Marais all afternoon.";
    final Span mention = new Span(20, 29);
    Assertions.assertEquals("Le Marais", mention.getCoveredText(text).toString());

    final Geocoder geocoder = new PopulationPriorGeocoder(
        InMemoryGazetteer.fromEntries(List.of(entry("Le Marais", "101"))));
    final Document document = Document.of(text)
        .with(Layers.ENTITIES, List.of(new Annotation<>(mention, "location")));

    final Document annotated = new HierarchyAnnotator(exampleSpine())
        .annotate(new GeocodeAnnotator(geocoder).annotate(document));

    Assertions.assertEquals(1, annotated.get(GeocodeAnnotator.LOCATIONS).size());

    final List<Annotation<ContainmentChain>> chains =
        annotated.get(HierarchyAnnotator.CONTAINMENT);
    Assertions.assertEquals(1, chains.size());
    Assertions.assertEquals(mention, chains.get(0).span());
    Assertions.assertEquals(List.of(
        new PlaceAncestor("102", "Paris", "locality"),
        new PlaceAncestor("103", "Ile-de-France", "region"),
        new PlaceAncestor("104", "France", "country")),
        chains.get(0).value().ancestors());
    for (final PlaceAncestor ancestor : chains.get(0).value().ancestors()) {
      Assertions.assertNotEquals("101", ancestor.id());
      Assertions.assertNotEquals("Le Marais", ancestor.name());
    }
  }

  /**
   * An unresolved entity has no location or containment annotation.
   */
  @Test
  void testUnresolvedMentionsGetNoChain() {
    final String text = "From Narnia to Le Marais.";
    final Span unresolvable = new Span(5, 11);
    final Span resolvable = new Span(15, 24);
    Assertions.assertEquals("Narnia", unresolvable.getCoveredText(text).toString());
    Assertions.assertEquals("Le Marais", resolvable.getCoveredText(text).toString());

    final Geocoder geocoder = new PopulationPriorGeocoder(
        InMemoryGazetteer.fromEntries(List.of(entry("Le Marais", "101"))));
    final Document document = Document.of(text)
        .with(Layers.ENTITIES, List.of(
            new Annotation<>(unresolvable, "location"),
            new Annotation<>(resolvable, "location")));

    final Document annotated = new HierarchyAnnotator(exampleSpine())
        .annotate(new GeocodeAnnotator(geocoder).annotate(document));

    final List<Annotation<ContainmentChain>> chains =
        annotated.get(HierarchyAnnotator.CONTAINMENT);
    Assertions.assertEquals(1, chains.size());
    Assertions.assertEquals(resolvable, chains.get(0).span());
    Assertions.assertEquals(3, chains.get(0).value().ancestors().size());
  }

  /** The manual's raw-text pipeline preserves offsets across sentences and repeated mentions. */
  @Test
  void testRawTextWithCustomHierarchyIdentifiers() {
    final String joinKey = "customer:district";
    final GazetteerEntry place = new GazetteerEntry("catalog", "record-7", "Le Marais",
        List.of(), new GeoPoint(48.859, 2.361), "FR", List.of(), 0,
        GazetteerEntry.FEATURE_CLASS_CITY,
        Map.of(joinKey, new AttributeValue("district-1", "customer", "")));
    final InMemoryGazetteer gazetteer = InMemoryGazetteer.fromEntries(List.of(place));
    final ContainmentSpine hierarchy = ContainmentSpine.builder()
        .add("district-1", "city-1", "Le Marais", "neighbourhood")
        .add("city-1", "country-1", "Paris", "locality")
        .add("country-1", null, "France", "country")
        .build();
    final Dictionary names = new Dictionary();
    names.put(new StringList("Le", "Marais"));
    names.put(new StringList("Narnia"));
    final DocumentAnalyzer analyzer = DocumentAnalyzer.builder()
        .add(new SentenceDetectorAnnotator(new NewlineSentenceDetector()))
        .add(new TokenizerAnnotator(SimpleTokenizer.INSTANCE))
        .add(new NameFinderAnnotator(new DictionaryNameFinder(names, "location")))
        .add(new GeocodeAnnotator(new PopulationPriorGeocoder(gazetteer)))
        .add(new HierarchyAnnotator(hierarchy, joinKey))
        .build();
    final String text = "\uD83D\uDE86 Le Marais.\nLe Marais and Narnia.";
    final Document document = analyzer.analyze(text);
    final List<Annotation<ContainmentChain>> chains = document.get(HierarchyAnnotator.CONTAINMENT);

    Assertions.assertEquals(text, document.text().toString());
    Assertions.assertEquals(Set.of(Layers.SENTENCES, Layers.TOKENS, Layers.ENTITIES,
        GeocodeAnnotator.LOCATIONS, HierarchyAnnotator.CONTAINMENT), document.layers());
    Assertions.assertEquals(2, document.get(Layers.SENTENCES).size());
    Assertions.assertEquals(3, document.get(Layers.ENTITIES).size());
    Assertions.assertEquals("Narnia", document.get(Layers.ENTITIES).getLast()
        .span().getCoveredText(text).toString());
    final List<Annotation<GeoResolution>> locations = document.get(GeocodeAnnotator.LOCATIONS);
    Assertions.assertEquals(2, locations.size());
    Assertions.assertEquals(List.of(new Span(3, 12), new Span(14, 23)),
        chains.stream().map(Annotation::span).toList());
    for (int i = 0; i < chains.size(); i++) {
      Assertions.assertEquals("Le Marais", chains.get(i).span().getCoveredText(text).toString());
      Assertions.assertEquals(locations.get(i).span(), chains.get(i).span());
      Assertions.assertEquals("record-7", locations.get(i).value().entry().recordId());
      Assertions.assertEquals(List.of(new PlaceAncestor("city-1", "Paris", "locality"),
          new PlaceAncestor("country-1", "France", "country")), chains.get(i).value().ancestors());
    }
    final Document empty = analyzer.analyze("");
    Assertions.assertEquals(document.layers(), empty.layers());
    for (final var layer : empty.layers()) {
      Assertions.assertTrue(empty.get(layer).isEmpty());
    }
  }
}
