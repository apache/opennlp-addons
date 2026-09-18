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
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.DocumentAnnotator;
import opennlp.tools.document.LayerKey;
import opennlp.tools.document.Layers;
import opennlp.tools.util.Span;

/**
 * Extracts calendar mentions from the original document text into {@link #TEMPORALS}.
 *
 * <p>The default mode extracts absolute mentions, selects the first absolute day as
 * the reference date, then extracts again to resolve relative expressions. The final
 * layer contains the results of that second pass. If no reference date is available,
 * the absolute results are used without consulting the system clock.</p>
 *
 * <p>A fixed date supplied to {@link #TemporalAnnotator(TemporalExtractor, LocalDate)}
 * takes precedence over dates in the text and requires one extraction pass.</p>
 *
 * <p>No input layers are required. Each extraction pass must return non-null results
 * in text order, without overlaps, and within the text bounds. The absolute pass is
 * validated before selecting a reference date.</p>
 *
 * @since 3.0.0
 */
public class TemporalAnnotator implements DocumentAnnotator {

  /**
   * Temporal mentions; each annotation covers one mention and carries its normalized
   * {@link TemporalExpression}.
   */
  public static final LayerKey<TemporalExpression> TEMPORALS =
      Layers.key("temporals", TemporalExpression.class);

  private static final String EXTRACTOR_REQUIRED = "extractor must not be null";

  private final TemporalExtractor extractor;

  /** The configured reference date, or {@code null} to select one from the text. */
  private final LocalDate reference;

  /**
   * Initializes the adapter, electing the reference date from each document's own
   * dateline.
   *
   * @param extractor The extractor to delegate to. Must not be {@code null}.
   * @throws IllegalArgumentException Thrown if {@code extractor} is {@code null}.
   */
  public TemporalAnnotator(TemporalExtractor extractor) {
    if (extractor == null) {
      throw new IllegalArgumentException(EXTRACTOR_REQUIRED);
    }
    this.extractor = extractor;
    this.reference = null;
  }

  /**
   * Initializes the adapter with a fixed reference date, for documents whose date is
   * known from metadata rather than from the text.
   *
   * @param extractor The extractor to delegate to. Must not be {@code null}.
   * @param reference The date relative expressions resolve against, used for every
   *                  document and in preference to any dateline in it. Must not be
   *                  {@code null}.
   * @throws IllegalArgumentException Thrown if a parameter is {@code null}.
   */
  public TemporalAnnotator(TemporalExtractor extractor, LocalDate reference) {
    if (extractor == null) {
      throw new IllegalArgumentException(EXTRACTOR_REQUIRED);
    }
    if (reference == null) {
      throw new IllegalArgumentException("reference must not be null");
    }
    this.extractor = extractor;
    this.reference = reference;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Adds validated mentions under {@link #TEMPORALS}.</p>
   *
   * @throws IllegalArgumentException Thrown if the output layer is present or the
   *         extractor returns a null result, null mention, or invalid span sequence.
   */
  @Override
  public Document annotate(Document document) {
    if (document == null) {
      throw new IllegalArgumentException("document must not be null");
    }
    if (document.layers().contains(TEMPORALS)) {
      throw new IllegalArgumentException("layer is already present: " + TEMPORALS);
    }
    final List<Annotation<TemporalExpression>> mentions = new ArrayList<>();
    for (final TemporalExpression expression : resolved(document.text())) {
      mentions.add(new Annotation<>(expression.span(), expression));
    }
    return document.with(TEMPORALS, mentions);
  }

  /**
   * Extracts and validates mentions using a configured or textual reference date.
   *
   * @param text The document text. Must not be {@code null}.
   * @return The non-null mentions in text order.
   * @throws IllegalArgumentException If an extraction result is invalid.
   */
  private List<TemporalExpression> resolved(CharSequence text) {
    if (reference != null) {
      return checked(extractor.extract(text, reference), text.length());
    }
    final List<TemporalExpression> absolute = checked(extractor.extract(text), text.length());
    final LocalDate dateline = dateline(absolute);
    return dateline == null ? absolute : checked(extractor.extract(text, dateline), text.length());
  }

  /**
   * Validates one extraction result before using it for date selection or annotation.
   *
   * @param mentions The provider result.
   * @param textLength The document length in UTF-16 code units.
   * @return The validated result without copying or reordering.
   * @throws IllegalArgumentException If the result is null, contains null, or has
   *         out-of-bounds, unordered, or overlapping spans.
   */
  private List<TemporalExpression> checked(List<TemporalExpression> mentions, int textLength) {
    if (mentions == null) {
      throw new IllegalArgumentException("extractor returned a null result");
    }
    int previousEnd = 0;
    for (final TemporalExpression mention : mentions) {
      if (mention == null) {
        throw new IllegalArgumentException("extractor returned a null mention");
      }
      final Span span = mention.span();
      if (span.getEnd() > textLength) {
        throw new IllegalArgumentException("extractor returned a span beyond the text: " + span);
      }
      if (span.getStart() < previousEnd) {
        throw new IllegalArgumentException("extractor returned an unordered or overlapping span: " + span);
      }
      previousEnd = span.getEnd();
    }
    return mentions;
  }

  /**
   * Parses the first absolute day mention as a reference date.
   *
   * <p>If that value is invalid, the absolute results are retained without resolving
   * relative expressions. {@link DocumentDateAnnotator} rejects the invalid value.</p>
   *
   * @param mentions The absolute mentions in text order. Must not be {@code null}.
   * @return The reference date, or {@code null} if no absolute day exists or the first
   *         absolute day value is invalid.
   */
  private LocalDate dateline(List<TemporalExpression> mentions) {
    for (final TemporalExpression mention : mentions) {
      if (mention.granularity() == TemporalExpression.Granularity.DAY
          && mention.origin() == TemporalExpression.Origin.ABSOLUTE) {
        try {
          return LocalDate.parse(mention.value());
        } catch (DateTimeParseException e) {
          return null;
        }
      }
    }
    return null;
  }

  /** {@inheritDoc} */
  @Override
  public Set<LayerKey<?>> provides() {
    return Set.of(TEMPORALS);
  }
}
