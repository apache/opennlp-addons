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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.DocumentAnalyzer;
import opennlp.tools.util.Span;

/**
 * Tests how the annotator supplies the reference date relative expressions resolve
 * against: elected from the document's own dateline by default, fixed by the caller
 * through the two-argument constructor, and absent when the text dates itself nowhere,
 * in which case relative expressions stay unreported rather than being guessed against
 * the wall clock.
 */
public class TemporalAnnotatorTest {

  private final TemporalAnnotator annotator =
      new TemporalAnnotator(new CursorTemporalExtractor());

  private static List<Annotation<TemporalExpression>> temporals(TemporalAnnotator annotator,
      String text) {
    return annotator.annotate(Document.of(text)).get(TemporalAnnotator.TEMPORALS);
  }

  private static Span spanOf(String text, String fragment) {
    final int start = text.indexOf(fragment);
    Assertions.assertTrue(start >= 0, "fragment not found: " + fragment);
    return new Span(start, start + fragment.length());
  }

  /**
   * Verifies the headline behavior: a dateline dates the document, so a relative
   * expression later in the same text resolves against it and is reported with its own
   * span and its resolved value.
   */
  @Test
  void testDatelineResolvesALaterRelativeExpression() {
    final String text = "Berlin, 14 July 2026. The buyer paid yesterday.";

    final List<Annotation<TemporalExpression>> mentions = temporals(annotator, text);

    Assertions.assertEquals(2, mentions.size());
    Assertions.assertEquals(spanOf(text, "14 July 2026"), mentions.get(0).span());
    Assertions.assertEquals("2026-07-14", mentions.get(0).value().value());
    Assertions.assertEquals(spanOf(text, "yesterday"), mentions.get(1).span());
    Assertions.assertEquals("2026-07-13", mentions.get(1).value().value());
    Assertions.assertEquals(TemporalExpression.Granularity.DAY,
        mentions.get(1).value().granularity());
  }

  /**
   * Verifies that the election rule is the dateline rule of
   * {@link DocumentDateAnnotator}: the first day-granularity mention in text order
   * supplies the reference, later days do not.
   */
  @Test
  void testFirstDayMentionSuppliesTheReference() {
    final String text = "Filed 2026-07-10. Hearing on 2026-09-02. Amended yesterday.";

    final List<Annotation<TemporalExpression>> mentions = temporals(annotator, text);

    Assertions.assertEquals(3, mentions.size());
    Assertions.assertEquals(spanOf(text, "yesterday"), mentions.get(2).span());
    Assertions.assertEquals("2026-07-09", mentions.get(2).value().value());
  }

  /**
   * Verifies that relative expressions of every supported shape resolve once a dateline
   * is present: a day word, a counted offset, and a coarser unit that keeps its own
   * granularity.
   */
  @Test
  void testCountedAndCoarserRelativesResolveAgainstTheDateline() {
    final String text = "Berlin, 14 July 2026. Shipped 3 days ago, audited last month, "
        + "due tomorrow.";

    final List<Annotation<TemporalExpression>> mentions = temporals(annotator, text);

    Assertions.assertEquals(4, mentions.size());
    Assertions.assertEquals("2026-07-11", mentions.get(1).value().value());
    Assertions.assertEquals(spanOf(text, "3 days ago"), mentions.get(1).span());
    Assertions.assertEquals("2026-06", mentions.get(2).value().value());
    Assertions.assertEquals(TemporalExpression.Granularity.MONTH,
        mentions.get(2).value().granularity());
    Assertions.assertEquals("2026-07-15", mentions.get(3).value().value());
  }

  /**
   * Verifies that a text without a day-granularity mention leaves relative expressions
   * unreported: a month mention is too coarse to date the document, exactly as it is
   * too coarse to elect the document date.
   */
  @Test
  void testTextWithoutADayMentionLeavesRelativesUnreported() {
    final String text = "The July 2026 report was filed yesterday.";

    final List<Annotation<TemporalExpression>> mentions = temporals(annotator, text);

    Assertions.assertEquals(1, mentions.size());
    Assertions.assertEquals(spanOf(text, "July 2026"), mentions.get(0).span());
    Assertions.assertEquals("2026-07", mentions.get(0).value().value());
  }

  /**
   * Verifies that a text holding nothing but a relative expression yields the temporal
   * layer present and empty rather than a mention resolved against the wall clock.
   */
  @Test
  void testRelativeWithoutAnyDateYieldsAnEmptyLayer() {
    final Document document = annotator.annotate(Document.of("we shipped it yesterday"));

    Assertions.assertTrue(document.get(TemporalAnnotator.TEMPORALS).isEmpty());
  }

  /**
   * Verifies the explicit reference constructor: a caller who knows the document date
   * from metadata resolves relative expressions without the text dating itself.
   */
  @Test
  void testFixedReferenceResolvesRelativesWithoutADateline() {
    final String text = "we shipped it yesterday";
    final TemporalAnnotator fixed = new TemporalAnnotator(
        new CursorTemporalExtractor(), LocalDate.of(2026, 7, 14));

    final List<Annotation<TemporalExpression>> mentions = temporals(fixed, text);

    Assertions.assertEquals(1, mentions.size());
    Assertions.assertEquals(spanOf(text, "yesterday"), mentions.get(0).span());
    Assertions.assertEquals("2026-07-13", mentions.get(0).value().value());
  }

