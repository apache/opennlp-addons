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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.temporal.DocumentDateAnnotator;
import opennlp.tools.util.Span;

/** Tests conversion input and provider-result consistency. */
class MoneyConversionContractTest {

  private static final LocalDate DATE = LocalDate.of(2026, 7, 14);
  private static final MoneyAmount SOURCE = new MoneyAmount(new Span(0, 6), BigDecimal.TEN, "EUR");

  /**
   * Both date modes reject duplicate output before querying the provider.
   *
   * @param fixed Whether to configure a fixed reference date.
   */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testDuplicateOutputBeforeConversion(boolean fixed) {
    final AtomicInteger calls = new AtomicInteger();
    final FxRates provider = provider(calls, (amount, date) -> Optional.empty());
    final MoneyConversionAnnotator annotator = fixed
        ? new MoneyConversionAnnotator(provider, "USD", DATE)
        : new MoneyConversionAnnotator(provider, "USD");
    final Document input = input().with(MoneyConversionAnnotator.CONVERTED_MONEY, List.of());
    Assertions.assertThrows(IllegalArgumentException.class, () -> annotator.annotate(input));
    Assertions.assertEquals(0, calls.get());
  }

  /**
   * Provider results must use the requested currency and retain the input span.
   *
   * @param failure The invalid result to supply.
   */
  @ParameterizedTest
  @ValueSource(strings = {"null", "currency", "offsets", "spanType"})
  void testInvalidProviderResult(String failure) {
    final FxRates provider = provider(new AtomicInteger(), (amount, date) -> switch (failure) {
      case "null" -> null;
      case "currency" -> Optional.of(new MoneyAmount(amount.span(), BigDecimal.ONE, "JPY"));
      case "offsets" -> Optional.of(new MoneyAmount(new Span(1, 6), BigDecimal.ONE, "USD"));
      case "spanType" -> Optional.of(new MoneyAmount(new Span(0, 6, "changed"), BigDecimal.ONE, "USD"));
      default -> throw new IllegalArgumentException(failure);
    });
    final Document input = input();
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new MoneyConversionAnnotator(provider, "USD", DATE).annotate(input));
    Assertions.assertFalse(input.layers().contains(MoneyConversionAnnotator.CONVERTED_MONEY));
  }

  /** Input amount spans are checked before any conversion call. */
  @Test
  void testMismatchedInputSpan() {
    final Document input = Document.of("EUR 10; EUR 20").with(MoneyAnnotator.MONEY,
        List.of(new Annotation<>(SOURCE.span(), SOURCE),
            new Annotation<>(new Span(8, 14),
                new MoneyAmount(new Span(8, 10), BigDecimal.TWO, "EUR"))));
    final AtomicInteger calls = new AtomicInteger();
    final FxRates provider = provider(calls, (amount, date) -> Optional.empty());
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new MoneyConversionAnnotator(provider, "USD", DATE).annotate(input));
    Assertions.assertEquals(0, calls.get());
  }

  /**
   * Empty amounts and a missing document-date value do not query the provider.
   *
   * @param emptyAmounts Whether the amount layer, or the document-date layer, is empty.
   */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testEmptyRequiredValues(boolean emptyAmounts) {
    final Document input = Document.of("EUR 10")
        .with(MoneyAnnotator.MONEY, emptyAmounts ? List.of()
            : List.of(new Annotation<>(SOURCE.span(), SOURCE)))
        .with(DocumentDateAnnotator.DOCUMENT_DATE, emptyAmounts
            ? List.of(new Annotation<>(new Span(0, 0), DATE)) : List.of());
    final AtomicInteger calls = new AtomicInteger();
    final Document output = new MoneyConversionAnnotator(
        provider(calls, (amount, date) -> Optional.empty()), "USD").annotate(input);
    Assertions.assertTrue(output.get(MoneyConversionAnnotator.CONVERTED_MONEY).isEmpty());
    Assertions.assertEquals(0, calls.get());
  }

  /**
   * Document-date mode rejects multiple date annotations before querying rates.
   *
   * @param repeated Whether the annotations contain the same date.
   */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testMultipleDocumentDates(boolean repeated) {
    final Document input = inputWithDates(List.of(DATE, repeated ? DATE : DATE.minusDays(1)));
    final AtomicInteger calls = new AtomicInteger();
    final FxRates rates = provider(calls, (amount, date) -> Optional.empty());
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new MoneyConversionAnnotator(rates, "USD").annotate(input));
    Assertions.assertEquals(0, calls.get());
  }

  /**
   * Fixed-date mode does not read an optional document-date layer.
   *
   * @param repeated Whether the unused annotations contain the same date.
   */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testFixedDateIgnoresDocumentDates(boolean repeated) {
    final Document input = inputWithDates(List.of(DATE.minusDays(1),
        repeated ? DATE.minusDays(1) : DATE.plusDays(1)));
    final AtomicInteger calls = new AtomicInteger();
    final FxRates rates = provider(calls, (amount, date) -> {
      Assertions.assertEquals(DATE, date);
      return Optional.empty();
    });
    Assertions.assertTrue(new MoneyConversionAnnotator(rates, "USD", DATE).annotate(input)
        .get(MoneyConversionAnnotator.CONVERTED_MONEY).isEmpty());
    Assertions.assertEquals(1, calls.get());
  }

  /**
   * Valid results retain negative and zero amounts without additional rounding.
   *
   * @param amount The provider's result amount.
   */
  @ParameterizedTest
  @ValueSource(strings = {"0.0000", "-12345678901234567890.123456789"})
  void testValidResult(String amount) {
    final MoneyAmount converted = new MoneyAmount(SOURCE.span(), new BigDecimal(amount), "USD");
    final AtomicInteger calls = new AtomicInteger();
    final FxRates provider = provider(calls, (value, date) -> {
      Assertions.assertSame(SOURCE, value);
      Assertions.assertEquals(DATE, date);
      return Optional.of(converted);
    });
    final Document result = new MoneyConversionAnnotator(provider, "USD", DATE).annotate(input());
    final Annotation<MoneyAmount> annotation =
        result.get(MoneyConversionAnnotator.CONVERTED_MONEY).getFirst();
    Assertions.assertSame(converted, annotation.value());
    Assertions.assertEquals(SOURCE.span(), annotation.span());
    Assertions.assertEquals(1, calls.get());
  }

  /** Provider failures propagate without being replaced by a missing-rate result. */
  @Test
  void testProviderFailure() {
    final IllegalStateException failure = new IllegalStateException("test conversion failure");
    final FxRates provider = provider(new AtomicInteger(), (amount, date) -> {
      throw failure;
    });
    Assertions.assertSame(failure, Assertions.assertThrows(IllegalStateException.class,
        () -> new MoneyConversionAnnotator(provider, "USD", DATE).annotate(input())));
  }

  /**
   * Builds an input with one amount and a document date.
   *
   * @return The immutable input document.
   */
  private Document input() {
    return inputWithDates(List.of(DATE.minusDays(1)));
  }

  /**
   * Builds an input with one amount and the specified document-date annotations.
   *
   * @param dates The date values to include.
   * @return The immutable input document.
   */
  private Document inputWithDates(List<LocalDate> dates) {
    return Document.of("EUR 10").with(MoneyAnnotator.MONEY,
        List.of(new Annotation<>(SOURCE.span(), SOURCE)))
        .with(DocumentDateAnnotator.DOCUMENT_DATE,
            dates.stream().map(date -> new Annotation<>(new Span(0, 0), date)).toList());
  }

  /**
   * Creates a provider that exposes calls to its conversion override.
   *
   * @param calls The conversion-call counter.
   * @param convert The conversion result function.
   * @return The synthetic provider.
   */
  private FxRates provider(AtomicInteger calls,
      BiFunction<MoneyAmount, LocalDate, Optional<MoneyAmount>> convert) {
    return new FxRates() {
      /** {@inheritDoc} */
      @Override
      public Optional<BigDecimal> rate(String from, String to, LocalDate date) {
        return Assertions.fail("the conversion override must be used");
      }

      /** {@inheritDoc} */
      @Override
      public Optional<MoneyAmount> convert(MoneyAmount amount, String to, LocalDate date) {
        calls.incrementAndGet();
        Assertions.assertEquals("USD", to);
        return convert.apply(amount, date);
      }
    };
  }
}
