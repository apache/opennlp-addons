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

import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.DocumentAnnotator;
import opennlp.tools.document.DocumentAnnotators;
import opennlp.tools.document.LayerKey;
import opennlp.tools.geo.DocumentRegionAnnotator;
import opennlp.tools.geo.RegionVote;

/**
 * Money extraction that resolves ambiguous currency symbols per document: reads the
 * region ballot of {@link DocumentRegionAnnotator}, picks the symbol table for the
 * winning country at annotation time, and provides {@link MoneyAnnotator#MONEY}, so in
 * a document that speaks from Australia {@code $} is identified as {@code AUD}.
 *
 * <p>Extractors are built through {@link CursorMoneyExtractor#forRegion(Locale)} and
 * cached per country. A document with an empty ballot, a ballot whose lead is below
 * the configured minimum margin, or a winning country without a suitable locale or
 * single currency-sign symbol, falls back to the default table.</p>
 *
 * <p>The annotator is safe to share between threads: the per-country cache is a
 * concurrent map and the extractors themselves hold no per-call state.</p>
 *
 * @since 3.0.0
 */
public class RegionAwareMoneyAnnotator implements DocumentAnnotator {

  /**
   * The minimum margin of the no-argument constructor: every non-empty ballot is
   * decisive, so the top row always picks the symbol table, exact ties included.
   */
  private static final double DEFAULT_MINIMUM_MARGIN = 0.0;

  private final CursorMoneyExtractor defaultExtractor = new CursorMoneyExtractor();
  private final Map<String, CursorMoneyExtractor> byCountry = new ConcurrentHashMap<>();
  private final double minimumMargin;

  /**
   * Initializes the annotator without a decisiveness requirement: the top row of every
   * non-empty ballot picks the symbol table, exact ties included.
   */
  public RegionAwareMoneyAnnotator() {
    this(DEFAULT_MINIMUM_MARGIN);
  }

  /**
   * Initializes the annotator with a decisiveness requirement: the ballot winner picks
   * the symbol table only when its share exceeds the runner-up's share by at least
   * {@code minimumMargin}, as decided by
   * {@link DocumentRegionAnnotator#winner(List, double)}; an indecisive ballot falls
   * back to the default table exactly like an empty one.
   *
   * @param minimumMargin The minimum lead of the top share over the runner-up share.
   *                      Must be in {@code [0, 1]}.
   * @throws IllegalArgumentException Thrown if {@code minimumMargin} is not in
   *         {@code [0, 1]}, including {@code NaN}.
   */
  public RegionAwareMoneyAnnotator(double minimumMargin) {
    if (!(minimumMargin >= 0.0 && minimumMargin <= 1.0)) {
      throw new IllegalArgumentException(
          "minimumMargin must be in [0, 1], got: " + minimumMargin);
    }
    this.minimumMargin = minimumMargin;
  }

  /**
   * {@inheritDoc}
   *
   * @param document The document to annotate. Must not be {@code null} and must carry
   *                 the {@link DocumentRegionAnnotator#REGIONS} layer.
   * @return The document with the {@link MoneyAnnotator#MONEY} layer added. Never
   *         {@code null}.
   * @throws IllegalArgumentException Thrown if {@code document} is {@code null} or the
   *         region layer is absent.
   */
  @Override
  public Document annotate(Document document) {
    DocumentAnnotators.requireLayers(document, DocumentRegionAnnotator.REGIONS);
    final List<Annotation<RegionVote>> ballot =
        document.get(DocumentRegionAnnotator.REGIONS);
    final CursorMoneyExtractor extractor =
        DocumentRegionAnnotator.winner(ballot, minimumMargin)
            .map(vote -> extractorFor(vote.countryCode()))
            .orElse(defaultExtractor);
    final List<Annotation<MoneyAmount>> mentions = new ArrayList<>();
    for (final MoneyAmount amount : extractor.extract(document.text())) {
      mentions.add(new Annotation<>(amount.span(), amount));
    }
    return document.with(MoneyAnnotator.MONEY, mentions);
  }

  /**
   * Resolves and caches the extractor for a winning country. Fallback outcomes are
   * cached too: a country with no locale whose currency symbol is a single currency
   * sign maps to the default extractor, so the locale scan runs at most once per
   * country.
   *
   * @param countryCode The winning ISO 3166-1 alpha-2 country code. Must not be
   *                    {@code null}.
   * @return The extractor for the country, or the default one if the country has no
   *         resolvable currency symbol. Never {@code null}.
   */
  private CursorMoneyExtractor extractorFor(String countryCode) {
    return byCountry.computeIfAbsent(countryCode, code -> {
      final Optional<Locale> locale = localeOf(code);
      if (locale.isEmpty()) {
        return defaultExtractor;
      }
      try {
        return CursorMoneyExtractor.forRegion(locale.get());
      } catch (IllegalArgumentException e) {
        return defaultExtractor;
      }
    });
  }

  /**
   * Picks a locale through which a country's currency symbol can be resolved, that is one
   * whose currency is written with a single currency-sign code point there. {@code en}
   * plus the country is tried first, because a country's minority-language locales often
   * write the currency with a disambiguating prefix, for example the Canadian dollar as
   * {@code CA$} rather than {@code $}. Otherwise the installed locales for the country
   * are scanned and the usable candidate with the lexicographically smallest language tag
   * wins, so the choice never depends on the order in which the runtime lists its
   * locales.
   *
   * @param countryCode The ISO 3166-1 alpha-2 country code. Must not be {@code null}.
   * @return A locale for the country whose currency symbol is a single currency sign, or
   *         an empty {@link Optional} if the country has none. Never {@code null}.
   */
  private static Optional<Locale> localeOf(String countryCode) {
    final Locale english = Locale.of("en", countryCode);
    if (hasResolvableSymbol(english)) {
      return Optional.of(english);
    }
    Locale best = null;
    for (final Locale locale : Locale.getAvailableLocales()) {
      if (countryCode.equals(locale.getCountry())
          && hasResolvableSymbol(locale)
          && (best == null
              || locale.toLanguageTag().compareTo(best.toLanguageTag()) < 0)) {
        best = locale;
      }
    }
    return Optional.ofNullable(best);
  }

  /**
   * Reports whether a locale's currency is written with a single currency-sign code
   * point there, which is what makes a symbol table override possible; a locale with no
   * currency at all, such as one naming an unassigned country code, is not resolvable.
   *
   * @param locale The locale to test. Must not be {@code null}.
   * @return {@code true} if the locale has a currency written as one currency sign.
   */
  private static boolean hasResolvableSymbol(Locale locale) {
    final Currency currency;
    try {
      currency = Currency.getInstance(locale);
    } catch (IllegalArgumentException e) {
      return false;
    }
    if (currency == null) {
      return false;
    }
    final String symbol = currency.getSymbol(locale);
    return symbol.codePointCount(0, symbol.length()) == 1
        && Character.getType(symbol.codePointAt(0)) == Character.CURRENCY_SYMBOL;
  }

  @Override
  public Set<LayerKey<?>> requires() {
    return Set.of(DocumentRegionAnnotator.REGIONS);
  }

  @Override
  public Set<LayerKey<?>> provides() {
    return Set.of(MoneyAnnotator.MONEY);
  }
}
