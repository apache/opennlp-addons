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

package opennlp.tools.numeric;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import opennlp.tools.document.DocumentAnalyzer;
import opennlp.tools.document.DocumentAnnotator;
import opennlp.tools.extraction.NumberNotation;
import opennlp.tools.money.CursorMoneyExtractor;
import opennlp.tools.money.MoneyAnnotator;
import opennlp.tools.money.MoneyConversionAnnotator;
import opennlp.tools.quantity.CursorQuantityExtractor;
import opennlp.tools.quantity.QuantityAnnotator;
import opennlp.tools.temporal.CursorTemporalExtractor;
import opennlp.tools.temporal.DocumentDateAnnotator;
import opennlp.tools.temporal.TemporalAnnotator;

/**
 * Builds pipelines for currency amounts, quantities, calendar mentions, or their combination.
 *
 * <p>Temporal pipelines run {@link TemporalAnnotator} before {@link DocumentDateAnnotator}.
 * Relative expressions use a reference date from the text or a configured date.</p>
 *
 * <p>Regional variants select number notation and currency-symbol defaults from JDK locale
 * data through
 * {@link CursorMoneyExtractor#forRegion(Locale)} and
 * {@link NumberNotation#forLocale(Locale)}. Locale selection does not translate
 * month names or relative-date words.</p>
 *
 * <p>The analyzers use stateless extractors and can be shared between threads. To add steps
 * such as {@link MoneyConversionAnnotator}, start with the modifiable list from
 * {@link #annotators()} or {@link #annotators(Locale)}. An extended pipeline requires
 * concurrent-call support from the added annotators and providers.</p>
 *
 * @since 3.0.0
 */
public final class NumericPacks {

  /** Prevents instances of this factory class. */
  private NumericPacks() {
  }

  /**
   * Money amounts, read in {@link NumberNotation#LATIN_US} with the default symbol table.
   *
   * @return A new analyzer providing {@link MoneyAnnotator#MONEY}. Never {@code null}.
   */
  public static DocumentAnalyzer money() {
    return DocumentAnalyzer.builder()
        .add(new MoneyAnnotator(new CursorMoneyExtractor()))
        .build();
  }

  /**
   * Currency amounts using the notation and symbol defaults selected for a region.
   *
   * @param region A locale with a country component. Must not be {@code null} and must
   *               name a region with a currency.
   * @return A new analyzer providing {@link MoneyAnnotator#MONEY}. Never {@code null}.
   * @throws IllegalArgumentException Thrown if {@code region} is {@code null} or has no
   *         currency.
   */
  public static DocumentAnalyzer money(Locale region) {
    return DocumentAnalyzer.builder()
        .add(new MoneyAnnotator(CursorMoneyExtractor.forRegion(region)))
        .build();
  }

  /**
   * Measured quantities and percentages, read in {@link NumberNotation#LATIN_US} with the
   * default unit set.
   *
   * @return A new analyzer providing {@link QuantityAnnotator#QUANTITIES}. Never
   *         {@code null}.
   */
  public static DocumentAnalyzer quantity() {
    return DocumentAnalyzer.builder()
        .add(new QuantityAnnotator(new CursorQuantityExtractor()))
        .build();
  }

  /**
   * Measured quantities and percentages, read in the number notation of a region.
   *
   * @param region The locale whose notation the document follows. Must not be
   *               {@code null}.
   * @return A new analyzer providing {@link QuantityAnnotator#QUANTITIES}. Never
   *         {@code null}.
   * @throws IllegalArgumentException Thrown if {@code region} is {@code null}.
   */
  public static DocumentAnalyzer quantity(Locale region) {
    return DocumentAnalyzer.builder()
        .add(new QuantityAnnotator(
            new CursorQuantityExtractor(NumberNotation.forLocale(region))))
        .build();
  }

