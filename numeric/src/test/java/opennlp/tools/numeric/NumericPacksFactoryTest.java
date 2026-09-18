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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.DocumentAnalyzer;
import opennlp.tools.document.DocumentAnnotator;
import opennlp.tools.document.LayerKey;
import opennlp.tools.money.MoneyAmount;
import opennlp.tools.money.MoneyAnnotator;
import opennlp.tools.quantity.Quantity;
import opennlp.tools.quantity.QuantityAnnotator;
import opennlp.tools.temporal.DocumentDateAnnotator;
import opennlp.tools.temporal.TemporalAnnotator;
import opennlp.tools.temporal.TemporalExpression;
import opennlp.tools.util.Span;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests factory contracts and concurrent analysis of independent documents. */
@Isolated
class NumericPacksFactoryTest {

  private static final LocalDate FIRST_DATE = LocalDate.of(2026, 1, 1);
  private static final LocalDate FIXED_REFERENCE = LocalDate.of(2025, 12, 25);
  private static final Set<LayerKey<?>> MONEY = Set.of(MoneyAnnotator.MONEY);
  private static final Set<LayerKey<?>> QUANTITY = Set.of(QuantityAnnotator.QUANTITIES);
  private static final Set<LayerKey<?>> TEMPORAL =
      Set.of(TemporalAnnotator.TEMPORALS, DocumentDateAnnotator.DOCUMENT_DATE);
  private static final Set<LayerKey<?>> ALL = Set.of(MoneyAnnotator.MONEY,
      QuantityAnnotator.QUANTITIES, TemporalAnnotator.TEMPORALS, DocumentDateAnnotator.DOCUMENT_DATE);
  private static final String YESTERDAY = "yesterday";
  private static final String NO_MATCH = "An empty envelope.";
  private static final int WORKERS = 4;
  private static final int CALLS_PER_WORKER = 16;

  /**
   * Specifies a factory and the output expected from the generated input.
   *
   * @param name The displayed test name.
   * @param factory The analyzer factory.
   * @param layers The expected layers.
   * @param symbol The currency sign in the input.
   * @param currency The expected currency code.
   * @param decimal The decimal separator in the input.
   * @param reference The configured date, or null for text-based resolution.
   */
  private record Pack(String name, Supplier<DocumentAnalyzer> factory, Set<LayerKey<?>> layers,
                      String symbol, String currency, char decimal, LocalDate reference) {

    /** {@inheritDoc} */
    @Override
    public String toString() {
      return name;
    }
  }

  /** @return The factory variants and their expected output. */
  private static Stream<Pack> packs() {
    return Stream.of(
        new Pack("money", NumericPacks::money, MONEY, "$", "USD", '.', null),
        new Pack("money-Australia", () -> NumericPacks.money(Locale.of("en", "AU")),
            MONEY, "$", "AUD", '.', null),
        new Pack("quantity", NumericPacks::quantity, QUANTITY, "$", "USD", '.', null),
        new Pack("quantity-France", () -> NumericPacks.quantity(Locale.FRANCE),
            QUANTITY, "€", "EUR", ',', null),
        new Pack("temporal", NumericPacks::temporal, TEMPORAL, "$", "USD", '.', null),
        new Pack("temporal-fixed", () -> NumericPacks.temporal(FIXED_REFERENCE),
            TEMPORAL, "$", "USD", '.', FIXED_REFERENCE),
        new Pack("full", NumericPacks::fullPipeline, ALL, "$", "USD", '.', null),
        new Pack("full-Germany", () -> NumericPacks.fullPipeline(Locale.GERMANY),
            ALL, "€", "EUR", ',', null),
        new Pack("annotators", () -> analyzer(NumericPacks.annotators()),
            ALL, "$", "USD", '.', null),
        new Pack("annotators-Germany", () -> analyzer(NumericPacks.annotators(Locale.GERMANY)),
            ALL, "€", "EUR", ',', null));
  }

  /**
   * Builds an analyzer from a factory's annotator list.
   *
   * @param annotators The ordered annotators.
   * @return The analyzer.
   */
  private static DocumentAnalyzer analyzer(List<DocumentAnnotator> annotators) {
    final DocumentAnalyzer.Builder builder = DocumentAnalyzer.builder();
    annotators.forEach(builder::add);
    return builder.build();
  }

