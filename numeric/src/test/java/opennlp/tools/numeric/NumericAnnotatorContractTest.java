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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.DocumentAnnotator;
import opennlp.tools.document.LayerKey;
import opennlp.tools.money.MoneyAmount;
import opennlp.tools.money.MoneyAnnotator;
import opennlp.tools.quantity.Quantity;
import opennlp.tools.quantity.QuantityAnnotator;
import opennlp.tools.temporal.TemporalAnnotator;
import opennlp.tools.temporal.TemporalExpression;
import opennlp.tools.temporal.TemporalExtractor;
import opennlp.tools.util.Span;

/** Tests result validation across the numeric extraction adapters. */
class NumericAnnotatorContractTest {

  private static final String TEXT = "0123456789";
  private static final LocalDate DATE = LocalDate.of(2026, 7, 14);

  /** Adapter modes sharing the extraction-result contract. */
  private enum Kind { MONEY, QUANTITY, TEMPORAL, TEMPORAL_FIXED }

  /** Synthetic extraction outcomes. */
  private enum Result {
    NULL_LIST, NULL_ELEMENT, OUTSIDE_TEXT, REVERSED, OVERLAP, EMPTY, VALID, BOUNDARIES, THROWS
  }

  /**
   * Supplies each invalid result for each adapter mode.
   *
   * @return The adapter/result combinations.
   */
  private static Stream<Arguments> invalidResults() {
    return Arrays.stream(Kind.values()).flatMap(kind -> Stream.of(Result.NULL_LIST,
        Result.NULL_ELEMENT, Result.OUTSIDE_TEXT, Result.REVERSED, Result.OVERLAP)
        .map(result -> Arguments.of(kind, result)));
  }

  /**
   * Invalid extraction results are rejected without adding a layer to the input.
   *
   * @param kind The adapter mode.
   * @param result The invalid result.
   */
  @ParameterizedTest
  @MethodSource("invalidResults")
  void testInvalidResults(Kind kind, Result result) {
    final Fixture fixture = fixture(kind, result);
    final Document input = Document.of(TEXT);
    Assertions.assertThrows(IllegalArgumentException.class, () -> fixture.annotator().annotate(input));
    Assertions.assertTrue(input.layers().isEmpty());
  }

  /**
   * An existing output layer is rejected before invoking an extractor.
   *
   * @param kind The adapter mode.
   */
  @ParameterizedTest
  @EnumSource(Kind.class)
  void testDuplicateOutputBeforeExtraction(Kind kind) {
    final Fixture fixture = fixture(kind, Result.VALID);
    final Document input = Document.of(TEXT).with(fixture.layer(), List.of());
    Assertions.assertThrows(IllegalArgumentException.class, () -> fixture.annotator().annotate(input));
    Assertions.assertEquals(0, fixture.calls().get());
    Assertions.assertTrue(input.get(fixture.layer()).isEmpty());
  }

  /**
   * A null document is rejected before invoking an extractor.
   *
   * @param kind The adapter mode.
   */
  @ParameterizedTest
  @EnumSource(Kind.class)
  void testNullDocumentBeforeExtraction(Kind kind) {
    final Fixture fixture = fixture(kind, Result.VALID);
    Assertions.assertThrows(IllegalArgumentException.class, () -> fixture.annotator().annotate(null));
    Assertions.assertEquals(0, fixture.calls().get());
  }

  /**
   * An empty extraction produces a present, empty layer.
   *
   * @param kind The adapter mode.
   */
  @ParameterizedTest
  @EnumSource(Kind.class)
  void testEmptyResult(Kind kind) {
    final Fixture fixture = fixture(kind, Result.EMPTY);
    final Document result = fixture.annotator().annotate(Document.of(TEXT));
    Assertions.assertTrue(result.layers().contains(fixture.layer()));
    Assertions.assertTrue(result.get(fixture.layer()).isEmpty());
    Assertions.assertEquals(1, fixture.calls().get());
  }

  /**
   * Valid extraction preserves values and order while detaching the output list.
   *
   * @param kind The adapter mode.
   */
  @ParameterizedTest
  @EnumSource(Kind.class)
  void testValidResult(Kind kind) {
    final Fixture fixture = fixture(kind, Result.VALID);
    final Document input = Document.of(TEXT);
    final Document result = fixture.annotator().annotate(input);
    final List<? extends Annotation<?>> annotations = result.get(fixture.layer());
    Assertions.assertEquals(2, annotations.size());
    Assertions.assertEquals(List.of(new Span(0, 3), new Span(7, 10)),
        annotations.stream().map(Annotation::span).toList());
    Assertions.assertSame(fixture.values().getFirst(), annotations.getFirst().value());
    Assertions.assertSame(fixture.values().getLast(), annotations.getLast().value());
    fixture.values().clear();
    Assertions.assertEquals(2, result.get(fixture.layer()).size());
    Assertions.assertThrows(UnsupportedOperationException.class, annotations::clear);
    Assertions.assertTrue(input.layers().isEmpty());
    Assertions.assertEquals(1, fixture.calls().get());
  }

