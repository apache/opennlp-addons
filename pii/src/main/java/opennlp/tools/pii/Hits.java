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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import opennlp.tools.util.Span;

/**
 * Candidate storage and ordering for built-in and composite extractors.
 *
 * <p>Candidates are ordered by start offset, descending length, type rank and insertion
 * order. Overlapping mentions are retained; equal mentions are combined.</p>
 */
final class Hits {

  /**
   * A candidate and the values used to sort it.
   *
   * @param start The candidate start offset in the scanned text, inclusive.
   * @param end The candidate end offset in the scanned text, exclusive.
   * @param priority The type rank for candidates with equal offsets.
   * @param mention The candidate mention.
   */
  record Hit(int start, int end, int priority, PiiMention mention) {
  }

  /** Prevents construction. */
  private Hits() {
  }

  /**
   * Records one candidate.
   *
   * @param hits The candidate collector.
   * @param start The candidate start offset, inclusive.
   * @param end The candidate end offset, exclusive.
   * @param type The mention type.
   * @param normalized The normalized form of the mention.
   */
  static void add(List<Hit> hits, int start, int end, String type, String normalized) {
    add(hits, new PiiMention(new Span(start, end), type, normalized));
  }

  /**
   * Adds an existing mention without copying it.
   *
   * @param hits The candidate collector.
   * @param mention The non-null candidate.
   */
  static void add(List<Hit> hits, PiiMention mention) {
    hits.add(new Hit(mention.span().getStart(), mention.span().getEnd(),
        PiiTypePriority.rank(mention.type()), mention));
  }

  /**
   * Sorts candidates by start, length and type rank, retaining overlaps. Stable sorting
   * preserves insertion order for equal offsets and ranks. Duplicates compare by
   * {@link PiiMention#equals(Object)}; the first original mention is retained.
   *
   * @param hits The raw candidates; this list is sorted in place.
   * @return The distinct mentions in text order.
   */
  static List<PiiMention> resolve(List<Hit> hits) {
    hits.sort((a, b) -> {
      if (a.start() != b.start()) {
        return Integer.compare(a.start(), b.start());
      }
      if (a.end() != b.end()) {
        return Integer.compare(b.end(), a.end());
      }
      return Integer.compare(a.priority(), b.priority());
    });
    final List<PiiMention> mentions = new ArrayList<>();
    final Set<PiiMention> seen = new HashSet<>();
    for (final Hit hit : hits) {
      if (seen.add(hit.mention())) {
        mentions.add(hit.mention());
      }
    }
    return mentions;
  }
}
