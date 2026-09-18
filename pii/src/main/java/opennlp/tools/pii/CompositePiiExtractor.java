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

package opennlp.tools.pii;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * Combines PII extractors into a result in text order, retaining overlapping mentions.
 *
 * <p>Candidates are ordered by start offset, then descending length, then
 * {@link PiiTypePriority type priority}, then delegate order. Duplicates compare by
 * {@link PiiMention#equals(Object)}; the first original mention is retained.
 * Different types or normalized values on the same span remain separate.</p>
 *
 * <p>Nested composites contribute their individual extractors to the same ordering pass,
 * in depth-first, left-to-right order. Grouping the same ordered extractors does not
 * change the result. Other extractors contribute their returned mentions; their internal
 * filtering is unchanged. {@link #extractors()} retains the configured nested structure.</p>
 *
 * <p>Thread safety depends on the delegates. See {@link PiiPacks} for built-in
 * combinations.</p>
 *
 * @since 3.0.0
 */
public final class CompositePiiExtractor implements PiiExtractor {

  private final List<PiiExtractor> extractors;

  /**
   * Initializes a composite over the given extractors.
   *
   * @param extractors The extractors to merge, in order for equal offsets and type ranks.
   *                   Must not be {@code null} or empty and must not contain
   *                   {@code null}.
   * @throws IllegalArgumentException Thrown if {@code extractors} is {@code null} or
   *         empty, or contains {@code null}.
   */
  public CompositePiiExtractor(PiiExtractor... extractors) {
    this(extractors == null ? null : Arrays.asList(extractors));
  }

  /**
   * Initializes a composite over the given extractors.
   *
   * @param extractors The extractors to merge, in order for equal offsets and type ranks.
   *                   Must not be {@code null} or empty and must not contain
   *                   {@code null}.
   * @throws IllegalArgumentException Thrown if {@code extractors} is {@code null} or
   *         empty, or contains {@code null}.
   */
  public CompositePiiExtractor(List<PiiExtractor> extractors) {
    if (extractors == null || extractors.isEmpty()) {
      throw new IllegalArgumentException("extractors must not be null or empty");
    }
    for (final PiiExtractor extractor : extractors) {
      if (extractor == null) {
        throw new IllegalArgumentException("extractors must not contain null");
      }
    }
    this.extractors = List.copyOf(extractors);
  }

  /**
   * Returns the configured delegates, including nested composites.
   *
   * @return The immutable delegate list in the supplied order.
   */
  public List<PiiExtractor> extractors() {
    return extractors;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Retains overlapping detections and removes equal duplicates.</p>
   *
   * @throws IllegalArgumentException Thrown if {@code text} is null, or a delegate
   *         returns a null result, a null mention or a mention outside the input text.
   */
  @Override
  public List<PiiMention> extract(CharSequence text) {
    if (text == null) {
      throw new IllegalArgumentException("text must not be null");
    }
    final List<Hits.Hit> hits = new ArrayList<>();
    final Deque<PiiExtractor> pending = new ArrayDeque<>(extractors);
    while (!pending.isEmpty()) {
      final PiiExtractor extractor = pending.removeFirst();
      if (extractor instanceof CompositePiiExtractor composite) {
        for (int i = composite.extractors.size() - 1; i >= 0; i--) {
          pending.addFirst(composite.extractors.get(i));
        }
      } else {
        for (final PiiMention mention : PiiExtraction.extract(extractor, text)) {
          Hits.add(hits, mention);
        }
      }
    }
    return Hits.resolve(hits);
  }
}
