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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.DocumentAnalyzer;
import opennlp.tools.document.DocumentAnnotator;
import opennlp.tools.extraction.NumberNotation;
import opennlp.tools.money.CursorMoneyExtractor;
import opennlp.tools.money.EcbFxRates;
import opennlp.tools.money.FxRates;
import opennlp.tools.money.MoneyAmount;
import opennlp.tools.money.MoneyAnnotator;
import opennlp.tools.money.MoneyConversionAnnotator;
import opennlp.tools.quantity.CursorQuantityExtractor;
import opennlp.tools.quantity.Quantity;
import opennlp.tools.quantity.QuantityAnnotator;
import opennlp.tools.temporal.CursorTemporalExtractor;
import opennlp.tools.temporal.DocumentDateAnnotator;
import opennlp.tools.temporal.TemporalAnnotator;
import opennlp.tools.temporal.TemporalExpression;
import opennlp.tools.util.Span;

/**
 * Tests the numeric manual examples.
 */
public class NumericManualExampleTest {

  /**
   * Extends a regional factory pipeline with conversion using synthetic rates.
   *
   * @throws IOException If reading the table fails.
   */
  @Test
  void testExtendedPackListing() throws IOException {
    final String csv = "Date,USD\n2026-07-14,1.25\n";
    final FxRates rates = EcbFxRates.load(
        new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    final List<DocumentAnnotator> annotators = NumericPacks.annotators(Locale.GERMANY);
    annotators.add(new MoneyConversionAnnotator(rates, "USD"));
    final DocumentAnalyzer.Builder builder = DocumentAnalyzer.builder();
    annotators.forEach(builder::add);
    final String text = "2026-07-14: paid EUR 10 for 3 kg yesterday.";
    final Document document = builder.build().analyze(text);

    Assertions.assertEquals(Set.of(MoneyAnnotator.MONEY, QuantityAnnotator.QUANTITIES,
        TemporalAnnotator.TEMPORALS, DocumentDateAnnotator.DOCUMENT_DATE,
        MoneyConversionAnnotator.CONVERTED_MONEY), document.layers());
    final List<Annotation<MoneyAmount>> source = document.get(MoneyAnnotator.MONEY);
    final List<Annotation<MoneyAmount>> converted =
        document.get(MoneyConversionAnnotator.CONVERTED_MONEY);
    Assertions.assertEquals(1, source.size());
    Assertions.assertEquals(1, converted.size());
    Assertions.assertEquals("EUR", source.getFirst().value().currency());
    Assertions.assertEquals(0, BigDecimal.TEN.compareTo(source.getFirst().value().amount()));
    Assertions.assertEquals("USD", converted.getFirst().value().currency());
    Assertions.assertEquals(0, new BigDecimal("12.5")
        .compareTo(converted.getFirst().value().amount()));
    Assertions.assertEquals(new Span(17, 23), source.getFirst().span());
    Assertions.assertEquals(source.getFirst().span(), converted.getFirst().span());
    Assertions.assertEquals(converted.getFirst().span(), converted.getFirst().value().span());
    Assertions.assertEquals("EUR 10", converted.getFirst().span().getCoveredText(text).toString());
    Assertions.assertEquals(LocalDate.of(2026, 7, 14),
        document.get(DocumentDateAnnotator.DOCUMENT_DATE).getFirst().value());
    Assertions.assertEquals(2, document.get(TemporalAnnotator.TEMPORALS).size());
    Assertions.assertEquals("2026-07-13",
        document.get(TemporalAnnotator.TEMPORALS).getLast().value().value());
    Assertions.assertEquals(1, document.get(QuantityAnnotator.QUANTITIES).size());
    Assertions.assertEquals(0, BigDecimal.valueOf(3)
        .compareTo(document.get(QuantityAnnotator.QUANTITIES).getFirst().value().value()));
    Assertions.assertEquals("kg", document.get(QuantityAnnotator.QUANTITIES).getFirst().value().unit());
  }

  /**
   * The dateline resolves a relative date; both expressions retain their text spans.
   */
  @Test
  void testRelativeDateListing() {
    final String text = "Berlin, 14 July 2026. The buyer paid yesterday.";

    final Document document = NumericPacks.temporal().analyze(text);

    final List<Annotation<TemporalExpression>> temporals =
        document.get(TemporalAnnotator.TEMPORALS);
    Assertions.assertEquals(2, temporals.size());
    Assertions.assertEquals("2026-07-14", temporals.get(0).value().value());
    Assertions.assertEquals(new Span(8, 20), temporals.get(0).span());
    Assertions.assertEquals("2026-07-13", temporals.get(1).value().value());
    Assertions.assertEquals("yesterday",
        temporals.get(1).span().getCoveredText(text).toString());

    final List<Annotation<LocalDate>> dates =
        document.get(DocumentDateAnnotator.DOCUMENT_DATE);
    Assertions.assertEquals(LocalDate.of(2026, 7, 14), dates.get(0).value());
    Assertions.assertEquals(new Span(8, 20), dates.get(0).span());
  }

  /**
   * A relative date without a reference date has no result.
   */
  @Test
  void testRelativeWithoutADatelineListing() {
    final Document document = NumericPacks.temporal().analyze("The buyer paid yesterday.");

    Assertions.assertEquals(List.of(), document.get(TemporalAnnotator.TEMPORALS));
  }

  /**
   * The German pipeline accepts European notation; the default pipeline rejects it.
   */
  @Test
  void testNumberNotationListing() {
    final String text = "Der Kaufer zahlte 2.400.000 EUR fur 1.250,5 GB Speicher.";

    final Document german = NumericPacks.fullPipeline(Locale.GERMANY).analyze(text);
    Assertions.assertEquals(0, new BigDecimal("2400000")
        .compareTo(german.get(MoneyAnnotator.MONEY).get(0).value().amount()));
    Assertions.assertEquals("EUR", german.get(MoneyAnnotator.MONEY).get(0).value().currency());
    Assertions.assertEquals(0, new BigDecimal("1250.5")
        .compareTo(german.get(QuantityAnnotator.QUANTITIES).get(0).value().value()));

    final Document american = NumericPacks.fullPipeline().analyze(text);
    Assertions.assertEquals(List.of(), american.get(MoneyAnnotator.MONEY));
    Assertions.assertEquals(List.of(), american.get(QuantityAnnotator.QUANTITIES));
  }

  /**
   * Currency words identify the amount and are included in the text span.
   */
  @Test
  void testCurrencyWordListing() {
    final String text = "the fund raised 1.2 million dollars and 50 euros";

    final List<MoneyAmount> mentions = new CursorMoneyExtractor().extract(text);

    Assertions.assertEquals(2, mentions.size());
    Assertions.assertEquals(0,
        new BigDecimal("1200000").compareTo(mentions.get(0).amount()));
    Assertions.assertEquals("USD", mentions.get(0).currency());
    Assertions.assertEquals("1.2 million dollars",
        mentions.get(0).span().getCoveredText(text).toString());
    Assertions.assertEquals(0, new BigDecimal("50").compareTo(mentions.get(1).amount()));
    Assertions.assertEquals("EUR", mentions.get(1).currency());
    Assertions.assertEquals("50 euros",
        mentions.get(1).span().getCoveredText(text).toString());
  }

  /** The selected notation determines ambiguous values; unsupported grouping is rejected. */
  @Test
  void testAmbiguousNotationListing() {
    final MoneyAmount grouped = new CursorMoneyExtractor(NumberNotation.LATIN_US)
        .extract("1,234 USD").getFirst();
    final MoneyAmount decimal = new CursorMoneyExtractor(NumberNotation.LATIN_EU)
        .extract("1,234 USD").getFirst();
    Assertions.assertEquals(0, new BigDecimal("1234").compareTo(grouped.amount()));
    Assertions.assertEquals(0, new BigDecimal("1.234").compareTo(decimal.amount()));
    Assertions.assertEquals(List.of(), new CursorMoneyExtractor().extract("$1\u202F234"));
  }

  /** The pipeline distinguishes a currency scale suffix from a measurement unit. */
  @Test
  void testMoneyAndMeasurementListing() {
    final String text = "Paid $3m for 3m of pipe and 5kg of fittings; USD 2m remained.";
    final Document document = NumericPacks.fullPipeline().analyze(text);
    final List<Annotation<MoneyAmount>> amounts = document.get(MoneyAnnotator.MONEY);
    Assertions.assertEquals(2, amounts.size());
    Assertions.assertEquals("$3m", amounts.get(0).span().getCoveredText(text).toString());
    Assertions.assertEquals("USD 2m", amounts.get(1).span().getCoveredText(text).toString());
    Assertions.assertEquals("USD", amounts.get(0).value().currency());
    Assertions.assertEquals("USD", amounts.get(1).value().currency());
    Assertions.assertEquals(0, new BigDecimal("3000000").compareTo(amounts.get(0).value().amount()));
    Assertions.assertEquals(0, new BigDecimal("2000000").compareTo(amounts.get(1).value().amount()));
    final List<Annotation<Quantity>> quantities = document.get(QuantityAnnotator.QUANTITIES);
    Assertions.assertEquals(2, quantities.size());
    Assertions.assertEquals(new Span(13, 15), quantities.get(0).span());
    Assertions.assertEquals(new Span(28, 31), quantities.get(1).span());
    Assertions.assertEquals("m", quantities.get(0).value().unit());
    Assertions.assertEquals("kg", quantities.get(1).value().unit());
    Assertions.assertEquals(0, BigDecimal.valueOf(3).compareTo(quantities.get(0).value().value()));
    Assertions.assertEquals(0, BigDecimal.valueOf(5).compareTo(quantities.get(1).value().value()));
  }

  /** The metadata reference resolves dates across an ISO week-year boundary. */
  @Test
  void testMetadataReferenceListing() {
    final String text = "Filed yesterday. Review this week; follow-up next month.";
    final Document document = NumericPacks.temporal(LocalDate.of(2021, 1, 1)).analyze(text);
    final List<Annotation<TemporalExpression>> mentions = document.get(TemporalAnnotator.TEMPORALS);
    Assertions.assertEquals(3, mentions.size());
    Assertions.assertEquals(List.of("2020-12-31", "2020-W53", "2021-02"),
        mentions.stream().map(mention -> mention.value().value()).toList());
    Assertions.assertEquals(List.of("yesterday", "this week", "next month"),
        mentions.stream().map(mention -> mention.span().getCoveredText(text).toString()).toList());
    Assertions.assertTrue(document.get(DocumentDateAnnotator.DOCUMENT_DATE).isEmpty());
  }

  /**
   * Synthetic rates convert an extracted amount on a weekend and retain the source span.
   *
   * @throws IOException If reading the table fails.
   */
  @Test
  void testFxConversionListing() throws IOException {
    final String csv = "Date,USD,JPY,\n2026-07-10,1.25,150,\n";
    final FxRates rates = EcbFxRates.load(
        new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    final String text = "The fare was $10.";
    final MoneyAmount source = new CursorMoneyExtractor().extract(text).getFirst();
    final MoneyAmount converted = rates.convert(source, "JPY", LocalDate.of(2026, 7, 12))
        .orElseThrow();

    Assertions.assertEquals(0, new BigDecimal("1200").compareTo(converted.amount()));
    Assertions.assertEquals("JPY", converted.currency());
    Assertions.assertEquals(source.span(), converted.span());
    Assertions.assertEquals("$10", converted.span().getCoveredText(text).toString());
    Assertions.assertTrue(rates.convert(source, "JPY", LocalDate.of(2026, 7, 17)).isPresent());
    Assertions.assertTrue(rates.convert(source, "JPY", LocalDate.of(2026, 7, 18)).isEmpty());
  }

  /**
   * Converted mentions match source spans even when an intermediate amount has no rate.
   *
   * @throws IOException If reading the synthetic rate table fails.
   */
  @Test
  void testPartialConversionListing() throws IOException {
    final String csv = "Date,USD,JPY,\n2026-07-10,1.25,150,\n";
    final FxRates rates = EcbFxRates.load(
        new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    final String text = "2026-07-12: paid EUR 10, CHF 5, and JPY 150.";
    final Document document = DocumentAnalyzer.builder()
        .add(new TemporalAnnotator(new CursorTemporalExtractor()))
        .add(new DocumentDateAnnotator())
        .add(new MoneyAnnotator(new CursorMoneyExtractor()))
        .add(new MoneyConversionAnnotator(rates, "USD"))
        .build()
        .analyze(text);
    final List<Annotation<MoneyAmount>> source = document.get(MoneyAnnotator.MONEY);
    final List<Annotation<MoneyAmount>> converted =
        document.get(MoneyConversionAnnotator.CONVERTED_MONEY);

    Assertions.assertEquals(LocalDate.of(2026, 7, 12),
        document.get(DocumentDateAnnotator.DOCUMENT_DATE).getFirst().value());
    Assertions.assertEquals(List.of("EUR 10", "CHF 5", "JPY 150"),
        source.stream().map(mention -> mention.span().getCoveredText(text).toString()).toList());
    Assertions.assertEquals(List.of(source.get(0).span(), source.get(2).span()),
        converted.stream().map(Annotation::span).toList());
    Assertions.assertEquals(List.of("USD", "USD"),
        converted.stream().map(mention -> mention.value().currency()).toList());
    Assertions.assertEquals(0, new BigDecimal("12.5")
        .compareTo(converted.getFirst().value().amount()));
    Assertions.assertEquals(0, new BigDecimal("1.25")
        .compareTo(converted.getLast().value().amount()));
    for (final Annotation<MoneyAmount> mention : converted) {
      Assertions.assertEquals(mention.span(), mention.value().span());
    }
  }

  /**
   * Direct conversion avoids multiplication by an already rounded cross rate.
   *
   * @throws IOException If reading the synthetic rate table fails.
   */
  @Test
  void testConversionPrecisionListing() throws IOException {
    final String csv = "Date,USD,JPY\n2026-07-14,3,1\n";
    final FxRates rates = EcbFxRates.load(
        new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    final String text = "The fee was USD 3.";
    final MoneyAmount source = new CursorMoneyExtractor().extract(text).getFirst();
    final LocalDate date = LocalDate.of(2026, 7, 14);
    final BigDecimal rate = rates.rate("USD", "JPY", date).orElseThrow();
    final MoneyAmount converted = rates.convert(source, "JPY", date).orElseThrow();

    Assertions.assertEquals(new BigDecimal("0.3333333333333333"), rate);
    Assertions.assertEquals(BigDecimal.ONE, converted.amount());
    Assertions.assertEquals(new BigDecimal("0.9999999999999999"), source.amount().multiply(rate));
    Assertions.assertEquals("JPY", converted.currency());
    Assertions.assertEquals(source.span(), converted.span());
    Assertions.assertEquals("USD 3", converted.span().getCoveredText(text).toString());
  }

  /** An externally assembled temporal layer retains list order while dates use text position. */
  @Test
  void testExternalTemporalLayerListing() {
    final String text = "Filed 2026-07-14. Due 2026-08-01.";
    final List<Annotation<TemporalExpression>> mentions = new CursorTemporalExtractor().extract(text)
        .reversed().stream().map(value -> new Annotation<>(value.span(), value)).toList();
    final Document input = Document.of(text).with(TemporalAnnotator.TEMPORALS, mentions);
    final Document document = new DocumentDateAnnotator().annotate(input);
    final Annotation<LocalDate> date = document.get(DocumentDateAnnotator.DOCUMENT_DATE).getFirst();

    Assertions.assertEquals(LocalDate.of(2026, 7, 14), date.value());
    Assertions.assertEquals(new Span(6, 16), date.span());
    Assertions.assertEquals("2026-07-14", date.span().getCoveredText(text).toString());
    Assertions.assertEquals(List.of("2026-08-01", "2026-07-14"),
        document.get(TemporalAnnotator.TEMPORALS).stream().map(mention -> mention.value().value()).toList());
    Assertions.assertEquals(mentions, document.get(TemporalAnnotator.TEMPORALS));
    Assertions.assertFalse(input.layers().contains(DocumentDateAnnotator.DOCUMENT_DATE));
  }

  /** A custom unit set replaces the built-in set; percentage recognition remains available. */
  @Test
  void testCustomUnitSetListing() {
    final List<Quantity> mentions = new CursorQuantityExtractor(Set.of("kg"))
        .extract("Load 5kg across 2m at 45% capacity.");
    Assertions.assertEquals(List.of("kg", "%"), mentions.stream().map(Quantity::unit).toList());
    Assertions.assertEquals(List.of(new Span(5, 8), new Span(22, 25)),
        mentions.stream().map(Quantity::span).toList());
    Assertions.assertEquals(0, new BigDecimal("5").compareTo(mentions.getFirst().value()));
    Assertions.assertEquals(0, new BigDecimal("45").compareTo(mentions.getLast().value()));
  }

  /** Timestamp extraction uses the written date without converting the offset to UTC. */
  @Test
  void testTimestampDateListing() {
    final String text = "Received 2026-07-14T23:30:00.125-04:00. Reviewed yesterday.";
    final Document document = NumericPacks.temporal().analyze(text);
    final List<Annotation<TemporalExpression>> mentions = document.get(TemporalAnnotator.TEMPORALS);
    Assertions.assertEquals(2, mentions.size());
    Assertions.assertEquals("2026-07-14", mentions.getFirst().value().value());
    Assertions.assertEquals(new Span(9, 19), mentions.getFirst().span());
    Assertions.assertEquals("2026-07-13", mentions.getLast().value().value());
    Assertions.assertEquals("yesterday", mentions.getLast().span().getCoveredText(text).toString());
    Assertions.assertEquals(LocalDate.of(2026, 7, 14),
        document.get(DocumentDateAnnotator.DOCUMENT_DATE).getFirst().value());
  }
}
