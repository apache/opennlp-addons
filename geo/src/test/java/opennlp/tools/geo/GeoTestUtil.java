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
 * Shared fixtures of the region ballot tests: deterministic table geocoders, minimal
 * gazetteer entries, and documents carrying a location entity layer.
 */
final class GeoTestUtil {

  /** The entity type label the region annotator treats as a location by default. */
  static final String LOCATION = "location";

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
   * Builds a geocoder that resolves a fixed name-to-country table at one shared
   * confidence and leaves unknown mentions unresolved.
   *
   * @param countryByName Maps a mention text to its country code. Must not be
   *                      {@code null}.
   * @param confidence The confidence every resolution reports, in {@code [0, 1]}.
   * @return A {@link Geocoder} over the table. Never {@code null}.
   */
  static Geocoder tableGeocoder(Map<String, String> countryByName, double confidence) {
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
   * Builds a minimal city entry for a country; only the country code matters for the
   * region ballot.
   *
   * @param name The city name. Must not be {@code null}.
   * @param countryCode The ISO 3166-1 alpha-2 country code, or {@code null} for an
   *                    entry without a country.
   * @return A {@link GazetteerEntry} for the city. Never {@code null}.
   */
  static GazetteerEntry entry(String name, String countryCode) {
    return new GazetteerEntry("test", name, name, List.of(), new GeoPoint(0.0, 0.0),
        countryCode, List.of(), 1000, GazetteerEntry.FEATURE_CLASS_CITY, Map.of());
  }

  /**
   * Builds a document whose entity layer marks each given mention as a location.
   *
   * @param text The document text. Must not be {@code null}.
   * @param mentions The mention texts to mark. Each must occur in {@code text}.
   * @return A {@link Document} with an entity layer. Never {@code null}.
   * @throws IllegalArgumentException Thrown if a mention does not occur in the text.
   */
  static Document withLocations(String text, String... mentions) {
    final List<Annotation<String>> entities = new ArrayList<>();
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

  private GeoTestUtil() {
    // Not instantiated; this class provides static test fixtures only.
  }
}
