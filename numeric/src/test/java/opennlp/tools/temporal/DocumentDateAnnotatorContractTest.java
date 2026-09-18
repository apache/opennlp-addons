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

package opennlp.tools.temporal;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.util.Span;

/** Tests reference-date selection on externally supplied temporal layers. */
class DocumentDateAnnotatorContractTest {

  private static final String TEXT = "2026-07-14; 2026-08-01; yesterday";
  private static final Span FIRST_SPAN = new Span(0, 10);
  private static final Span LATER_SPAN = new Span(12, 22);
  private static final String FIRST_DATE = "2026-07-14";
  private static final String LATER_DATE = "2026-08-01";
  private static final String INVALID_DATE = "2026-02-30";
  private static final LocalDate DATE = LocalDate.of(2026, 7, 14);

  /**
   * Supplies all permutations of the first, later, and relative mentions.
   *
   * @return The input-list index permutations.
   */
  private static Stream<Arguments> permutations() {
    return Stream.of(Arguments.of(0, 1, 2), Arguments.of(0, 2, 1), Arguments.of(1, 0, 2),
        Arguments.of(1, 2, 0), Arguments.of(2, 0, 1), Arguments.of(2, 1, 0));
  }

  /**
   * Text position selects the reference date independently of annotation-list order.
   *
   * @param first The first list index.
   * @param middle The middle list index.
   * @param last The last list index.
   */
  @ParameterizedTest
  @MethodSource("permutations")
  void testTextOrderSelection(int first, int middle, int last) {
    final List<Annotation<TemporalExpression>> mentions = mentions();
    final List<Annotation<TemporalExpression>> ordered =
        List.of(mentions.get(first), mentions.get(middle), mentions.get(last));
    final Document input = Document.of(TEXT).with(TemporalAnnotator.TEMPORALS, ordered);
    final Document result = new DocumentDateAnnotator().annotate(input);
    Assertions.assertEquals(List.of(new Annotation<>(FIRST_SPAN, DATE)),
        result.get(DocumentDateAnnotator.DOCUMENT_DATE));
    Assertions.assertEquals(ordered, result.get(TemporalAnnotator.TEMPORALS));
    Assertions.assertFalse(input.layers().contains(DocumentDateAnnotator.DOCUMENT_DATE));
  }

  /**
   * Mention values must have the same spans as their annotations, including unselected values.
   *
   * @param index The mention with inconsistent offsets.
   */
  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void testMismatchedSpan(int index) {
    final List<Annotation<TemporalExpression>> mentions = new ArrayList<>(mentions());
    final Annotation<TemporalExpression> original = mentions.get(index);
    final TemporalExpression value = original.value();
    mentions.set(index, new Annotation<>(original.span(), new TemporalExpression(
        new Span(original.span().getStart() + 1, original.span().getEnd()),
        value.value(), value.granularity(), value.origin())));
    final Document input = Document.of(TEXT).with(TemporalAnnotator.TEMPORALS, mentions);
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new DocumentDateAnnotator().annotate(input));
  }

  /** Duplicate output is reported before parsing temporal values. */
  @Test
  void testDuplicateOutput() {
    final Document input = Document.of(TEXT)
        .with(TemporalAnnotator.TEMPORALS, List.of(day(FIRST_SPAN, INVALID_DATE)))
        .with(DocumentDateAnnotator.DOCUMENT_DATE, List.of());
    final IllegalArgumentException error = Assertions.assertThrows(IllegalArgumentException.class,
        () -> new DocumentDateAnnotator().annotate(input));
    Assertions.assertEquals("layer is already present: " + DocumentDateAnnotator.DOCUMENT_DATE,
        error.getMessage());
  }

  /**
   * An invalid first absolute day cannot be replaced by a later valid date.
   *
   * @param reversed Whether the input list puts the later date first.
   */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testInvalidSelectedDate(boolean reversed) {
    final Annotation<TemporalExpression> first = day(FIRST_SPAN, INVALID_DATE);
    final Annotation<TemporalExpression> later = day(LATER_SPAN, LATER_DATE);
    final Document input = Document.of(TEXT).with(TemporalAnnotator.TEMPORALS,
        reversed ? List.of(later, first) : List.of(first, later));
    final IllegalArgumentException error = Assertions.assertThrows(IllegalArgumentException.class,
        () -> new DocumentDateAnnotator().annotate(input));
    Assertions.assertTrue(error.getMessage().contains(INVALID_DATE));
    Assertions.assertTrue(error.getMessage().contains(FIRST_SPAN.toString()));
  }

  /** Calendar parsing applies to the selected reference value, not later date mentions. */
  @Test
  void testInvalidLaterDate() {
    final Document input = Document.of(TEXT).with(TemporalAnnotator.TEMPORALS,
        List.of(day(LATER_SPAN, INVALID_DATE), day(FIRST_SPAN, FIRST_DATE)));
    final Document result = Assertions.assertDoesNotThrow(() -> new DocumentDateAnnotator().annotate(input));
    Assertions.assertEquals(List.of(new Annotation<>(FIRST_SPAN, DATE)),
        result.get(DocumentDateAnnotator.DOCUMENT_DATE));
  }

  /** Equal start offsets retain input-list order. */
  @Test
  void testEqualStarts() {
    final Document input = Document.of(TEXT).with(TemporalAnnotator.TEMPORALS,
        List.of(day(FIRST_SPAN, FIRST_DATE), day(new Span(0, 9), LATER_DATE)));
    Assertions.assertEquals(DATE, new DocumentDateAnnotator().annotate(input)
        .get(DocumentDateAnnotator.DOCUMENT_DATE).getFirst().value());
  }

  /**
   * Shared instances do not retain one document's reference date for another document.
   *
   * @throws Exception If a worker fails.
   */
  @Test
  void testConcurrentDocuments() throws Exception {
    final DocumentDateAnnotator annotator = new DocumentDateAnnotator();
    final List<Callable<Void>> tasks = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      final LocalDate date = DATE.plusDays(i);
      tasks.add(() -> {
        final Document input = Document.of(date.toString()).with(TemporalAnnotator.TEMPORALS,
            List.of(day(FIRST_SPAN, date.toString())));
        Assertions.assertEquals(date,
            annotator.annotate(input).get(DocumentDateAnnotator.DOCUMENT_DATE).getFirst().value());
        return null;
      });
    }
    try (var executor = Executors.newFixedThreadPool(4)) {
      for (var result : executor.invokeAll(tasks)) {
        result.get();
      }
    }
  }

  /**
   * Creates absolute and relative mentions with consistent original-text spans.
   *
   * @return The first day, later day, and relative mention.
   */
  private List<Annotation<TemporalExpression>> mentions() {
    final Span relative = new Span(24, 33);
    return List.of(day(FIRST_SPAN, FIRST_DATE), day(LATER_SPAN, LATER_DATE),
        new Annotation<>(relative, new TemporalExpression(relative, "2026-07-13",
            TemporalExpression.Granularity.DAY, TemporalExpression.Origin.RELATIVE)));
  }

  /**
   * Creates one absolute day annotation.
   *
   * @param span The original-text span.
   * @param value The calendar value.
   * @return The temporal annotation.
   */
  private Annotation<TemporalExpression> day(Span span, String value) {
    return new Annotation<>(span, new TemporalExpression(span, value, TemporalExpression.Granularity.DAY));
  }
}
