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
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.DocumentAnnotator;
import opennlp.tools.document.DocumentAnnotators;
import opennlp.tools.document.LayerKey;
import opennlp.tools.document.Layers;
import opennlp.tools.util.Span;
import opennlp.tools.util.StringUtil;

/**
 * Derives the countries a document is about from its location entities and provides
 * {@link #REGIONS}, a document-scoped layer of span-less {@link RegionVote} rows ranked
 * by share.
 *
 * <p>Every location mention goes through the {@link Geocoder} and votes for its
 * entry's country weighted by the resolution confidence. A mention whose text is an
 * English country name, for example {@code Australia}, additionally matches a name
 * table built from JDK locale data, because place gazetteers often carry no country
 * entries. When the geocoder resolves such a mention to a different country, the
 * geocoder's resolution wins and the name vote is dropped, so {@code Georgia} next to
 * {@code Atlanta} counts for the United States rather than the Caucasus republic; when
 * the geocoder returns nothing for the mention or agrees with the name, the mention
 * casts the single name-table vote. A mention that resolves to nothing and names no
 * country does not vote, and a document without usable evidence gets an empty
 * layer.</p>
 *
 * <p>The annotator holds no per-call state and is as thread-safe as its geocoder.</p>
 *
 * @since 3.0.0
 */
public class DocumentRegionAnnotator implements DocumentAnnotator {

  /**
   * The document's region ballot: a document-scoped layer with one span-less annotation
   * per candidate country, ordered by descending share, with equal shares ranked by
   * ascending country code so the order is deterministic.
   */
  public static final LayerKey<RegionVote> REGIONS =
      Layers.documentKey("regions", RegionVote.class);

  /**
   * The vote weight of a direct country-name mention. It sits near the top of the
   * geocoder confidence range, so one country name counts about as much as one
   * confidently resolved city and never outvotes two independent mentions on its own.
   */
  private static final double COUNTRY_NAME_WEIGHT = 0.95;

  /** The entity type label treated as a location unless the caller names others. */
  private static final String DEFAULT_LOCATION_TYPE = "location";

  /**
   * The right single quotation mark (U+2019), which CLDR display names use where real
   * text often writes the ASCII apostrophe; lookups fold it to the apostrophe.
   */
  private static final char RIGHT_SINGLE_QUOTATION_MARK = '’';

  private static final Map<String, String> COUNTRY_NAMES = countryNames();

  private final Geocoder geocoder;
  private final Set<String> locationTypes;

  /**
   * Initializes the annotator for entities typed {@value #DEFAULT_LOCATION_TYPE}.
   *
   * @param geocoder The geocoder resolving location mentions. Must not be {@code null}.
   * @throws IllegalArgumentException Thrown if {@code geocoder} is {@code null}.
   */
  public DocumentRegionAnnotator(Geocoder geocoder) {
    this(geocoder, Set.of(DEFAULT_LOCATION_TYPE));
  }

  /**
   * Initializes the annotator.
   *
   * @param geocoder The geocoder resolving location mentions. Must not be {@code null}.
   * @param locationTypes The entity type labels treated as locations, matched
   *                      case-insensitively. Must not be {@code null} or empty.
   * @throws IllegalArgumentException Thrown if a parameter is {@code null} or
   *         {@code locationTypes} is empty.
   */
  public DocumentRegionAnnotator(Geocoder geocoder, Set<String> locationTypes) {
    if (geocoder == null) {
      throw new IllegalArgumentException("geocoder must not be null");
    }
    if (locationTypes == null || locationTypes.isEmpty()) {
      throw new IllegalArgumentException("locationTypes must not be null or empty");
    }
    this.geocoder = geocoder;
    final Set<String> lowered = new HashSet<>(locationTypes.size());
    for (final String type : locationTypes) {
      if (type == null || StringUtil.isBlank(type)) {
        throw new IllegalArgumentException("locationTypes must not contain blank entries");
      }
      lowered.add(StringUtil.toLowerCase(type));
    }
    this.locationTypes = Set.copyOf(lowered);
  }

