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

package opennlp.tools.geo;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.Layers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the edges of the region ballot: the deterministic order of tied countries, the
 * country-name vote when the geocoder resolves nothing, the empty ballot of a document
 * without location entities, and the confidence weighting that lets one strong mention
 * outvote several weak ones.
 */
public class DocumentRegionBallotEdgeCaseTest {

  /**
   * Asserts the ballot invariant: the shares of all rows sum to one, so a ballot is a
   * probability distribution over its countries.
   *
   * @param ballot The ballot rows to check. Must not be {@code null}.
   */
  private static void assertSharesSumToOne(List<Annotation<RegionVote>> ballot) {
    double sum = 0.0;
    for (final Annotation<RegionVote> row : ballot) {
      sum += row.value().share();
    }
    assertEquals(1.0, sum, 1e-9);
  }

  /**
   * Verifies the ranking rule for a tie: two countries with equal weight split the
   * ballot evenly, and the tie breaks by ascending country code, so {@code FR} ranks
   * ahead of {@code GB} regardless of mention order in the text.
   *
   * @param text The document text, mentioning the two cities in either order.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "trains between London and Paris",
      "trains between Paris and London"
  })
  void testTieBreaksByAscendingCountryCode(String text) {
    final Geocoder geocoder = GeoTestUtil.tableGeocoder(Map.of(
        "London", new GeoTestUtil.ScoredCountry("GB", 0.8),
        "Paris", new GeoTestUtil.ScoredCountry("FR", 0.8)));
    final Document document = new DocumentRegionAnnotator(geocoder)
        .annotate(GeoTestUtil.withLocations(text, "London", "Paris"));

    final List<Annotation<RegionVote>> ballot =
        document.get(DocumentRegionAnnotator.REGIONS);
    assertEquals(2, ballot.size());
    assertEquals("FR", ballot.get(0).value().countryCode());
    assertEquals(0.5, ballot.get(0).value().share(), 0.0);
    assertEquals("GB", ballot.get(1).value().countryCode());
    assertEquals(0.5, ballot.get(1).value().share(), 0.0);
  }

  /**
   * Verifies that country-name mentions carry a ballot on their own: with two English
   * country names and a geocoder that resolves none of them, both names vote with
   * the fixed country-name weight, tie evenly, and rank by ascending country code.
   */
  @Test
  void testCountryNamesAloneFillTheBallotWithoutGeocoderEvidence() {
    final Document document = new DocumentRegionAnnotator(GeoTestUtil.tableGeocoder(Map.of()))
        .annotate(GeoTestUtil.withLocations("trade between Mexico and New Zealand grew",
            "Mexico", "New Zealand"));

    final List<Annotation<RegionVote>> ballot =
        document.get(DocumentRegionAnnotator.REGIONS);
    assertEquals(2, ballot.size());
    assertEquals("MX", ballot.get(0).value().countryCode());
    assertEquals(0.5, ballot.get(0).value().share(), 0.0);
    assertEquals("NZ", ballot.get(1).value().countryCode());
    assertEquals(0.5, ballot.get(1).value().share(), 0.0);
  }