  /**
   * Verifies that a fixed reference wins over the document's own dateline, so a caller
   * who supplies one is never overruled by the text.
   */
  @Test
  void testFixedReferenceWinsOverTheDateline() {
    final TemporalAnnotator fixed = new TemporalAnnotator(
        new CursorTemporalExtractor(), LocalDate.of(2020, 1, 2));

    final List<Annotation<TemporalExpression>> mentions =
        temporals(fixed, "Berlin, 14 July 2026. The buyer paid yesterday.");

    Assertions.assertEquals(2, mentions.size());
    Assertions.assertEquals("2020-01-01", mentions.get(1).value().value());
  }

  /**
   * Verifies that the annotator and {@link DocumentDateAnnotator} agree in a pipeline:
   * the mention that resolves the relative expressions is the one that elects the
   * document date.
   */
  @Test
  void testPipelineElectsTheSameDateThatResolvedTheRelatives() {
    final String text = "Berlin, 14 July 2026. The buyer paid yesterday.";
    final Document document = DocumentAnalyzer.builder()
        .add(new TemporalAnnotator(new CursorTemporalExtractor()))
        .add(new DocumentDateAnnotator())
        .build()
        .analyze(text);

    final List<Annotation<LocalDate>> dates =
        document.get(DocumentDateAnnotator.DOCUMENT_DATE);
    Assertions.assertEquals(1, dates.size());
    Assertions.assertEquals(LocalDate.of(2026, 7, 14), dates.get(0).value());
    Assertions.assertEquals(spanOf(text, "14 July 2026"), dates.get(0).span());
    Assertions.assertEquals("2026-07-13",
        document.get(TemporalAnnotator.TEMPORALS).get(1).value().value());
  }

  /**
   * Verifies that a resolved relative mention before the dateline does not replace the
   * absolute mention that supplied its reference date.
   */
  @Test
  void testRelativeBeforeDatelineDoesNotElectTheDocumentDate() {
    final String text = "Yesterday we filed. Dateline: 14 July 2026.";
    final Document document = DocumentAnalyzer.builder()
        .add(new TemporalAnnotator(new CursorTemporalExtractor()))
        .add(new DocumentDateAnnotator())
        .build()
        .analyze(text);

    Assertions.assertEquals(LocalDate.of(2026, 7, 14),
        document.get(DocumentDateAnnotator.DOCUMENT_DATE).get(0).value());
    Assertions.assertEquals(spanOf(text, "14 July 2026"),
        document.get(DocumentDateAnnotator.DOCUMENT_DATE).get(0).span());
    Assertions.assertEquals("2026-07-13",
        document.get(TemporalAnnotator.TEMPORALS).get(0).value().value());
  }

  /**
   * Verifies that a day-granularity mention whose value is not an ISO 8601 date, as a
   * third-party extractor may supply, elects no reference: the mentions stay the
   * absolute ones instead of the annotator failing or inventing a date.
   */
  @Test
  void testNonIsoDayValueElectsNoReference() {
    final List<Annotation<TemporalExpression>> mentions =
        temporals(new TemporalAnnotator(new UnIsoExtractor()), "Filed July 14, 2026.");

    Assertions.assertEquals(1, mentions.size());
    Assertions.assertEquals("July 14, 2026", mentions.get(0).value().value());
  }

  /**
   * Verifies that an extractor recognizing no relative expressions is unaffected: the
   * mentions of the reference-aware pass are reported as they come.
   */
  @Test
  void testExtractorWithoutRelativeSupportIsUnaffected() {
    final List<Annotation<TemporalExpression>> mentions =
        temporals(new TemporalAnnotator(new IsoOnlyExtractor()), "Filed 2026-07-14.");

    Assertions.assertEquals(1, mentions.size());
    Assertions.assertEquals("2026-07-14", mentions.get(0).value().value());
  }

  @Test
  void testInvalidArguments() {
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new TemporalAnnotator(null));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new TemporalAnnotator(null, LocalDate.of(2026, 7, 14)));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new TemporalAnnotator(new CursorTemporalExtractor(), null));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> annotator.annotate(null));
  }

  /**
   * A stand-in for a third-party extractor that reports a day-granularity mention whose
   * value is not an ISO 8601 date, and a relative mention as soon as it is given a
   * reference date.
   */
  private static final class UnIsoExtractor implements TemporalExtractor {

    @Override
    public List<TemporalExpression> extract(CharSequence text) {
      return List.of(new TemporalExpression(new Span(6, 19), "July 14, 2026",
          TemporalExpression.Granularity.DAY));
    }

    @Override
    public List<TemporalExpression> extract(CharSequence text, LocalDate reference) {
      final List<TemporalExpression> mentions = new ArrayList<>(extract(text));
      mentions.add(new TemporalExpression(new Span(19, 20), reference.toString(),
          TemporalExpression.Granularity.DAY));
      return mentions;
    }
  }

  /** A stand-in for an extractor that recognizes absolute mentions only. */
  private static final class IsoOnlyExtractor implements TemporalExtractor {

    @Override
    public List<TemporalExpression> extract(CharSequence text) {
      return List.of(new TemporalExpression(new Span(6, 16), "2026-07-14",
          TemporalExpression.Granularity.DAY));
    }
  }
}
