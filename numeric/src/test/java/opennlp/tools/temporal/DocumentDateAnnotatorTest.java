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
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.DocumentAnalyzer;
import opennlp.tools.util.Span;

/** Tests reference-date selection, required layers, and calendar-value validation. */
public class DocumentDateAnnotatorTest {

  /** The first absolute day supplies the reference date and original span. */
  @Test
  void testFirstDayMentionElectsTheDate() {
    final Document document = Document.of("2026-07 report, filed 2026-07-10, due 2026-07-20.")
        .with(TemporalAnnotator.TEMPORALS, List.of(
            new Annotation<>(new Span(0, 7), new TemporalExpression(
                new Span(0, 7), "2026-07", TemporalExpression.Granularity.MONTH)),
            new Annotation<>(new Span(22, 32), new TemporalExpression(
                new Span(22, 32), "2026-07-10", TemporalExpression.Granularity.DAY)),
            new Annotation<>(new Span(38, 48), new TemporalExpression(
                new Span(38, 48), "2026-07-20", TemporalExpression.Granularity.DAY))));

    final Document dated = new DocumentDateAnnotator().annotate(document);

    final List<Annotation<LocalDate>> dates =
        dated.get(DocumentDateAnnotator.DOCUMENT_DATE);
    Assertions.assertEquals(1, dates.size());
    Assertions.assertEquals(LocalDate.parse("2026-07-10"), dates.get(0).value());
    Assertions.assertEquals(new Span(22, 32), dates.get(0).span());
  }

  /** Month and quarter mentions do not supply a day-level reference. */
  @Test
  void testCoarserMentionsNeverElectADate() {
    final Document document = Document.of("the 2026-07 report and 2024-Q3 numbers")
        .with(TemporalAnnotator.TEMPORALS, List.of(
            new Annotation<>(new Span(4, 11), new TemporalExpression(
                new Span(4, 11), "2026-07", TemporalExpression.Granularity.MONTH)),
            new Annotation<>(new Span(23, 30), new TemporalExpression(
                new Span(23, 30), "2024-Q3", TemporalExpression.Granularity.QUARTER))));

    Assertions.assertTrue(new DocumentDateAnnotator().annotate(document)
        .get(DocumentDateAnnotator.DOCUMENT_DATE).isEmpty());
  }

  /**
   * The extractor pipeline selects the first date by text position.
   */
  @Test
  void testFirstOfConflictingDatesWinsThroughThePipeline() {
    final Document document = DocumentAnalyzer.builder()
        .add(new TemporalAnnotator(new CursorTemporalExtractor()))
        .add(new DocumentDateAnnotator())
        .build()
        .analyze("Filed 2026-07-10. Hearing on 2026-09-02.");

    final List<Annotation<LocalDate>> dates =
        document.get(DocumentDateAnnotator.DOCUMENT_DATE);
    Assertions.assertEquals(1, dates.size());
    Assertions.assertEquals(LocalDate.parse("2026-07-10"), dates.get(0).value());
    Assertions.assertEquals(new Span(6, 16), dates.get(0).span());
  }

  /**
   * A missing temporal layer is rejected; an empty temporal layer adds an empty date layer.
   */
  @Test
  void testTemporalLayerRequirement() {
    final IllegalArgumentException e = Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> new DocumentDateAnnotator().annotate(Document.of("no dates here")));
    Assertions.assertEquals(
        "document lacks the required layer opennlp:temporals<TemporalExpression>",
        e.getMessage());

    final Document dated = new DocumentDateAnnotator().annotate(
        Document.of("no dates here").with(TemporalAnnotator.TEMPORALS, List.of()));
    Assertions.assertTrue(dated.get(DocumentDateAnnotator.DOCUMENT_DATE).isEmpty());
  }

  /**
   * An invalid date format is reported with the value and original span.
   */
  @Test
  void testInvalidDayFormat() {
    final Document document = Document.of("Filed July 14, 2026.")
        .with(TemporalAnnotator.TEMPORALS, List.of(
            new Annotation<>(new Span(6, 19), new TemporalExpression(
                new Span(6, 19), "July 14, 2026", TemporalExpression.Granularity.DAY))));

    final IllegalArgumentException e = Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> new DocumentDateAnnotator().annotate(document));
    Assertions.assertEquals(
        "not an ISO 8601 day value at [6..19): July 14, 2026", e.getMessage());
  }

  /**
   * An impossible calendar date is reported with the value and original span.
   */
  @Test
  void testInvalidCalendarDate() {
    final Document document = Document.of("Filed 2026-02-30.")
        .with(TemporalAnnotator.TEMPORALS, List.of(
            new Annotation<>(new Span(6, 16), new TemporalExpression(
                new Span(6, 16), "2026-02-30", TemporalExpression.Granularity.DAY))));

    final IllegalArgumentException e = Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> new DocumentDateAnnotator().annotate(document));
    Assertions.assertEquals(
        "not an ISO 8601 day value at [6..16): 2026-02-30", e.getMessage());
  }

  /** A null document is rejected at the API boundary. */
  @Test
  void testInvalidArguments() {
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new DocumentDateAnnotator().annotate(null));
  }
}