  /**
   * {@inheritDoc}
   *
   * @param document The document to annotate. Must not be {@code null} and must carry
   *                 the {@link Layers#ENTITIES} layer.
   * @return The document with the {@link #REGIONS} layer added. Never {@code null}.
   * @throws IllegalArgumentException Thrown if {@code document} is {@code null} or the
   *         entity layer is absent.
   * @throws UncheckedIOException Thrown if the {@link Geocoder} fails with an
   *         {@link IOException}.
   */
  @Override
  public Document annotate(Document document) {
    DocumentAnnotators.requireLayers(document, Layers.ENTITIES);
    final CharSequence text = document.text();
    final Map<String, Double> weights = new HashMap<>();
    final Map<Span, String> nameVotes = new HashMap<>();
    final List<Span> toGeocode = new ArrayList<>();
    for (final Annotation<String> entity : document.get(Layers.ENTITIES)) {
      if (!locationTypes.contains(StringUtil.toLowerCase(entity.value()))) {
        continue;
      }
      final Span span = entity.span();
      final String mention = text.subSequence(span.getStart(), span.getEnd()).toString();
      final String countryCode = countryCodeOf(mention);
      if (countryCode != null) {
        nameVotes.put(span, countryCode);
      }
      toGeocode.add(span);
    }
    if (!toGeocode.isEmpty()) {
      final List<GeoResolution> resolutions;
      try {
        resolutions = geocoder.resolve(text, toGeocode);
      } catch (IOException e) {
        throw new UncheckedIOException("Unable to geocode the document's location mentions", e);
      }
      for (final GeoResolution resolution : resolutions) {
        final String countryCode = resolution.entry().countryCode();
        if (countryCode == null) {
          continue;
        }
        final String namedCountry = nameVotes.get(resolution.mention());
        if (namedCountry == null) {
          weights.merge(countryCode, resolution.confidence(), Double::sum);
        } else if (!countryCode.equals(namedCountry)) {
          // The geocoder places the mention in another country, for example the US
          // state of Georgia; its resolution wins and the name vote is dropped.
          nameVotes.remove(resolution.mention());
          weights.merge(countryCode, resolution.confidence(), Double::sum);
        }
        // An agreeing resolution changes nothing: the mention casts its name vote once.
      }
    }
    for (final String countryCode : nameVotes.values()) {
      weights.merge(countryCode, COUNTRY_NAME_WEIGHT, Double::sum);
    }
    return document.with(REGIONS, ballot(weights));
  }

  /**
   * Turns the weight sums into a ranked ballot: each country's share is its weight over
   * the weight total, so shares sum to one, and rows are ordered by descending share
   * with ties broken by ascending country code. The rows carry no spans, since the
   * ballot describes the document as a whole. A country whose weight sums to zero
   * carries no evidence and gets no row, so a ballot without positive weights is empty
   * rather than a set of undefined shares.
   *
   * @param weights Maps a country code to the summed weight of its evidence. Must not be
   *                {@code null}.
   * @return The ranked ballot rows. Never {@code null}.
   */
  private static List<Annotation<RegionVote>> ballot(Map<String, Double> weights) {
    double total = 0.0;
    final List<Map.Entry<String, Double>> ranked = new ArrayList<>(weights.size());
    for (final Map.Entry<String, Double> entry : weights.entrySet()) {
      if (entry.getValue() > 0.0) {
        total += entry.getValue();
        ranked.add(entry);
      }
    }
    ranked.sort(Map.Entry.<String, Double>comparingByValue().reversed()
        .thenComparing(Map.Entry.comparingByKey()));
    final List<Annotation<RegionVote>> votes = new ArrayList<>(ranked.size());
    for (final Map.Entry<String, Double> entry : ranked) {
      votes.add(Annotation.of(new RegionVote(entry.getKey(), entry.getValue() / total)));
    }
    return votes;
  }

  /**
   * Elects a ballot's winner only when the election is decisive: the top row wins when
   * its share exceeds the runner-up's share by at least {@code minMargin}. A one-row
   * ballot has a runner-up share of zero. An empty ballot, or a lead below the margin,
   * yields no winner, so a caller can fall back instead of acting on what is
   * effectively a coin flip between near-tied countries.
   *
   * @param ballot The ballot rows ordered by descending share, as provided under
   *               {@link #REGIONS}. Must not be {@code null}.
   * @param minMargin The minimum lead of the top share over the runner-up share. Must
   *                  be in {@code [0, 1]}; {@code 0.0} always elects the top row of a
   *                  non-empty ballot, including on an exact tie.
   * @return The winning vote, or an empty {@link Optional} if the ballot is empty or
   *         the lead is below the margin. Never {@code null}.
   * @throws IllegalArgumentException Thrown if {@code ballot} is {@code null}, or
   *         {@code minMargin} is not in {@code [0, 1]}, including {@code NaN}.
   */
  public static Optional<RegionVote> winner(List<Annotation<RegionVote>> ballot,
      double minMargin) {
    if (ballot == null) {
      throw new IllegalArgumentException("ballot must not be null");
    }
    if (!(minMargin >= 0.0 && minMargin <= 1.0)) {
      throw new IllegalArgumentException("minMargin must be in [0, 1], got: " + minMargin);
    }
    if (ballot.isEmpty()) {
      return Optional.empty();
    }
    final RegionVote top = ballot.get(0).value();
    final double runnerUp = ballot.size() > 1 ? ballot.get(1).value().share() : 0.0;
    return top.share() - runnerUp >= minMargin ? Optional.of(top) : Optional.empty();
  }

