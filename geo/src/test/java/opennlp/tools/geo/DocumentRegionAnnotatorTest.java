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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.Layers;
import opennlp.tools.util.Span;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the region ballot: geocoded mentions vote with their confidence, country-name
 * mentions vote directly through JDK locale data, and shares rank the result.
 */
public class DocumentRegionAnnotatorTest {

  /** The tolerance for a share compared against a recomputed quotient. */
  private static final double SHARE_DELTA = 1e-9;

  /** The confidence every resolution of the table geocoder reports. */
  private static final double TABLE_CONFIDENCE = 0.8;

  /** Mirrors the annotator's fixed vote weight of a direct country-name mention. */
  private static final double COUNTRY_NAME_WEIGHT = 0.95;

  /** The entity type label the annotator treats as a location by default. */
  private static final String LOCATION = "location";

  /**
   * A no-break space: blank under the toolkit's whitespace definition, but not under
   * {@link String#isBlank()}.
   */
  private static final String NO_BREAK_SPACE = "\u00A0";

  @ParameterizedTest
  @CsvSource({"ΟΣ,οσ", "οσ,ΟΣ", "İ,i", "i,İ", "𐐀,𐐨"})
  void testLocationTypesUseCodePointCaseMapping(String configured, String observed) {
    final Geocoder geocoder = GeoTestUtil.tableGeocoder(Map.of("Sydney", "AU"), TABLE_CONFIDENCE);
    final Document document = Document.of("Sydney").with(Layers.ENTITIES,
        List.of(new Annotation<>(new Span(0, 6), observed)));
    final Document annotated = new DocumentRegionAnnotator(geocoder, Set.of(configured))
        .annotate(document);
    final List<Annotation<RegionVote>> ballot = annotated.get(DocumentRegionAnnotator.REGIONS);
    assertEquals(1, ballot.size());
    assertEquals("AU", ballot.get(0).value().countryCode());
  }

  @Test
  void testGeocodedMentionsVoteWithConfidence() {
    final Geocoder geocoder = GeoTestUtil.tableGeocoder(
        Map.of("Sydney", "AU", "Melbourne", "AU", "Auckland", "NZ"), TABLE_CONFIDENCE);
    final String text = "flights from Sydney and Melbourne to Auckland";
    final Document document = new DocumentRegionAnnotator(geocoder)
        .annotate(GeoTestUtil.withLocations(text, "Sydney", "Melbourne", "Auckland"));

    final List<Annotation<RegionVote>> ballot =
        document.get(DocumentRegionAnnotator.REGIONS);
    assertEquals(2, ballot.size());
    assertEquals("AU", ballot.get(0).value().countryCode());
    assertEquals(2.0 / 3.0, ballot.get(0).value().share(), SHARE_DELTA);
    assertEquals("NZ", ballot.get(1).value().countryCode());
    assertNull(ballot.get(0).span());
  }

  @Test
  void testCountryNamesVoteWithoutTheGazetteer() {
    // the geocoder resolves nothing; the country name still votes
    final Geocoder geocoder = GeoTestUtil.tableGeocoder(Map.of());
    final String text = "mining exports from Australia rose";
    final Document document = new DocumentRegionAnnotator(geocoder)
        .annotate(GeoTestUtil.withLocations(text, "Australia"));

    final List<Annotation<RegionVote>> ballot =
        document.get(DocumentRegionAnnotator.REGIONS);
    assertEquals(1, ballot.size());
    assertEquals("AU", ballot.get(0).value().countryCode());
    assertEquals(1.0, ballot.get(0).value().share(), SHARE_DELTA);
  }

  /**
   * Verifies that the geocoder can override a country-name mention: {@code Georgia} is
   * the display name of the country {@code GE}, but the geocoder resolves the mention
   * to the US state, so the name vote is dropped and the document elects {@code US},
   * pricing its dollars as USD rather than GEL.
   */
  @Test
  void testGeocoderOverridesADisagreeingCountryNameMention() {
    final Geocoder geocoder = GeoTestUtil.tableGeocoder(
        Map.of("Georgia", "US", "Atlanta", "US"), TABLE_CONFIDENCE);
    final String text = "Georgia's secretary of state confirmed the recount in Atlanta";
    final Document document = new DocumentRegionAnnotator(geocoder)
        .annotate(GeoTestUtil.withLocations(text, "Georgia", "Atlanta"));

    final List<Annotation<RegionVote>> ballot =
        document.get(DocumentRegionAnnotator.REGIONS);
    assertEquals(1, ballot.size());
    assertEquals("US", ballot.get(0).value().countryCode());
    assertEquals(1.0, ballot.get(0).value().share(), SHARE_DELTA);
  }

