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
import opennlp.tools.util.normalizer.EmojiFlags;

/**
 * Derives the countries a document is about from its location entities and provides
 * {@link #REGIONS}, a document-scoped layer of span-less {@link RegionVote} rows ranked
 * by share.
 *
 * <p>Three kinds of evidence vote. A location entity resolved by the
 * {@link GeocodeAnnotator} votes for its entry's country weighted by the resolution
 * confidence, so a coherence-aware geocoder makes the ballot sharper. A location
 * entity the geocoder left unresolved still votes when its text is an English country
 * name, recognized through JDK locale data, which covers gazetteers that carry no
 * country entries. Name matching folds the spellings real text uses: NFKC
 * normalization, the CLDR apostrophe (U+2019) folded to the ASCII apostrophe, and a
 * diacritic-stripped fallback; aliases such as {@code USA} are out of scope. A
 * mention resolved to a country never consults the name table, which is what makes
 * {@code Georgia} next to {@code Atlanta} count for the United States rather than the
 * Caucasus republic. A country flag emoji anywhere in the text, a regional indicator
 * pair naming an assigned ISO 3166-1 code, votes for its country and needs no entity
 * layer support at all; subdivision tag-sequence flags name no country and cast no
 * vote. Mentions with no kind of evidence do not vote; a document without usable
 * evidence gets an empty layer.</p>
 *
 * <p>When the geocoder ranks several candidates for one mention, the locations layer holds
 * them in the geocoder's order, best first, and the mention votes with that best candidate
 * alone. The order is the geocoder's ranking, which is authoritative: confidence is
 * resolver-defined and not comparable across implementations, so it ranks nothing by
 * itself.</p>
 *
 * <p>The annotator holds no per-call state and is safe to share between threads.</p>
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

  /**
   * The weight of a country flag emoji, deliberately equal to
   * {@link #COUNTRY_NAME_WEIGHT} because writing a flag is as explicit a signal as
   * writing the country's name.
   */
  private static final double FLAG_WEIGHT = COUNTRY_NAME_WEIGHT;

  /** The entity type label treated as a location unless the caller names others. */
  private static final String DEFAULT_LOCATION_TYPE = "location";

  /**
   * The right single quotation mark (U+2019), which CLDR display names use where real
   * text often writes the ASCII apostrophe; lookups fold it to the apostrophe.
   */
  private static final char RIGHT_SINGLE_QUOTATION_MARK = '’';

  private static final Map<String, String> COUNTRY_NAMES = countryNames();

  private static final Set<String> ISO_COUNTRIES = Set.of(Locale.getISOCountries());

  private static final int FIRST_REGIONAL_INDICATOR = 0x1F1E6;
  private static final int LAST_REGIONAL_INDICATOR = 0x1F1FF;

  private final Set<String> locationTypes;

  /**
   * Initializes the annotator for entities typed {@value #DEFAULT_LOCATION_TYPE}.
   */
  public DocumentRegionAnnotator() {
    this(Set.of(DEFAULT_LOCATION_TYPE));
  }

  /**
   * Initializes the annotator.
   *
   * @param locationTypes The entity type labels treated as locations, matched
   *                      case-insensitively. Must not be {@code null} or empty.
   * @throws IllegalArgumentException Thrown if {@code locationTypes} is {@code null},
   *         empty, or contains a blank entry.
   */
  public DocumentRegionAnnotator(Set<String> locationTypes) {
    if (locationTypes == null || locationTypes.isEmpty()) {
      throw new IllegalArgumentException("locationTypes must not be null or empty");
    }
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
   * Annotates the document with the {@link #REGIONS} ballot.
   *
   * <p>Every location entity casts at most one vote. A mention is matched to the
   * locations layer by exact span and votes for its best candidate's country with that
   * candidate's confidence; a mention with no candidates, or whose best candidate
   * carries no country code, falls back to the name table and votes as a country name
   * when its text is one. A mention with neither kind of evidence casts no vote.
   * Country flag emoji vote directly from the text, independently of any entity.</p>
   *
   * <p>The required layers must be present, but they may be empty: a document with a
   * present but empty locations layer is a document nothing geocoded, and its country-name
   * and flag evidence still votes. An absent required layer is a pipeline error rather
   * than an evidence-free document, because a missing {@link GeocodeAnnotator} stage would
   * otherwise drop every geocoded vote silently.</p>
   *
   * @param document The document to annotate. Must not be {@code null} and must carry the
   *                 {@link Layers#ENTITIES} and {@link GeocodeAnnotator#LOCATIONS} layers.
   * @return A new {@link Document} with the {@link #REGIONS} layer added. Never
   *         {@code null}.
   * @throws IllegalArgumentException Thrown if {@code document} is {@code null}, the entity
   *         layer or the locations layer is absent, or the document already carries the
   *         {@link #REGIONS} layer.
   */
  @Override
  public Document annotate(Document document) {
    if (document == null) {
      throw new IllegalArgumentException("document must not be null");
    }
    DocumentAnnotators.requireLayers(document, Layers.ENTITIES, GeocodeAnnotator.LOCATIONS);
    final CharSequence text = document.text();
    // The layer holds a mention's candidates in the geocoder's ranking, best first, so
    // the first entry for a span is the one that votes.
    final Map<Long, GeoResolution> resolutionsBySpan = new HashMap<>();
    for (final Annotation<GeoResolution> location : document.get(GeocodeAnnotator.LOCATIONS)) {
      resolutionsBySpan.putIfAbsent(GeocodeAnnotator.spanKey(location.span()), location.value());
    }
    final Map<String, Double> weights = new HashMap<>();
    for (final Annotation<String> entity : document.get(Layers.ENTITIES)) {
      if (!locationTypes.contains(StringUtil.toLowerCase(entity.value()))) {
        continue;
      }
      final Span span = entity.span();
      final GeoResolution resolution = resolutionsBySpan.get(GeocodeAnnotator.spanKey(span));
      if (resolution != null && resolution.entry().countryCode() != null) {
        weights.merge(resolution.entry().countryCode(), resolution.confidence(), Double::sum);
        continue;
      }
      // A countryless entry casts no vote of its own and cannot silence its mention,
      // so the name table still gets its say.
      final String mention = text.subSequence(span.getStart(), span.getEnd()).toString();
      final String countryCode = countryCodeOf(mention);
      if (countryCode != null) {
        weights.merge(countryCode, COUNTRY_NAME_WEIGHT, Double::sum);
      }
    }
    flagVotes(text, weights);
    return document.with(REGIONS, ballot(weights));
  }

  /**
   * Adds one vote per country flag emoji in the text. Consecutive flags are segmented
   * left to right, so two adjacent flags never form a spurious middle pair. A lone
   * regional indicator with no partner to its right casts no vote, and a pair decoding
   * to a code outside the assigned ISO countries is skipped the same way.
   *
   * @param text The document text.
   * @param weights The running weight sums by country code.
   */
  private static void flagVotes(CharSequence text, Map<String, Double> weights) {
    int i = 0;
    while (i < text.length()) {
      final int first = Character.codePointAt(text, i);
      final int width = Character.charCount(first);
      if (isRegionalIndicator(first) && i + width < text.length()) {
        final int second = Character.codePointAt(text, i + width);
        if (isRegionalIndicator(second)) {
          final int end = i + width + Character.charCount(second);
          final String code = EmojiFlags.isoRegion(text.subSequence(i, end)).orElse(null);
          if (code != null && ISO_COUNTRIES.contains(code)) {
            weights.merge(code, FLAG_WEIGHT, Double::sum);
          }
          i = end;
          continue;
        }
      }
      i += width;
    }
  }

  /**
   * Determines whether a code point is a regional indicator symbol, the building block
   * of a flag emoji. Kept local because the equivalent check in {@code EmojiFlags} is
   * private to the decoder there.
   *
   * @param codePoint The code point to examine.
   * @return {@code true} if the code point is a regional indicator symbol.
   */
  private static boolean isRegionalIndicator(int codePoint) {
    return codePoint >= FIRST_REGIONAL_INDICATOR && codePoint <= LAST_REGIONAL_INDICATOR;
  }

  /**
   * Turns the weight sums into the ranked ballot: each country's share is its weight
   * over the total, and rows are ordered by descending share with ties broken by
   * ascending country code so the ranking is deterministic. The rows carry no spans,
   * since the ballot describes the document as a whole.
   *
   * <p>A country whose weight sums to zero carries no evidence, which a resolution at
   * confidence {@code 0.0} is entitled to report, so it casts no vote and gets no row.
   * An empty weight map, or one without a positive weight, produces an empty ballot
   * rather than a set of undefined shares.</p>
   *
   * @param weights Maps a country code to the summed weight of its evidence. Must not be
   *                {@code null}.
   * @return The ballot annotations in rank order. Never {@code null}; empty when no
   *         country has evidence.
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
    return Set.of(Layers.ENTITIES, GeocodeAnnotator.LOCATIONS);
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
