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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.Layers;
import opennlp.tools.util.Span;

/**
 * Utility class for testing the geocoding annotators and everything reading their
 * layers: deterministic table geocoders, minimal gazetteer entries, and documents
 * carrying an entity or region layer.
 */
public class GeoTestUtil {

  /** The population every fixture entry carries; no fixture ranks candidates by it. */
  private static final long FIXTURE_POPULATION = 1000;

  /** The entity type label the annotators treat as a location by default. */
  static final String LOCATION = "location";

  private GeoTestUtil() {
  }

  /**
   * One geocoding outcome of a table geocoder: the country a mention resolves to and
   * the confidence of that resolution.
   *
   * @param countryCode The ISO 3166-1 alpha-2 country code, or {@code null} for an
   *                    entry without a country.
   * @param confidence The resolution confidence, in {@code [0, 1]}.
   */
  record ScoredCountry(String countryCode, double confidence) {
  }

  /**
   * Builds a geocoder that resolves each known mention text to a fixed country with a
   * fixed per-mention confidence, and leaves unknown mentions unresolved.
   *
   * @param outcomes Maps a mention text to its resolution outcome. Must not be
   *                 {@code null}.
   * @return A {@link Geocoder} over the table. Never {@code null}.
   */
  static Geocoder tableGeocoder(Map<String, ScoredCountry> outcomes) {
    return (text, mentions) -> {
      final List<GeoResolution> resolutions = new ArrayList<>();
      for (final Span mention : mentions) {
        final String name =
            text.subSequence(mention.getStart(), mention.getEnd()).toString();
        final ScoredCountry outcome = outcomes.get(name);
        if (outcome != null) {
          resolutions.add(new GeoResolution(mention,
              entry(name, outcome.countryCode()), outcome.confidence()));
        }
      }
      return resolutions;
    };
  }

  /**
   * Builds a geocoder resolving mentions through a fixed name-to-country table at a
   * fixed confidence, leaving every name the table does not know unresolved.
   *
   * @param countryByName The resolvable surface forms mapped to their country codes.
   * @param confidence The confidence given to every resolution.
   * @return The table-backed geocoder. Never {@code null}.
   */
  public static Geocoder tableGeocoder(Map<String, String> countryByName, double confidence) {
    final Map<String, ScoredCountry> outcomes = new HashMap<>(countryByName.size());
    for (final Map.Entry<String, String> entry : countryByName.entrySet()) {
      outcomes.put(entry.getKey(), new ScoredCountry(entry.getValue(), confidence));
    }
    return tableGeocoder(outcomes);
  }

  /**
   * Builds a geocoder that must never be consulted: any call fails the test loudly.
   * Passing it proves a code path resolves all of its evidence without the gazetteer.
   *
   * @return A {@link Geocoder} that rejects every call. Never {@code null}.
   */
  static Geocoder unreachableGeocoder() {
    return (text, mentions) -> {
      throw new IllegalStateException("the geocoder must not be consulted");
    };
  }

  /**
   * Builds a minimal city entry whose record id equals its name.
   *
   * @param name The place name.
   * @param countryCode The ISO 3166-1 alpha-2 country code, or {@code null} when the
   *                    source assigns the place no country.
   * @return The entry. Never {@code null}.
   */
  public static GazetteerEntry entry(String name, String countryCode) {
    return entry(name, name, countryCode);
  }

  /**
   * Builds a minimal city entry, with a distinct record id for same-name places.
   *
   * @param recordId The source-scoped record id.
   * @param name The place name.
   * @param countryCode The ISO 3166-1 alpha-2 country code, or {@code null} when the
   *                    source assigns the place no country.
   * @return The entry. Never {@code null}.
   */
  public static GazetteerEntry entry(String recordId, String name, String countryCode) {
    return new GazetteerEntry("test", recordId, name, List.of(), new GeoPoint(0.0, 0.0),
        countryCode, List.of(), FIXTURE_POPULATION, GazetteerEntry.FEATURE_CLASS_CITY,
        Map.of());
  }

  /**
   * Builds a document whose entity layer marks each given mention as a location, over
   * the mention's first occurrence in the text.
   *
   * @param text The document text.
   * @param mentions The mention texts to mark. Each must occur in {@code text}.
   * @return The document with its entity layer. Never {@code null}.
   * @throws IllegalArgumentException Thrown if a mention does not occur in the text.
   */
  public static Document withLocationEntities(String text, String... mentions) {
    final List<Annotation<String>> entities = new ArrayList<>(mentions.length);
    for (final String mention : mentions) {
      final int start = text.indexOf(mention);
      if (start < 0) {
        throw new IllegalArgumentException("mention not in the text: " + mention);
      }
      entities.add(
          new Annotation<>(new Span(start, start + mention.length()), LOCATION));
    }
    return Document.of(text).with(Layers.ENTITIES, entities);
  }

  /**
   * Builds a document carrying a region ballot with a single full-share vote for one
   * country, as a downstream annotator sees it.
   *
   * @param text The document text.
   * @param countryCode The winning ISO 3166-1 alpha-2 country code.
   * @return The document with a one-row region ballot. Never {@code null}.
   */
  public static Document withRegionBallot(String text, String countryCode) {
    return Document.of(text).with(DocumentRegionAnnotator.REGIONS,
        List.of(Annotation.of(new RegionVote(countryCode, 1.0))));
  }
}