  /**
   * Verifies that a geocoder agreeing with a country-name mention does not double the
   * vote: {@code Australia} resolves to {@code AU} both by name and through the
   * geocoder, so it votes once with the country-name weight and the shares stay the
   * exact name-weight and confidence quotients.
   */
  @Test
  void testAgreeingGeocoderKeepsTheSingleCountryNameVote() {
    final Geocoder geocoder = GeoTestUtil.tableGeocoder(
        Map.of("Australia", "AU", "Auckland", "NZ"), TABLE_CONFIDENCE);
    final String text = "exports from Australia to Auckland rose";
    final Document document = new DocumentRegionAnnotator(geocoder)
        .annotate(GeoTestUtil.withLocations(text, "Australia", "Auckland"));

    final List<Annotation<RegionVote>> ballot =
        document.get(DocumentRegionAnnotator.REGIONS);
    assertEquals(2, ballot.size());
    assertEquals("AU", ballot.get(0).value().countryCode());
    assertEquals(COUNTRY_NAME_WEIGHT / (COUNTRY_NAME_WEIGHT + TABLE_CONFIDENCE),
        ballot.get(0).value().share(), SHARE_DELTA);
    assertEquals("NZ", ballot.get(1).value().countryCode());
    assertEquals(TABLE_CONFIDENCE / (COUNTRY_NAME_WEIGHT + TABLE_CONFIDENCE),
        ballot.get(1).value().share(), SHARE_DELTA);
  }

  /**
   * Pins the country-name weight against geocoder confidence: a country name the
   * geocoder cannot resolve votes at {@code 0.95} while a confidently geocoded city
   * votes at {@code 0.8}, so New Zealand outranks Sydney's Australia and the shares
   * are the exact weight quotients, about {@code 0.543} to {@code 0.457}.
   */
  @Test
  void testCountryNameOutweighsAConfidentlyGeocodedCity() {
    final Geocoder geocoder =
        GeoTestUtil.tableGeocoder(Map.of("Sydney", "AU"), TABLE_CONFIDENCE);
    final String text = "the Sydney office reports to New Zealand headquarters";
    final Document document = new DocumentRegionAnnotator(geocoder)
        .annotate(GeoTestUtil.withLocations(text, "Sydney", "New Zealand"));

    final List<Annotation<RegionVote>> ballot =
        document.get(DocumentRegionAnnotator.REGIONS);
    assertEquals(2, ballot.size());
    assertEquals("NZ", ballot.get(0).value().countryCode());
    assertEquals(COUNTRY_NAME_WEIGHT / (COUNTRY_NAME_WEIGHT + TABLE_CONFIDENCE),
        ballot.get(0).value().share(), SHARE_DELTA);
    assertEquals("AU", ballot.get(1).value().countryCode());
    assertEquals(TABLE_CONFIDENCE / (COUNTRY_NAME_WEIGHT + TABLE_CONFIDENCE),
        ballot.get(1).value().share(), SHARE_DELTA);
  }

  /**
   * Verifies that country-name matching tolerates the spellings real text uses: the
   * CLDR display name's apostrophe (U+2019) and the ASCII apostrophe, the accented and
   * the plain spelling of a name with diacritics, and any letter case. The geocoder
   * resolves nothing, so each match is proven by the resulting one-row ballot.
   *
   * @param mention The country mention as it appears in the text.
   * @param expectedCountry The ISO 3166-1 alpha-2 code the mention must elect.
   */
  @ParameterizedTest
  @CsvSource(quoteCharacter = '"', value = {
      "Côte d’Ivoire, CI",
      "Côte d'Ivoire, CI",
      "Cote d'Ivoire, CI",
      "México, MX",
      "AUSTRALIA, AU",
      "australia, AU"
  })
  void testCountryNameSpellingVariantsMatch(String mention, String expectedCountry) {
    final Document document = new DocumentRegionAnnotator(GeoTestUtil.tableGeocoder(Map.of()))
        .annotate(GeoTestUtil.withLocations("new offices in " + mention + " opened", mention));

    final List<Annotation<RegionVote>> ballot =
        document.get(DocumentRegionAnnotator.REGIONS);
    assertEquals(1, ballot.size());
    assertEquals(expectedCountry, ballot.get(0).value().countryCode());
    assertEquals(1.0, ballot.get(0).value().share(), SHARE_DELTA);
  }