  /**
   * Verifies that an absent entity layer is rejected as an assembly error: the
   * annotator requires {@code Layers.ENTITIES}, so a document without that layer is
   * refused with an exception naming it, rather than silently producing an empty
   * ballot. A present but empty entity layer is the empty-document case instead.
   */
  @Test
  void testMissingEntityLayerIsRejectedNamingTheLayer() {
    final DocumentRegionAnnotator annotator =
        new DocumentRegionAnnotator(GeoTestUtil.unreachableGeocoder());
    final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> annotator.annotate(Document.of("nothing to locate here")));
    assertEquals("document lacks the required layer opennlp:entities<String>",
        e.getMessage());
  }

  /**
   * Verifies that confidence weights the vote rather than the mention count: two French
   * mentions at low confidence lose to one US mention at high confidence, and the
   * shares are the exact confidence sums over the ballot total.
   */
  @Test
  void testOneConfidentMentionOutvotesTwoWeakOnes() {
    final Geocoder geocoder = GeoTestUtil.tableGeocoder(Map.of(
        "Nice", new GeoTestUtil.ScoredCountry("FR", 0.3),
        "Nancy", new GeoTestUtil.ScoredCountry("FR", 0.3),
        "Chicago", new GeoTestUtil.ScoredCountry("US", 0.7)));
    final Document document = new DocumentRegionAnnotator(geocoder)
        .annotate(GeoTestUtil.withLocations("flights from Nice and Nancy to Chicago",
            "Nice", "Nancy", "Chicago"));

    final List<Annotation<RegionVote>> ballot =
        document.get(DocumentRegionAnnotator.REGIONS);
    assertEquals(2, ballot.size());
    assertEquals("US", ballot.get(0).value().countryCode());
    assertEquals(0.7 / (0.7 + 0.6), ballot.get(0).value().share(), 0.0);
    assertEquals("FR", ballot.get(1).value().countryCode());
    assertEquals(0.6 / (0.7 + 0.6), ballot.get(1).value().share(), 0.0);
  }

  /**
   * Verifies that a resolution at confidence {@code 0.0}, which the {@link GeoResolution}
   * contract allows, carries no evidence and therefore casts no vote: the sole mention of
   * the document resolves at zero confidence, so the region layer is present and empty
   * rather than the annotator failing on a share of {@code 0.0 / 0.0}.
   */
  @Test
  void testZeroConfidenceResolutionCastsNoVote() {
    final Geocoder geocoder = GeoTestUtil.tableGeocoder(Map.of(
        "Bilbao", new GeoTestUtil.ScoredCountry("ES", 0.0)));
    final Document document = new DocumentRegionAnnotator(geocoder)
        .annotate(GeoTestUtil.withLocations("a dispatch from Bilbao", "Bilbao"));

    assertTrue(document.get(DocumentRegionAnnotator.REGIONS).isEmpty());
    assertTrue(document.layers().contains(DocumentRegionAnnotator.REGIONS));
  }

  /**
   * Verifies that a zero-confidence resolution neither dilutes nor blocks the ballot: a
   * mention resolving at confidence {@code 0.0} shares the document with a mention
   * resolving at {@code 0.8}, so the ballot holds exactly one row, the confident country
   * at the full share.
   */
  @Test
  void testZeroConfidenceResolutionDoesNotDiluteTheBallot() {
    final Geocoder geocoder = GeoTestUtil.tableGeocoder(Map.of(
        "Bilbao", new GeoTestUtil.ScoredCountry("ES", 0.0),
        "Sydney", new GeoTestUtil.ScoredCountry("AU", 0.8)));
    final Document document = new DocumentRegionAnnotator(geocoder)
        .annotate(GeoTestUtil.withLocations("flights from Bilbao to Sydney", "Bilbao", "Sydney"));

    final List<Annotation<RegionVote>> ballot =
        document.get(DocumentRegionAnnotator.REGIONS);
    assertEquals(1, ballot.size());
    assertEquals("AU", ballot.get(0).value().countryCode());
    assertEquals(1.0, ballot.get(0).value().share(), 0.0);
  }

  /**
   * Verifies the share-sum invariant on a three-way ballot: three mentions resolving
   * to three countries with different confidences produce three rows whose shares sum
   * to one, ranked by descending confidence.
   */
  @Test
  void testThreeWayBallotSharesSumToOne() {
    final Geocoder geocoder = GeoTestUtil.tableGeocoder(Map.of(
        "Sydney", new GeoTestUtil.ScoredCountry("AU", 0.8),
        "Auckland", new GeoTestUtil.ScoredCountry("NZ", 0.7),
        "London", new GeoTestUtil.ScoredCountry("GB", 0.5)));
    final Document document = new DocumentRegionAnnotator(geocoder)
        .annotate(GeoTestUtil.withLocations("flights from Sydney and Auckland to London",
            "Sydney", "Auckland", "London"));

    final List<Annotation<RegionVote>> ballot =
        document.get(DocumentRegionAnnotator.REGIONS);
    assertEquals(3, ballot.size());
    assertEquals("AU", ballot.get(0).value().countryCode());
    assertEquals("NZ", ballot.get(1).value().countryCode());
    assertEquals("GB", ballot.get(2).value().countryCode());
    assertSharesSumToOne(ballot);
  }

  /**
   * Verifies the degenerate texts: an empty and a whitespace-only document, each with
   * a present but empty entity layer, produce a present and empty ballot without
   * consulting the geocoder.
   *
   * @param text The degenerate document text.
   */
  @ParameterizedTest
  @ValueSource(strings = {"", "   "})
  void testEmptyAndWhitespaceOnlyTextsYieldAnEmptyBallot(String text) {
    final Document document =
        new DocumentRegionAnnotator(GeoTestUtil.unreachableGeocoder())
            .annotate(Document.of(text).with(Layers.ENTITIES, List.of()));

    assertTrue(document.get(DocumentRegionAnnotator.REGIONS).isEmpty());
    assertTrue(document.layers().contains(DocumentRegionAnnotator.REGIONS));
  }

  /**
   * Verifies that a present but empty entity layer is the graceful case, distinct from
   * the absent layer that is rejected: the document passes and gets a present, empty
   * ballot, and the geocoder is never consulted for it.
   */
  @Test
  void testPresentButEmptyEntityLayerYieldsAnEmptyPresentBallot() {
    final Document document =
        new DocumentRegionAnnotator(GeoTestUtil.unreachableGeocoder())
            .annotate(Document.of("nothing to locate here")
                .with(Layers.ENTITIES, List.of()));

    assertTrue(document.get(DocumentRegionAnnotator.REGIONS).isEmpty());
    assertTrue(document.layers().contains(DocumentRegionAnnotator.REGIONS));
  }

  /**
   * Verifies the country-less gazetteer entry branch: a resolution whose entry carries
   * no country code, as an ocean or a disputed territory may, casts no vote, so the
   * remaining mention holds the full share and the country-less entry neither dilutes
   * nor blocks the ballot.
   */
  @Test
  void testNullCountryCodeResolutionCastsNoVote() {
    final Geocoder geocoder = GeoTestUtil.tableGeocoder(Map.of(
        "Pacific", new GeoTestUtil.ScoredCountry(null, 0.9),
        "Sydney", new GeoTestUtil.ScoredCountry("AU", 0.8)));
    final Document document = new DocumentRegionAnnotator(geocoder)
        .annotate(GeoTestUtil.withLocations("across the Pacific to Sydney",
            "Pacific", "Sydney"));

    final List<Annotation<RegionVote>> ballot =
        document.get(DocumentRegionAnnotator.REGIONS);
    assertEquals(1, ballot.size());
    assertEquals("AU", ballot.get(0).value().countryCode());
    assertEquals(1.0, ballot.get(0).value().share(), 0.0);
  }

  /**
   * Verifies that a {@code null} document is rejected with a clear exception before any
   * layer is touched.
   */
  @Test
  void testNullDocumentIsRejected() {
    final DocumentRegionAnnotator annotator =
        new DocumentRegionAnnotator(GeoTestUtil.unreachableGeocoder());
    assertThrows(IllegalArgumentException.class, () -> annotator.annotate(null));
  }
}
