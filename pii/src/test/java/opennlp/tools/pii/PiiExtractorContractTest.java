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
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.document.Document;
import opennlp.tools.util.Span;

/** Tests rejection of invalid delegate output without exposing mention values. */
class PiiExtractorContractTest {

  private enum EntryPoint {
    COMPOSITE, NESTED, ANNOTATOR, ANNOTATED_COMPOSITE
  }

  /**
   * Provides missing results, null entries and out-of-range mention spans.
   *
   * @return The invalid result name, result and entry point.
   */
  private static Stream<Arguments> invalidResults() {
    final PiiMention valid = new PiiMention(new Span(0, 4), "valid", "private-valid");
    final PiiMention outside = new PiiMention(new Span(1, 8), "bad", "private-outside");
    final List<Arguments> cases = new ArrayList<>();
    final List<List<PiiMention>> results = Arrays.asList(null,
        Arrays.asList((PiiMention) null), Arrays.asList(valid, null),
        List.of(outside), List.of(valid, outside),
        List.of(new PiiMention(new Span(5, 6), "bad", "private-past-end")),
        List.of(new PiiMention(new Span(0, Integer.MAX_VALUE), "bad", "private-huge")));
    for (int i = 0; i < results.size(); i++) {
      for (final EntryPoint entry : EntryPoint.values()) {
        cases.add(Arguments.of(i, results.get(i), entry));
      }
    }
    return cases.stream();
  }

  /**
   * Rejects invalid results even if overlap resolution would otherwise drop them.
   *
   * @param index The printable case identifier.
   * @param returned The invalid delegate result.
   * @param entry The entry point.
   */
  @ParameterizedTest(name = "invalid result {0}, via={2}")
  @MethodSource("invalidResults")
  void testRejectsInvalidResults(int index, List<PiiMention> returned, EntryPoint entry) {
    final PiiExtractor invalid = text -> returned;
    final PiiExtractor composite = new CompositePiiExtractor(invalid);
    final Document source = Document.of("abcd");
    final IllegalArgumentException failure = Assertions.assertThrows(IllegalArgumentException.class, () -> {
      switch (entry) {
        case COMPOSITE -> composite.extract(source.text());
        case NESTED -> new CompositePiiExtractor(composite).extract(source.text());
        case ANNOTATOR -> new PiiAnnotator(invalid).annotate(source);
        case ANNOTATED_COMPOSITE -> new PiiAnnotator(composite).annotate(source);
        default -> throw new AssertionError(entry);
      }
    }, "invalid result " + index);
    Assertions.assertFalse(failure.getMessage().contains("private"));
    Assertions.assertTrue(source.layers().isEmpty());
  }

  /** Checks that exceptions raised by the delegate retain their identity. */
  @Test
  void testDelegateFailurePropagates() {
    final IllegalStateException failure = new IllegalStateException("detector unavailable");
    final PiiExtractor failing = text -> {
      throw failure;
    };

    Assertions.assertSame(failure, Assertions.assertThrows(IllegalStateException.class,
        () -> new CompositePiiExtractor(failing).extract("x")));
    Assertions.assertSame(failure, Assertions.assertThrows(IllegalStateException.class,
        () -> new PiiAnnotator(failing).annotate(Document.of("x"))));
  }

  /**
   * Checks valid empty results on empty and non-empty text.
   *
   * @param text The input text.
   */
  @ParameterizedTest
  @ValueSource(strings = {"", "x", "😀"})
  void testAcceptsEmptyResults(String text) {
    final PiiExtractor empty = ignored -> List.of();
    Assertions.assertEquals(List.of(), new CompositePiiExtractor(empty).extract(text));
    final Document result = new PiiAnnotator(empty).annotate(Document.of(text));
    Assertions.assertTrue(result.layers().contains(PiiAnnotator.PII));
    Assertions.assertEquals(List.of(), result.get(PiiAnnotator.PII));
  }

  /**
   * Preserves acceptance of zero-length mentions at a valid text boundary.
   *
   * @param text The input text.
   */
  @ParameterizedTest
  @ValueSource(strings = {"", "x", "😀"})
  void testKeepsExistingEmptySpanBehavior(String text) {
    final PiiMention mention = new PiiMention(new Span(text.length(), text.length()), "custom", "marker");
    final PiiExtractor emptySpan = ignored -> List.of(mention);
    Assertions.assertEquals(List.of(mention), new CompositePiiExtractor(emptySpan).extract(text));
    final Document result = new PiiAnnotator(emptySpan).annotate(Document.of(text));
    Assertions.assertEquals(mention, result.get(PiiAnnotator.PII).getFirst().value());
    Assertions.assertEquals(text + "CUSTOM-1", new Pseudonymizer().rewrite(result).text());
  }
}
