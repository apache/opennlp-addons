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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.Layers;
import opennlp.tools.geo.DocumentRegionAnnotator;
import opennlp.tools.geo.GeoResolution;
import opennlp.tools.geo.GeoTestUtil;
import opennlp.tools.geo.GeocodeAnnotator;
import opennlp.tools.geo.Geocoder;
import opennlp.tools.geo.RegionVote;
import opennlp.tools.util.Span;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Demonstrates per-document currency symbol resolution end to end on one realistic
 * document: two city mentions resolve to different countries with different geocoder
 * confidences, the region ballot ranks the countries by their confidence-weighted
 * shares, and the winning country decides that the ambiguous {@code $} sign denotes the
 * Mexican peso rather than the US dollar.
 */
public class RegionCurrencyResolutionExampleTest {

  /** The example document: a Mexican city, a US city, and an ambiguous dollar amount. */
  private static final String TEXT =
      "Guadalajara outsells Boston this quarter, and lunch there costs $8.";

  /** The geocoder confidence of the Guadalajara resolution. */
  private static final double GUADALAJARA_CONFIDENCE = 0.9;

  /** The geocoder confidence of the Boston resolution. */
  private static final double BOSTON_CONFIDENCE = 0.6;

  /**
   * Builds a geocoder that resolves the two example cities with fixed confidences:
   * Guadalajara to Mexico and Boston to the United States.
   *
   * @return A {@link Geocoder} for the example document. Never {@code null}.
   */
  private static Geocoder exampleGeocoder() {
    return (text, mentions) -> {
      final List<GeoResolution> resolutions = new ArrayList<>();
      for (final Span mention : mentions) {
        final String name =
            text.subSequence(mention.getStart(), mention.getEnd()).toString();
        switch (name) {
          case "Guadalajara" -> resolutions.add(
              new GeoResolution(mention, GeoTestUtil.entry(name, "MX"), GUADALAJARA_CONFIDENCE));
          case "Boston" -> resolutions.add(
              new GeoResolution(mention, GeoTestUtil.entry(name, "US"), BOSTON_CONFIDENCE));
          default -> throw new IllegalStateException("unexpected mention: " + name);
        }
      }
      return resolutions;
    };
  }

  /**
   * Builds a location entity annotation over one mention of the example text.
   *
   * @param mention The mention text to locate. Must occur in {@link #TEXT}.
   * @return An {@link Annotation} typed {@code location} over the mention.
   * @throws IllegalArgumentException Thrown if the mention does not occur in the text.
   */
  private static Annotation<String> locationEntity(String mention) {
    final int start = TEXT.indexOf(mention);
    if (start < 0) {
      throw new IllegalArgumentException("mention not in the example text: " + mention);
    }
    return new Annotation<>(new Span(start, start + mention.length()), "location");
  }

  /**
   * Runs the example: the entity layer is geocoded into a locations layer that feeds
   * the region ballot, and the ballot winner picks the symbol table for the money
   * layer. Asserts the exact ranked shares, that every ballot row is span-less under
   * the document-scoped key, and that the {@code $} amount is
   * identified as {@code MXN} because Mexico wins the ballot.
   */
  @Test
  void testWinningCountryResolvesTheDollarSign() {
    final Document withEntities = Document.of(TEXT).with(Layers.ENTITIES,
        List.of(locationEntity("Guadalajara"), locationEntity("Boston")));

    final Document document = new RegionAwareMoneyAnnotator().annotate(
        new DocumentRegionAnnotator().annotate(
            new GeocodeAnnotator(exampleGeocoder()).annotate(withEntities)));

    final List<Annotation<RegionVote>> ballot =
        document.get(DocumentRegionAnnotator.REGIONS);
    assertEquals(2, ballot.size());
    final double total = GUADALAJARA_CONFIDENCE + BOSTON_CONFIDENCE;
    assertEquals("MX", ballot.get(0).value().countryCode());
    assertEquals(GUADALAJARA_CONFIDENCE / total, ballot.get(0).value().share(), 0.0);
    assertEquals("US", ballot.get(1).value().countryCode());
    assertEquals(BOSTON_CONFIDENCE / total, ballot.get(1).value().share(), 0.0);
    assertNull(ballot.get(0).span());
    assertNull(ballot.get(1).span());

    final List<Annotation<MoneyAmount>> money = document.get(MoneyAnnotator.MONEY);
    assertEquals(1, money.size());
    assertEquals("MXN", money.get(0).value().currency());
    assertEquals(0, new BigDecimal("8").compareTo(money.get(0).value().amount()));
    assertEquals("$8",
        money.get(0).span().getCoveredText(document.text()).toString());
  }

  /**
   * Mirrors the manual's ballot-reading listing: iterating the ranked rows yields the
   * country codes and shares in rank order, {@code MX} at {@code 0.6} ahead of
   * {@code US} at {@code 0.4}.
   */
  @Test
  void testReadingTheRankedBallotRows() {
    final Document document = new DocumentRegionAnnotator().annotate(
        new GeocodeAnnotator(exampleGeocoder()).annotate(
            Document.of(TEXT).with(Layers.ENTITIES,
                List.of(locationEntity("Guadalajara"), locationEntity("Boston")))));

    final List<Annotation<RegionVote>> ballot =
        document.get(DocumentRegionAnnotator.REGIONS);
    final List<String> countries = new ArrayList<>();
    final List<Double> shares = new ArrayList<>();
    for (final Annotation<RegionVote> row : ballot) {
      countries.add(row.value().countryCode()); // "MX", then "US"
      shares.add(row.value().share());          // 0.6, then 0.4
    }
    assertEquals(List.of("MX", "US"), countries);
    assertEquals(0.6, shares.get(0), 1e-9);
    assertEquals(0.4, shares.get(1), 1e-9);
  }
}
