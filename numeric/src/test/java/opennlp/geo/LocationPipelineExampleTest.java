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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import opennlp.tools.dictionary.Dictionary;
import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.Layers;
import opennlp.tools.geo.DocumentRegionAnnotator;
import opennlp.tools.geo.Gazetteer;
import opennlp.tools.geo.GazetteerEntry;
import opennlp.tools.geo.GeoResolution;
import opennlp.tools.geo.GeocodeAnnotator;
import opennlp.tools.geo.RegionVote;
import opennlp.tools.money.MoneyAmount;
import opennlp.tools.money.MoneyAnnotator;
import opennlp.tools.money.RegionAwareMoneyAnnotator;
import opennlp.tools.namefind.DictionaryNameFinder;
import opennlp.tools.namefind.NameFinderAnnotator;
import opennlp.tools.sentdetect.NewlineSentenceDetector;
import opennlp.tools.sentdetect.SentenceDetectorAnnotator;
import opennlp.tools.tokenize.SimpleTokenizer;
import opennlp.tools.tokenize.TokenizerAnnotator;
import opennlp.tools.util.StringList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the manual's location-pipeline example (docbkx {@code geo.xml}, section
 * {@code tools.geo.pipeline}) verbatim: raw text through real, model-free components into
 * geocoding over a real gazetteer with a user overlay, the document region ballot, and
 * region-aware currency resolution. Every value asserted here is stated in the chapter; a
 * change breaking this test breaks the manual.
 *
 * <p>The point the example makes: injecting one customer site through the overlay flips the
 * document's winning region from Mexico to the United States, which flips how the ambiguous
 * {@code $} sign resolves.</p>
 */
public class LocationPipelineExampleTest {

  /** The example document: a customer site, a bundled-gazetteer city, an ambiguous amount. */
  private static final String TEXT =
      "Acme Plant 3 outsells Guadalajara this quarter, and lunch there costs $8.";

  /** The customer places file exactly as printed in the manual's user-places section. */
  private static final String CUSTOMER_ROWS = String.join("\n",
      "# id\tname\talternates\tlat\tlon\tcc\tclass\tpop\tbbox\tcontainment\tattributes",
      "plant-3\tAcme Plant 3\tPlant 3|The Plant\t33.75\t-84.39\tUS\tPOI\t0\t\t"
          + "Georgia|Fulton County\taddress=123 Main St, Atlanta GA 30303",
      "campus-east\tAcme Campus East\t\t\t\tUS\tPOI\t0\t-84.5,33.6,-84.2,33.9",
      "") + "\n";

  /**
   * Builds the location dictionary driving the model-free name finder of the example.
   *
   * @return A {@link Dictionary} holding the two example place names. Never {@code null}.
   */
  private static Dictionary places() {
    final Dictionary places = new Dictionary();
    places.put(new StringList("Acme", "Plant", "3"));
    places.put(new StringList("Guadalajara"));
    return places;
  }

  /**
   * Runs the whole pipeline over a gazetteer.
   *
   * @param gazetteer The gazetteer backing the geocoder. Must not be {@code null}.
   * @return The fully annotated document. Never {@code null}.
   * @throws IOException Propagated from gazetteer-backed lookups; the in-memory setup of
   *         this example never throws it.
   */
  private static Document pipeline(Gazetteer gazetteer) throws IOException {
    Document document = Document.of(TEXT);
    document = new SentenceDetectorAnnotator(new NewlineSentenceDetector()).annotate(document);
    document = new TokenizerAnnotator(SimpleTokenizer.INSTANCE).annotate(document);
    document = new NameFinderAnnotator(
        new DictionaryNameFinder(places(), "location")).annotate(document);
    document = new GeocodeAnnotator(new PopulationPriorGeocoder(gazetteer)).annotate(document);
    document = new DocumentRegionAnnotator().annotate(document);
    return new RegionAwareMoneyAnnotator().annotate(document);
  }