  @Override
  public Set<LayerKey<?>> requires() {
    return Set.of(Layers.ENTITIES);
  }

  @Override
  public Set<LayerKey<?>> provides() {
    return Set.of(REGIONS);
  }

  /**
   * Looks up the country a mention names, tolerating the spellings real text uses: the
   * mention is normalized like the table keys, and when the normalized form does not
   * match, a diacritic-stripped variant is tried, so both {@code Côte d'Ivoire} and
   * {@code Cote d'Ivoire} match {@code CI} and {@code México} matches {@code MX}.
   * Aliases such as {@code USA} or {@code UK} are out of scope for this lookup and do
   * not match.
   *
   * @param mention The mention text. Must not be {@code null}.
   * @return The ISO 3166-1 alpha-2 code of the named country, or {@code null} if the
   *         mention names no country.
   */
  private static String countryCodeOf(String mention) {
    final String normalized = normalize(mention);
    final String code = COUNTRY_NAMES.get(normalized);
    return code != null ? code : COUNTRY_NAMES.get(stripDiacritics(normalized));
  }

  /**
   * Normalizes a country name for the lookup: NFKC composition, folding the right
   * single quotation mark to the ASCII apostrophe, and lowercasing.
   *
   * @param name The name to normalize. Must not be {@code null}.
   * @return The normalized name. Never {@code null}.
   */
  private static String normalize(String name) {
    return StringUtil.toLowerCase(Normalizer.normalize(name, Normalizer.Form.NFKC)
        .replace(RIGHT_SINGLE_QUOTATION_MARK, '\''));
  }

  /**
   * Strips combining diacritical marks from a normalized name by NFD decomposition and
   * removal of non-spacing marks, so {@code côte d'ivoire} becomes
   * {@code cote d'ivoire}.
   *
   * @param name The normalized name. Must not be {@code null}.
   * @return The name without diacritics. Never {@code null}.
   */
  private static String stripDiacritics(String name) {
    final String decomposed = Normalizer.normalize(name, Normalizer.Form.NFD);
    final StringBuilder stripped = new StringBuilder(decomposed.length());
    for (int i = 0; i < decomposed.length(); i++) {
      final char c = decomposed.charAt(i);
      if (Character.getType(c) != Character.NON_SPACING_MARK) {
        stripped.append(c);
      }
    }
    return stripped.toString();
  }

  /**
   * Builds the country-name lookup from JDK locale data. Codes for which the JDK has no
   * distinct display name are skipped rather than mapped to themselves. Each name is
   * indexed under its {@link #normalize(String) normalized} form and additionally under
   * its {@link #stripDiacritics(String) diacritic-stripped} variant; a stripped variant
   * never displaces another name's exact normalized form.
   *
   * @return Normalized English country display names mapped to their ISO 3166-1 alpha-2
   *         codes. Never {@code null}.
   */
  private static Map<String, String> countryNames() {
    final Map<String, String> names = new HashMap<>();
    for (final String code : Locale.getISOCountries()) {
      final String name = Locale.of("", code).getDisplayCountry(Locale.ENGLISH);
      if (!name.isEmpty() && !name.equals(code)) {
        names.put(normalize(name), code);
      }
    }
    for (final String code : Locale.getISOCountries()) {
      final String name = Locale.of("", code).getDisplayCountry(Locale.ENGLISH);
      if (!name.isEmpty() && !name.equals(code)) {
        names.putIfAbsent(stripDiacritics(normalize(name)), code);
      }
    }
    return Map.copyOf(names);
  }
}