  /**
   * Calendar mentions and a document date, using an absolute day in the text to resolve
   * relative expressions.
   *
   * @return A new analyzer providing {@link TemporalAnnotator#TEMPORALS} and
   *         {@link DocumentDateAnnotator#DOCUMENT_DATE}. Never {@code null}.
   */
  public static DocumentAnalyzer temporal() {
    return DocumentAnalyzer.builder()
        .add(new TemporalAnnotator(new CursorTemporalExtractor()))
        .add(new DocumentDateAnnotator())
        .build();
  }

  /**
   * Calendar mentions with a configured reference date for relative expressions. The
   * document-date layer requires an absolute day mention in the text.
   *
   * @param reference The date relative expressions resolve against. Must not be
   *                  {@code null}.
   * @return A new analyzer providing {@link TemporalAnnotator#TEMPORALS} and
   *         {@link DocumentDateAnnotator#DOCUMENT_DATE}. Never {@code null}.
   * @throws IllegalArgumentException Thrown if {@code reference} is {@code null}.
   */
  public static DocumentAnalyzer temporal(LocalDate reference) {
    return DocumentAnalyzer.builder()
        .add(new TemporalAnnotator(new CursorTemporalExtractor(), reference))
        .add(new DocumentDateAnnotator())
        .build();
  }

  /**
   * Calendar mentions, the document date, currency amounts, and quantities.
   *
   * @return A new analyzer providing all four numeric layers. Never {@code null}.
   */
  public static DocumentAnalyzer fullPipeline() {
    return analyzer(annotators());
  }

  /**
   * All numeric layers using a region's selected notation and currency-symbol defaults.
   *
   * @param region A locale with a country component. Must not be {@code null} and must
   *               name a region with a currency.
   * @return A new analyzer providing all four numeric layers. Never {@code null}.
   * @throws IllegalArgumentException Thrown if {@code region} is {@code null} or has no
   *         currency.
   */
  public static DocumentAnalyzer fullPipeline(Locale region) {
    return analyzer(annotators(region));
  }

  /**
   * The annotators of {@link #fullPipeline()} in execution order. Additional steps such
   * as {@link MoneyConversionAnnotator} can be appended to the returned list.
   *
   * @return A new, modifiable list of annotators in execution order. Never {@code null}.
   */
  public static List<DocumentAnnotator> annotators() {
    return pipeline(new CursorMoneyExtractor(), new CursorQuantityExtractor());
  }

  /**
   * The annotators of {@link #fullPipeline(Locale)} in execution order.
   *
   * @param region A locale with a country component. Must not be {@code null} and must
   *               name a region with a currency.
   * @return A new, modifiable list of annotators in execution order. Never {@code null}.
   * @throws IllegalArgumentException Thrown if {@code region} is {@code null} or has no
   *         currency.
   */
  public static List<DocumentAnnotator> annotators(Locale region) {
    if (region == null) {
      throw new IllegalArgumentException("region must not be null");
    }
    return pipeline(CursorMoneyExtractor.forRegion(region),
        new CursorQuantityExtractor(NumberNotation.forLocale(region)));
  }

  /**
   * Assembles the full pipeline using the selected extractors.
   *
   * @param money The money extractor to use. Must not be {@code null}.
   * @param quantity The quantity extractor to use. Must not be {@code null}.
   * @return The annotators in execution order. Never {@code null}.
   */
  private static List<DocumentAnnotator> pipeline(CursorMoneyExtractor money,
      CursorQuantityExtractor quantity) {
    return new ArrayList<>(List.of(
        new TemporalAnnotator(new CursorTemporalExtractor()),
        new DocumentDateAnnotator(),
        new MoneyAnnotator(money),
        new QuantityAnnotator(quantity)));
  }

  /**
   * Builds an analyzer from annotators in execution order.
   *
   * @param annotators The annotators to run. Must not be {@code null} or empty.
   * @return The analyzer. Never {@code null}.
   */
  private static DocumentAnalyzer analyzer(List<DocumentAnnotator> annotators) {
    final DocumentAnalyzer.Builder builder = DocumentAnalyzer.builder();
    for (final DocumentAnnotator annotator : annotators) {
      builder.add(annotator);
    }
    return builder.build();
  }
}
