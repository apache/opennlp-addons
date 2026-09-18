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
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import opennlp.tools.util.Span;

/** Compares composite merging with exhaustive interval comparisons. */
class CompositeGlossaryOracleTest {

  /** Source length for generated spans. */
  private static final int TEXT_LENGTH = 400;

  /** Number of independently generated delegate sets per seed. */
  private static final int SCENARIOS = 250;

  /** Source text shared by the generated delegate results. */
  private static final String TEXT = "x".repeat(TEXT_LENGTH);

  /**
   * Checks priority selection and result ordering for sorted, non-overlapping delegates.
   *
   * @param seed The reproducible random seed.
   * @param includeEmpty Whether some generated matches have zero-length spans.
   */
  @ParameterizedTest
  @CsvSource({"7,false", "41,false", "2026,false", "65537,false",
      "7,true", "41,true", "2026,true", "65537,true"})
  void testMatchesExhaustiveSelection(long seed, boolean includeEmpty) {
    final Random random = new Random(seed);
    for (int scenario = 0; scenario < SCENARIOS; scenario++) {
      final List<List<GlossaryMatch>> results = new ArrayList<>();
      final List<GlossaryMatcher> delegates = new ArrayList<>();
      final int delegateCount = 1 + random.nextInt(10);
      for (int delegate = 0; delegate < delegateCount; delegate++) {
        final List<GlossaryMatch> hits = generatedHits(random, delegate, includeEmpty);
        results.add(hits);
        delegates.add(text -> {
          Assertions.assertSame(TEXT, text);
          return hits;
        });
      }
      Assertions.assertEquals(exhaustiveSelection(results),
          new CompositeGlossaryMatcher(delegates).match(TEXT),
          "seed=" + seed + ", scenario=" + scenario + ", includeEmpty=" + includeEmpty);
    }
  }

  /**
   * Generates intervals in source order without overlap.
   *
   * @param random The scenario generator.
   * @param delegate The delegate index used in match identifiers.
   * @param includeEmpty Whether to include zero-length spans.
   * @return An immutable delegate result, possibly empty.
   */
  private List<GlossaryMatch> generatedHits(Random random, int delegate, boolean includeEmpty) {
    final List<GlossaryMatch> hits = new ArrayList<>();
    final int count = random.nextInt(20);
    int cursor = random.nextInt(20);
    for (int hit = 0; hit < count; hit++) {
      final int length = includeEmpty && random.nextInt(6) == 0 ? 0 : 1 + random.nextInt(35);
      int start = cursor + random.nextInt(12);
      if (!hits.isEmpty() && start == cursor
          && (length == 0 || hits.getLast().span().length() == 0)) {
        start++;
      }
      if (start >= TEXT_LENGTH) {
        break;
      }
      final int end = Math.min(TEXT_LENGTH, start + length);
      hits.add(new GlossaryMatch(new Span(start, end), delegate + ":" + hit, "term"));
      cursor = end;
    }
    return List.copyOf(hits);
  }

  /**
   * Compares each candidate with all accepted intervals before sorting the result.
   *
   * @param delegates The delegate results in priority order.
   * @return The accepted matches in source order.
   */
  private List<GlossaryMatch> exhaustiveSelection(List<List<GlossaryMatch>> delegates) {
    final List<GlossaryMatch> accepted = new ArrayList<>();
    for (final List<GlossaryMatch> candidates : delegates) {
      for (final GlossaryMatch candidate : candidates) {
        boolean overlaps = false;
        for (final GlossaryMatch previous : accepted) {
          final int start = candidate.span().getStart();
          final int end = candidate.span().getEnd();
          final int previousStart = previous.span().getStart();
          final int previousEnd = previous.span().getEnd();
          if (Math.max(start, previousStart) < Math.min(end, previousEnd)
              || start == end && previousStart <= start && start <= previousEnd
              || previousStart == previousEnd && start <= previousStart && previousStart <= end) {
            overlaps = true;
            break;
          }
        }
        if (!overlaps) {
          accepted.add(candidate);
        }
      }
    }
    accepted.sort(Comparator.comparingInt(hit -> hit.span().getStart()));
    return accepted;
  }
}