  /**
   * Empty inputs retain the configured layers without creating mentions.
   *
   * @param pack The factory configuration.
   */
  @ParameterizedTest
  @MethodSource("packs")
  void testEmptyAndUnmatchedInputs(Pack pack) {
    final DocumentAnalyzer analyzer = pack.factory().get();
    assertEmptyLayers(analyzer.analyze(""), pack.layers());
    assertEmptyLayers(analyzer.analyze(NO_MATCH), pack.layers());
    assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(null));
    assertNotSame(analyzer, pack.factory().get());
  }

  /**
   * Reusing an analyzer does not reuse values from a preceding document.
   *
   * @param pack The factory configuration.
   */
  @ParameterizedTest
  @MethodSource("packs")
  void testRepeatedAnalysis(Pack pack) {
    final DocumentAnalyzer analyzer = pack.factory().get();
    for (int id = 0; id < 8; id++) {
      checkDocument(pack, analyzer, id);
    }
  }

  /**
   * Concurrent requests use independent dates, amounts and UTF-16 offsets.
   *
   * @param pack The factory configuration.
   * @throws Exception If a request fails or the workers do not finish.
   */
  @ParameterizedTest
  @MethodSource("packs")
  void testConcurrentAnalysis(Pack pack) throws Exception {
    final DocumentAnalyzer analyzer = pack.factory().get();
    final ExecutorService executor = Executors.newFixedThreadPool(WORKERS);
    final CountDownLatch ready = new CountDownLatch(WORKERS);
    final CountDownLatch start = new CountDownLatch(1);
    try {
      final List<Future<Void>> futures = new ArrayList<>();
      for (int worker = 0; worker < WORKERS; worker++) {
        final int workerId = worker;
        futures.add(executor.submit(() -> {
          ready.countDown();
          assertTrue(start.await(10, TimeUnit.SECONDS));
          for (int request = 0; request < CALLS_PER_WORKER; request++) {
            checkDocument(pack, analyzer, workerId * CALLS_PER_WORKER + request);
          }
          return null;
        }));
      }
      assertTrue(ready.await(10, TimeUnit.SECONDS));
      start.countDown();
      for (Future<Void> future : futures) {
        future.get(10, TimeUnit.SECONDS);
      }
    } finally {
      start.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }
  }

  /**
   * Validates output using values known before extraction.
   *
   * @param pack The factory configuration.
   * @param analyzer The shared analyzer.
   * @param id The request number.
   */
  private void checkDocument(Pack pack, DocumentAnalyzer analyzer, int id) {
    final LocalDate date = FIRST_DATE.plusDays(id);
    final boolean dated = id % 2 == 0;
    final BigDecimal amount = BigDecimal.valueOf(1000L + id, 2);
    final BigDecimal quantity = BigDecimal.valueOf(200L + id, 1);
    final String moneyText = pack.symbol() + amount.toPlainString().replace('.', pack.decimal());
    final String quantityText = quantity.toPlainString().replace('.', pack.decimal()) + " kg";
    final String text = "\uD83D\uDCCE " + (dated ? date + ": " : "")
        + "Paid " + moneyText + " " + YESTERDAY + " for " + quantityText + ".";
    final Document document = analyzer.analyze(text);
    assertEquals(text, document.text().toString());
    assertEquals(pack.layers(), document.layers());
    if (pack.layers().contains(MoneyAnnotator.MONEY)) {
      final List<Annotation<MoneyAmount>> mentions = document.get(MoneyAnnotator.MONEY);
      assertEquals(1, mentions.size(), text);
      final Annotation<MoneyAmount> mention = mentions.getFirst();
      assertEquals(0, amount.compareTo(mention.value().amount()), text);
      assertEquals(pack.currency(), mention.value().currency(), text);
      assertEquals(mention.span(), mention.value().span());
      assertSpan(text, moneyText, mention.span());
    }
    if (pack.layers().contains(QuantityAnnotator.QUANTITIES)) {
      final List<Annotation<Quantity>> mentions = document.get(QuantityAnnotator.QUANTITIES);
      assertEquals(1, mentions.size(), text);
      final Annotation<Quantity> mention = mentions.getFirst();
      assertEquals(0, quantity.compareTo(mention.value().value()), text);
      assertEquals("kg", mention.value().unit());
      assertEquals(mention.span(), mention.value().span());
      assertSpan(text, quantityText, mention.span());
    }
    if (pack.layers().contains(TemporalAnnotator.TEMPORALS)) {
      final List<Annotation<LocalDate>> dates = document.get(DocumentDateAnnotator.DOCUMENT_DATE);
      assertEquals(dated ? 1 : 0, dates.size(), text);
      if (dated) {
        assertEquals(date, dates.getFirst().value());
        assertSpan(text, date.toString(), dates.getFirst().span());
      }
      final LocalDate reference = pack.reference() != null ? pack.reference() : dated ? date : null;
      final List<Annotation<TemporalExpression>> temporals = document.get(TemporalAnnotator.TEMPORALS);
      assertEquals((dated ? 1 : 0) + (reference != null ? 1 : 0), temporals.size(), text);
      if (dated) {
        assertEquals(date.toString(), temporals.getFirst().value().value());
        assertEquals(TemporalExpression.Origin.ABSOLUTE, temporals.getFirst().value().origin());
        assertSpan(text, date.toString(), temporals.getFirst().span());
      }
      if (reference != null) {
        final Annotation<TemporalExpression> relative = temporals.getLast();
        assertEquals(reference.minusDays(1).toString(), relative.value().value(), text);
        assertEquals(TemporalExpression.Origin.RELATIVE, relative.value().origin());
        assertEquals(relative.span(), relative.value().span());
        assertSpan(text, YESTERDAY, relative.span());
      }
    }
    assertEmptyLayers(analyzer.analyze(NO_MATCH), pack.layers());
  }

  /**
   * Checks the original offsets and covered text of a mention.
   *
   * @param text The original input.
   * @param fragment The expected mention text.
   * @param span The reported span.
   */
  private void assertSpan(String text, String fragment, Span span) {
    final int start = text.indexOf(fragment);
    assertTrue(start >= 0);
    assertEquals(new Span(start, start + fragment.length()), span);
    assertEquals(fragment, span.getCoveredText(text).toString());
  }

  /**
   * Checks that the expected layers are present and empty.
   *
   * @param document The analysis result.
   * @param layers The expected layers.
   */
  private void assertEmptyLayers(Document document, Set<LayerKey<?>> layers) {
    assertEquals(layers, document.layers());
    for (LayerKey<?> layer : layers) {
      assertTrue(document.get(layer).isEmpty(), layer.toString());
    }
  }

  /**
   * Editing a returned list does not affect another list or a built analyzer.
   *
   * @param regional Whether to use the German variant.
   */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testAnnotatorListsAreIndependent(boolean regional) {
    final List<DocumentAnnotator> first = regional
        ? NumericPacks.annotators(Locale.GERMANY) : NumericPacks.annotators();
    final List<DocumentAnnotator> other = regional
        ? NumericPacks.annotators(Locale.GERMANY) : NumericPacks.annotators();
    final DocumentAnalyzer built = analyzer(first);
    assertNotSame(first, other);
    first.clear();
    assertEquals(4, other.size());
    assertEmptyLayers(built.analyze(NO_MATCH), ALL);
    assertEmptyLayers(analyzer(other).analyze(NO_MATCH), ALL);
  }

  /** @return Locales without an applicable currency. */
  private static Stream<Locale> invalidRegions() {
    return Stream.of(null, Locale.ROOT, Locale.ENGLISH, Locale.of("en", "AQ"), Locale.of("en", "ZZ"));
  }

  /**
   * All regional factories using currencies reject an invalid region.
   *
   * @param region The unsupported locale.
   */
  @ParameterizedTest
  @MethodSource("invalidRegions")
  void testCurrencyFactoriesRejectMissingCurrency(Locale region) {
    assertThrows(IllegalArgumentException.class, () -> NumericPacks.money(region));
    assertThrows(IllegalArgumentException.class, () -> NumericPacks.fullPipeline(region));
    assertThrows(IllegalArgumentException.class, () -> NumericPacks.annotators(region));
  }

  /**
   * Quantity-only factories do not require a currency or country.
   *
   * @param tag The locale language tag.
   */
  @ParameterizedTest
  @ValueSource(strings = {"und", "en", "en-AQ", "en-ZZ"})
  void testQuantityLocaleDoesNotRequireCurrency(String tag) {
    final Document document = NumericPacks.quantity(Locale.forLanguageTag(tag)).analyze("12.5 kg");
    assertEquals(QUANTITY, document.layers());
    assertEquals(1, document.get(QuantityAnnotator.QUANTITIES).size());
    assertEquals(0, new BigDecimal("12.5")
        .compareTo(document.get(QuantityAnnotator.QUANTITIES).getFirst().value().value()));
  }

  /**
   * Factories select notation without depending on the process default locale.
   *
   * @param tag The temporary default language tag.
   */
  @ParameterizedTest
  @ValueSource(strings = {"de-DE", "tr-TR", "ar-EG-u-nu-arab"})
  void testDefaultLocaleDoesNotChangeFactoryBehavior(String tag) {
    final Locale previous = Locale.getDefault();
    final Locale previousFormat = Locale.getDefault(Locale.Category.FORMAT);
    final Locale previousDisplay = Locale.getDefault(Locale.Category.DISPLAY);
    try {
      Locale.setDefault(Locale.forLanguageTag(tag));
      for (Pack pack : packs().toList()) {
        final DocumentAnalyzer analyzer = pack.factory().get();
        checkDocument(pack, analyzer, 0);
        checkDocument(pack, analyzer, 1);
      }
    } finally {
      Locale.setDefault(previous);
      Locale.setDefault(Locale.Category.FORMAT, previousFormat);
      Locale.setDefault(Locale.Category.DISPLAY, previousDisplay);
    }
  }
}