  @Test
  void testNoEvidenceMeansAnEmptyBallot() {
    final Geocoder geocoder = GeoTestUtil.tableGeocoder(Map.of());
    final Document document = new DocumentRegionAnnotator(geocoder)
        .annotate(GeoTestUtil.withLocations("nothing resolvable in Atlantis", "Atlantis"));
    assertTrue(document.get(DocumentRegionAnnotator.REGIONS).isEmpty());
  }

  @Test
  void testNonLocationEntitiesDoNotVote() {
    final Geocoder geocoder = GeoTestUtil.tableGeocoder(Map.of("Sydney", "AU"), TABLE_CONFIDENCE);
    final String text = "Sydney Smith spoke";
    final Document document = Document.of(text)
        .with(Layers.ENTITIES,
            List.of(new Annotation<>(new Span(0, 12), "person")));
    assertTrue(new DocumentRegionAnnotator(geocoder).annotate(document)
        .get(DocumentRegionAnnotator.REGIONS).isEmpty());
  }

  @Test
  void testCustomLocationTypesMatchCaseInsensitively() {
    final Geocoder geocoder = GeoTestUtil.tableGeocoder(Map.of("Sydney", "AU"), TABLE_CONFIDENCE);
    final String text = "Sydney hosts the summit";
    final Document document = Document.of(text).with(Layers.ENTITIES,
        List.of(new Annotation<>(new Span(0, 6), "GPE")));
    final Document annotated =
        new DocumentRegionAnnotator(geocoder, Set.of("gpe")).annotate(document);

    final List<Annotation<RegionVote>> ballot =
        annotated.get(DocumentRegionAnnotator.REGIONS);
    assertEquals(1, ballot.size());
    assertEquals("AU", ballot.get(0).value().countryCode());
  }

  @Test
  void testGeocoderIoFailureSurfacesUnchecked() {
    final Geocoder failing = (text, mentions) -> {
      throw new IOException("gazetteer unavailable");
    };
    final Document document = GeoTestUtil.withLocations("a dispatch from Bilbao", "Bilbao");
    assertThrows(UncheckedIOException.class,
        () -> new DocumentRegionAnnotator(failing).annotate(document));
  }

  @Test
  void testNullGeocoderIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> new DocumentRegionAnnotator(null));
  }

  @Test
  void testNullDocumentIsRejected() {
    final DocumentRegionAnnotator annotator =
        new DocumentRegionAnnotator(GeoTestUtil.tableGeocoder(Map.of()));
    assertThrows(IllegalArgumentException.class, () -> annotator.annotate(null));
  }

  @ParameterizedTest
  @MethodSource("invalidLocationTypes")
  void testLocationTypesAreValidated(Set<String> locationTypes) {
    final Geocoder geocoder = GeoTestUtil.tableGeocoder(Map.of());
    assertThrows(IllegalArgumentException.class,
        () -> new DocumentRegionAnnotator(geocoder, locationTypes));
  }

  private static Stream<Arguments> invalidLocationTypes() {
    return Stream.of(
        Arguments.of((Set<String>) null),
        Arguments.of(Set.of()),
        Arguments.of(Collections.<String>singleton(null)),
        Arguments.of(Set.of(" ")),
        Arguments.of(Set.of(NO_BREAK_SPACE)),
        Arguments.of(Set.of(LOCATION, "")));
  }

  @ParameterizedTest
  @MethodSource("invalidVotes")
  void testRegionVoteRejectsInvalidRows(String countryCode, double share) {
    assertThrows(IllegalArgumentException.class,
        () -> new RegionVote(countryCode, share));
  }

  private static Stream<Arguments> invalidVotes() {
    return Stream.of(
        Arguments.of(null, 0.5),
        Arguments.of("", 0.5),
        Arguments.of(" ", 0.5),
        Arguments.of(NO_BREAK_SPACE, 0.5),
        Arguments.of("AU", 0.0),
        Arguments.of("AU", -0.1),
        Arguments.of("AU", 1.1),
        Arguments.of("AU", Double.NaN));
  }

  @Test
  void testRegionVoteAcceptsTheShareBounds() {
    assertEquals(1.0, new RegionVote("AU", 1.0).share(), 0.0);
    assertEquals(Double.MIN_VALUE, new RegionVote("AU", Double.MIN_VALUE).share(), 0.0);
  }
}
