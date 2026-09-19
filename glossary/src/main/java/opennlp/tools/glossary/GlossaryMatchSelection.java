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

package opennlp.tools.glossary;

import java.util.ArrayList;
import java.util.List;

import opennlp.tools.util.Span;

/** Shared overlap selection for character and token matchers. */
final class GlossaryMatchSelection {

  /** Prevents construction of this utility. */
  private GlossaryMatchSelection() {
  }

  /**
   * Selects non-overlapping matches from candidates already sorted by start and priority.
   * Zero-length spans use {@link Span#intersects(Span)} like other spans.
   *
   * @param hits The sorted candidates with start, end, and registration index in positions 0 to 2.
   * @param entries The registrations referenced by the candidates.
   * @return The accepted matches in source order.
   */
  static List<GlossaryMatch> select(List<int[]> hits, List<GlossaryEntry> entries) {
    final List<GlossaryMatch> matches = new ArrayList<>();
    int lastEnd = 0;
    for (final int[] hit : hits) {
      if (hit[0] >= lastEnd) {
        final Span span = new Span(hit[0], hit[1]);
        if (!matches.isEmpty() && matches.getLast().span().intersects(span)) {
          continue;
        }
        final GlossaryEntry entry = entries.get(hit[2]);
        matches.add(new GlossaryMatch(span, entry.id(), entry.term()));
        lastEnd = hit[1];
      }
    }
    return matches;
  }
}
