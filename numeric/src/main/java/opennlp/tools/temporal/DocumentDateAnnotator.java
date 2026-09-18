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
import java.util.List;
import java.util.Set;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.DocumentAnnotator;
import opennlp.tools.document.DocumentAnnotators;
import opennlp.tools.document.LayerKey;
import opennlp.tools.document.Layers;

/**
 * Selects the first absolute day mention by text position from
 * {@link TemporalAnnotator#TEMPORALS} and adds {@link #DOCUMENT_DATE}.
 *
 * <p>Annotation-list order does not determine the selected date. Equal start offsets
 * retain list order. Relative expressions and coarser granularities are excluded;
 * without an absolute day mention, the output layer is empty. The source layer is
 * unchanged, and the date annotation retains the selected mention's span.</p>
 *
 * <p>Input value spans must match their annotation spans. The selected value must be
 * a valid calendar date accepted by {@link LocalDate#parse(CharSequence)}.</p>
 *
 * <p>Instances are stateless and safe to share between threads.</p>
 *
 * @see <a href="https://www.iso.org/iso-8601-date-and-time-format.html">ISO 8601</a>
 * @since 3.0.0
 */
public class DocumentDateAnnotator implements DocumentAnnotator {

  /**
   * The document's reference date: at most one annotation, on the selected mention's span.
   */
  public static final LayerKey<LocalDate> DOCUMENT_DATE =
      Layers.key("document.date", LocalDate.class);

  /**
   * {@inheritDoc}
   *
   * <p>Parses the absolute day mention with the earliest start offset.</p>
   *
   * @throws IllegalArgumentException If the output layer is present, a temporal value
   *         has a different span from its annotation, or the selected value is not a
   *         valid calendar date.
   */
  @Override
  public Document annotate(Document document) {
    DocumentAnnotators.requireLayers(document, TemporalAnnotator.TEMPORALS);
    if (document.layers().contains(DOCUMENT_DATE)) {
      throw new IllegalArgumentException("layer is already present: " + DOCUMENT_DATE);
    }
    Annotation<TemporalExpression> selected = null;
    for (final Annotation<TemporalExpression> mention
        : document.get(TemporalAnnotator.TEMPORALS)) {
      if (!mention.span().equals(mention.value().span())) {
        throw new IllegalArgumentException("temporal span differs from annotation span: " + mention.span());
      }
      if (mention.value().granularity() == TemporalExpression.Granularity.DAY
          && mention.value().origin() == TemporalExpression.Origin.ABSOLUTE
          && (selected == null || mention.span().getStart() < selected.span().getStart())) {
        selected = mention;
      }
    }
    if (selected == null) {
      return document.with(DOCUMENT_DATE, List.of());
    }
    final LocalDate date;
    try {
      date = LocalDate.parse(selected.value().value());
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("not an ISO 8601 day value at "
          + selected.span() + ": " + selected.value().value(), e);
    }
    return document.with(DOCUMENT_DATE, List.of(new Annotation<>(selected.span(), date)));
  }

  /** {@inheritDoc} */
  @Override
  public Set<LayerKey<?>> requires() {
    return Set.of(TemporalAnnotator.TEMPORALS);
  }

  /** {@inheritDoc} */
  @Override
  public Set<LayerKey<?>> provides() {
    return Set.of(DOCUMENT_DATE);
  }
}
