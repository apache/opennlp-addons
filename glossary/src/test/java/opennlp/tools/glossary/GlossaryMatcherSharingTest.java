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
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import opennlp.tools.util.Span;

import static opennlp.tools.glossary.GlossaryTestSupport.englishStemmingAnalyzer;

/** Tests independent calls through shared matchers and copied registrations. */
class GlossaryMatcherSharingTest {

  /** Built-in matcher combinations tested with a thread-safe analyzer. */
  private enum MatcherKind {
    EXACT, INFLECTED, COMPOSITE
  }

  /** Terms shared by the matcher variants. */
  private static final List<GlossaryEntry> ENTRIES = List.of(
      new GlossaryEntry("FOOD", "hot dog"), new GlossaryEntry("CITY", "New York City"));

  /**
   * Runs independent texts concurrently through one matcher instance.
   *
   * @param kind The matcher variant.
   * @throws Exception Thrown if a worker fails or the run times out.
   */
  @ParameterizedTest
  @EnumSource(MatcherKind.class)
  void testConcurrentCallsHaveIndependentSpans(MatcherKind kind) throws Exception {
    final GlossaryMatcher matcher = matcher(kind, ENTRIES);
    final List<Callable<List<GlossaryMatch>>> calls = new ArrayList<>();
    final List<List<GlossaryMatch>> expected = new ArrayList<>();
    for (int index = 0; index < 64; index++) {
      final String prefix = "\uD83D\uDE80 ".repeat(index % 5);
      final boolean plural = index % 2 == 0;
      final String food = plural ? "Hot dogs" : "Hot dog";
      final String text = prefix + food + " in New York City.";
      calls.add(() -> matcher.match(new StringBuilder(text)));
      final List<GlossaryMatch> matches = new ArrayList<>();
      if (kind != MatcherKind.EXACT || !plural) {
        matches.add(new GlossaryMatch(new Span(prefix.length(), prefix.length() + food.length()),
            "FOOD", "hot dog"));
      }
      final int cityStart = prefix.length() + food.length() + 4;
      matches.add(new GlossaryMatch(new Span(cityStart, cityStart + 13), "CITY", "New York City"));
      expected.add(matches);
    }
    try (var executor = Executors.newFixedThreadPool(4)) {
      final var results = executor.invokeAll(calls, 30, TimeUnit.SECONDS);
      for (int index = 0; index < results.size(); index++) {
        Assertions.assertFalse(results.get(index).isCancelled(), "call=" + index);
        Assertions.assertEquals(expected.get(index), results.get(index).get(), "call=" + index);
      }
    }
  }

  /**
   * Confirms that later changes to the registration list cannot change a matcher.
   *
   * @param kind The matcher variant.
   */
  @ParameterizedTest
  @EnumSource(MatcherKind.class)
  void testRegistrationListIsCopied(MatcherKind kind) {
    final List<GlossaryEntry> entries = new ArrayList<>(ENTRIES);
    final GlossaryMatcher matcher = matcher(kind, entries);
    entries.clear();
    entries.add(new GlossaryEntry("OTHER", "hot dog"));
    Assertions.assertEquals(List.of(new GlossaryMatch(new Span(0, 7), "FOOD", "hot dog")),
        matcher.match("hot dog"));
  }

  /**
   * Builds a matcher with case normalization and optional English stemming.
   *
   * @param kind The matcher variant.
   * @param entries The registrations to copy.
   * @return The matcher.
   */
  private GlossaryMatcher matcher(MatcherKind kind, List<GlossaryEntry> entries) {
    return switch (kind) {
      case EXACT -> new AhoCorasickGlossaryMatcher(entries, true);
      case INFLECTED -> new TermAnalyzingGlossaryMatcher(entries, englishStemmingAnalyzer());
      case COMPOSITE -> new CompositeGlossaryMatcher(List.of(
          new AhoCorasickGlossaryMatcher(entries, true),
          new TermAnalyzingGlossaryMatcher(entries, englishStemmingAnalyzer())));
    };
  }
}