  /**
   * Adjacent and zero-width spans are valid at the start and end of the text.
   *
   * @param kind The adapter mode.
   */
  @ParameterizedTest
  @EnumSource(Kind.class)
  void testValidSpanBoundaries(Kind kind) {
    final Fixture fixture = fixture(kind, Result.BOUNDARIES);
    final Document result = fixture.annotator().annotate(Document.of(TEXT));
    final List<? extends Annotation<?>> annotations = result.get(fixture.layer());
    Assertions.assertEquals(List.of(new Span(0, 0), new Span(0, 5), new Span(5, 5),
        new Span(5, 10), new Span(10, 10)), annotations.stream().map(Annotation::span).toList());
    for (int i = 0; i < annotations.size(); i++) {
      Assertions.assertSame(fixture.values().get(i), annotations.get(i).value());
    }
    Assertions.assertEquals(1, fixture.calls().get());
  }

  /**
   * Provider failures propagate as the original exception.
   *
   * @param kind The adapter mode.
   */
  @ParameterizedTest
  @EnumSource(Kind.class)
  void testProviderException(Kind kind) {
    final Fixture fixture = fixture(kind, Result.THROWS);
    Assertions.assertSame(fixture.failure(), Assertions.assertThrows(IllegalStateException.class,
        () -> fixture.annotator().annotate(Document.of(TEXT))));
  }

  /**
   * Builds a typed adapter over a synthetic result list.
   *
   * @param kind The adapter mode.
   * @param result The result to return.
   * @return The adapter and its observable provider state.
   */
  private Fixture fixture(Kind kind, Result result) {
    final AtomicInteger calls = new AtomicInteger();
    final RuntimeException failure = result == Result.THROWS
        ? new IllegalStateException("test provider failure") : null;
    return switch (kind) {
      case MONEY -> {
        final List<MoneyAmount> values = results(result,
            span -> new MoneyAmount(span, BigDecimal.ONE, "USD"));
        yield new Fixture(new MoneyAnnotator(text -> returned(values, calls, failure)),
            MoneyAnnotator.MONEY, values, calls, failure);
      }
      case QUANTITY -> {
        final List<Quantity> values = results(result,
            span -> new Quantity(span, BigDecimal.ONE, "kg"));
        yield new Fixture(new QuantityAnnotator(text -> returned(values, calls, failure)),
            QuantityAnnotator.QUANTITIES, values, calls, failure);
      }
      case TEMPORAL, TEMPORAL_FIXED -> {
        final List<TemporalExpression> values = results(result,
            span -> new TemporalExpression(span, "2026-07", TemporalExpression.Granularity.MONTH));
        final TemporalExtractor extractor = text -> returned(values, calls, failure);
        final TemporalAnnotator annotator = kind == Kind.TEMPORAL
            ? new TemporalAnnotator(extractor) : new TemporalAnnotator(extractor, DATE);
        yield new Fixture(annotator, TemporalAnnotator.TEMPORALS, values, calls, failure);
      }
    };
  }

  /**
   * Records one provider call and returns or throws the configured result.
   *
   * @param values The result list.
   * @param calls The invocation counter.
   * @param failure The optional failure.
   * @param <T> The mention type.
   * @return The configured list.
   * @throws RuntimeException If a provider failure was configured.
   */
  private <T> List<T> returned(List<T> values, AtomicInteger calls, RuntimeException failure) {
    calls.incrementAndGet();
    if (failure != null) {
      throw failure;
    }
    return values;
  }

  /**
   * Builds result lists with controlled spans and nulls.
   *
   * @param result The result mode.
   * @param create The mention constructor.
   * @param <T> The mention type.
   * @return The configured list, including null for that test case.
   */
  private <T> List<T> results(Result result, Function<Span, T> create) {
    return switch (result) {
      case NULL_LIST -> null;
      case NULL_ELEMENT -> Arrays.asList(create.apply(new Span(0, 3)), null);
      case OUTSIDE_TEXT -> List.of(create.apply(new Span(0, TEXT.length() + 1)));
      case REVERSED -> List.of(create.apply(new Span(7, 10)), create.apply(new Span(0, 3)));
      case OVERLAP -> List.of(create.apply(new Span(0, 4)), create.apply(new Span(3, 6)));
      case EMPTY -> List.of();
      case BOUNDARIES -> List.of(create.apply(new Span(0, 0)), create.apply(new Span(0, 5)),
          create.apply(new Span(5, 5)), create.apply(new Span(5, 10)), create.apply(new Span(10, 10)));
      case VALID, THROWS -> new ArrayList<>(List.of(create.apply(new Span(0, 3)),
          create.apply(new Span(7, 10))));
    };
  }

  /**
   * An adapter and its provider state.
   *
   * @param annotator The adapter under test.
   * @param layer Its output layer.
   * @param values The provider's result list.
   * @param calls The invocation counter.
   * @param failure The optional provider failure.
   */
  private record Fixture(DocumentAnnotator annotator, LayerKey<?> layer, List<?> values,
                         AtomicInteger calls, RuntimeException failure) {
  }
}
