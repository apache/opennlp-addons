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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.DocumentAnnotator;
import opennlp.tools.document.DocumentAnnotators;
import opennlp.tools.document.LayerKey;
import opennlp.tools.document.Layers;
import opennlp.tools.temporal.DocumentDateAnnotator;

/**
 * Converts {@link MoneyAnnotator#MONEY} into a target currency under
 * {@link #CONVERTED_MONEY}, retaining the original annotation spans.
 *
 * <p>The reference date is fixed at construction or read from
 * {@link DocumentDateAnnotator#DOCUMENT_DATE}. Without a date, the converted layer is
 * empty. Mentions without a rate are omitted and logged at debug level. The source
 * layer is unchanged. Document-date mode requires at most one date annotation;
 * fixed-date mode does not read that layer.</p>
 *
 * <p>Input amount spans must match their annotation spans. Provider results must use
 * the target currency and preserve those spans. Invalid results are rejected;
 * exceptions from the provider propagate. No additional rounding is applied.</p>
 *
 * @see <a href="https://www.iso.org/iso-4217-currency-codes.html">ISO 4217</a>
 * @since 3.0.0
 */
public class MoneyConversionAnnotator implements DocumentAnnotator {

  /**
   * Money mentions restated in the target currency; aligned with the money layer by
   * span, omitting mentions without a usable rate.
   */
  public static final LayerKey<MoneyAmount> CONVERTED_MONEY =
      Layers.key("money.converted", MoneyAmount.class);

  private static final Logger logger =
      LoggerFactory.getLogger(MoneyConversionAnnotator.class);

  private static final String RATES_REQUIRED = "rates must not be null";
  private static final String TARGET_REQUIRED = "target must not be null or blank";

  private final FxRates rates;
  private final String target;
  private final LocalDate asOf;

  /**
   * Initializes the annotator.
   *
   * @param rates The rate provider. Must not be {@code null}.
   * @param target The ISO 4217 code of the target currency. Must not be {@code null} or
   *               blank.
   * @param asOf The date the conversions should hold for. Must not be {@code null}.
   * @throws IllegalArgumentException Thrown if any parameter is {@code null} or
   *         {@code target} is blank.
   */
  public MoneyConversionAnnotator(FxRates rates, String target, LocalDate asOf) {
    if (rates == null) {
      throw new IllegalArgumentException(RATES_REQUIRED);
    }
    if (target == null || target.isBlank()) {
      throw new IllegalArgumentException(TARGET_REQUIRED);
    }
    if (asOf == null) {
      throw new IllegalArgumentException("asOf must not be null");
    }
    this.rates = rates;
    this.target = target;
    this.asOf = asOf;
  }

  /**
   * Initializes the annotator using each document's
   * {@link DocumentDateAnnotator#DOCUMENT_DATE} layer.
   *
   * @param rates The rate provider. Must not be {@code null}.
   * @param target The ISO 4217 code of the target currency. Must not be {@code null} or
   *               blank.
   * @throws IllegalArgumentException Thrown if {@code rates} is {@code null} or
   *         {@code target} is {@code null} or blank.
   */
  public MoneyConversionAnnotator(FxRates rates, String target) {
    if (rates == null) {
      throw new IllegalArgumentException(RATES_REQUIRED);
    }
    if (target == null || target.isBlank()) {
      throw new IllegalArgumentException(TARGET_REQUIRED);
    }
    this.rates = rates;
    this.target = target;
    this.asOf = null;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Adds validated conversions under {@link #CONVERTED_MONEY}.</p>
   *
   * @throws IllegalArgumentException Thrown if the output layer is present, an input
   *         amount span differs from its annotation span, or a provider result is null
   *         or has an unexpected currency or span, or document-date mode receives
   *         multiple date annotations.
   */
  @Override
  public Document annotate(Document document) {
    if (asOf != null) {
      DocumentAnnotators.requireLayers(document, MoneyAnnotator.MONEY);
    } else {
      DocumentAnnotators.requireLayers(document, MoneyAnnotator.MONEY,
          DocumentDateAnnotator.DOCUMENT_DATE);
    }
    if (document.layers().contains(CONVERTED_MONEY)) {
      throw new IllegalArgumentException("layer is already present: " + CONVERTED_MONEY);
    }
    final List<Annotation<MoneyAmount>> amounts = document.get(MoneyAnnotator.MONEY);
    for (final Annotation<MoneyAmount> amount : amounts) {
      if (!amount.span().equals(amount.value().span())) {
        throw new IllegalArgumentException("amount span differs from annotation span: " + amount.span());
      }
    }
    final LocalDate date;
    if (asOf != null) {
      date = asOf;
    } else {
      final List<Annotation<LocalDate>> dates = document.get(DocumentDateAnnotator.DOCUMENT_DATE);
      if (dates.size() > 1) {
        throw new IllegalArgumentException("document date layer must contain at most one annotation");
      }
      date = dates.isEmpty() ? null : dates.getFirst().value();
    }
    final List<Annotation<MoneyAmount>> converted = new ArrayList<>();
    if (date == null) {
      logger.debug("No document date available for conversion");
      return document.with(CONVERTED_MONEY, converted);
    }
    for (final Annotation<MoneyAmount> mention : amounts) {
      final Optional<MoneyAmount> restated = rates.convert(mention.value(), target, date);
      if (restated == null) {
        throw new IllegalArgumentException("rate provider returned a null result");
      }
      if (restated.isPresent()) {
        final MoneyAmount amount = restated.get();
        if (!target.equals(amount.currency())) {
          throw new IllegalArgumentException("rate provider returned currency " + amount.currency()
              + "; expected " + target);
        }
        if (!mention.span().equals(amount.span())) {
          throw new IllegalArgumentException("rate provider changed the amount span: " + mention.span());
        }
        converted.add(new Annotation<>(mention.span(), amount));
      } else {
        logger.debug("No {} rate as of {} for mention {}", target, date, mention.value());
      }
    }
    return document.with(CONVERTED_MONEY, converted);
  }

  /** {@inheritDoc} */
  @Override
  public Set<LayerKey<?>> requires() {
    return asOf != null
        ? Set.of(MoneyAnnotator.MONEY)
        : Set.of(MoneyAnnotator.MONEY, DocumentDateAnnotator.DOCUMENT_DATE);
  }

  /** {@inheritDoc} */
  @Override
  public Set<LayerKey<?>> provides() {
    return Set.of(CONVERTED_MONEY);
  }
}