  /**
   * Runs the example as the manual shows it: the bundled gazetteer overlaid with the
   * customer file. The customer site resolves from the overlay at the single-candidate
   * confidence, Guadalajara resolves to the Mexican city from the bundled table, the
   * United States wins the ballot by a hair, and the {@code $} amount is USD.
   */
  @Test
  void testCustomerSiteFlipsTheBallotAndTheCurrency() throws IOException {
    final Gazetteer gazetteer = new OverlayGazetteer(
        BundledGazetteer.getInstance(),
        UserGazetteer.load(new ByteArrayInputStream(
            CUSTOMER_ROWS.getBytes(StandardCharsets.UTF_8)), "customer"),
        List.of());
    final Document document = pipeline(gazetteer);

    final List<Annotation<String>> entities = document.get(Layers.ENTITIES);
    assertEquals(2, entities.size());
    assertEquals("Acme Plant 3",
        entities.get(0).span().getCoveredText(TEXT).toString());
    assertEquals("Guadalajara",
        entities.get(1).span().getCoveredText(TEXT).toString());

    final List<Annotation<GeoResolution>> locations =
        document.get(GeocodeAnnotator.LOCATIONS);
    assertEquals(2, locations.size());
    final GeoResolution plant = locations.get(0).value();
    assertEquals("customer", plant.entry().source());
    assertEquals("plant-3", plant.entry().recordId());
    assertEquals(0.9, plant.confidence(), 0.0,
        "a single overlay candidate scores the documented single-candidate confidence");
    final GeoResolution guadalajara = locations.get(1).value();
    assertEquals("naturalearth", guadalajara.entry().source());
    assertEquals("1159151293", guadalajara.entry().recordId());
    assertEquals("MX", guadalajara.entry().countryCode());
    assertEquals(4198000L, guadalajara.entry().population());
    assertEquals(guadalajaraConfidence(gazetteer), guadalajara.confidence(), 0.0);

    final List<Annotation<RegionVote>> ballot =
        document.get(DocumentRegionAnnotator.REGIONS);
    assertEquals(2, ballot.size());
    final double total = 0.9 + guadalajara.confidence();
    assertEquals("US", ballot.get(0).value().countryCode());
    assertEquals(0.9 / total, ballot.get(0).value().share(), 0.0);
    assertEquals("MX", ballot.get(1).value().countryCode());
    assertEquals(guadalajara.confidence() / total, ballot.get(1).value().share(), 0.0);
    assertTrue(ballot.get(0).value().share() < 0.51,
        "the customer site wins the ballot by a hair, not a landslide");

    final List<Annotation<MoneyAmount>> money = document.get(MoneyAnnotator.MONEY);
    assertEquals(1, money.size());
    assertEquals("USD", money.get(0).value().currency());
    assertEquals(0, new BigDecimal("8").compareTo(money.get(0).value().amount()));
    assertEquals("$8", money.get(0).span().getCoveredText(TEXT).toString());
  }

  /**
   * Runs the identical pipeline without the overlay, the contrast the manual states: the
   * customer site resolves to nothing, Mexico takes the whole ballot, and the same
   * {@code $} sign is a peso amount.
   */
  @Test
  void testWithoutTheOverlayMexicoWinsAndTheSignIsPesos() throws IOException {
    final Document document = pipeline(BundledGazetteer.getInstance());

    final List<Annotation<GeoResolution>> locations =
        document.get(GeocodeAnnotator.LOCATIONS);
    assertEquals(1, locations.size(), "the customer site is unknown to the public dataset");
    assertEquals("Guadalajara", locations.get(0).value().entry().name());

    final List<Annotation<RegionVote>> ballot =
        document.get(DocumentRegionAnnotator.REGIONS);
    assertEquals(1, ballot.size());
    assertEquals("MX", ballot.get(0).value().countryCode());
    assertEquals(1.0, ballot.get(0).value().share(), 0.0);

    final List<Annotation<MoneyAmount>> money = document.get(MoneyAnnotator.MONEY);
    assertEquals("MXN", money.get(0).value().currency());
  }

  /**
   * Recomputes the geocoder's documented two-candidate confidence for Guadalajara from the
   * gazetteer's own candidate list, so the assertion tracks the bundled data rather than
   * hard-coding a float.
   *
   * @param gazetteer The gazetteer the pipeline used. Must not be {@code null}.
   * @return The expected confidence of the Guadalajara resolution.
   * @throws IOException Propagated from the lookup; never thrown by the in-memory setup.
   */
  private static double guadalajaraConfidence(Gazetteer gazetteer) throws IOException {
    final List<GazetteerEntry> candidates = gazetteer.lookup("Guadalajara");
    assertEquals(2, candidates.size(),
        "the bundled table knows the Mexican city and one more Guadalajara");
    final long p1 = candidates.get(0).population();
    final long p2 = candidates.get(1).population();
    return 0.5 + 0.4 * ((double) (p1 - p2) / (p1 + p2));
  }
}
