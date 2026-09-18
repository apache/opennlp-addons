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
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.document.Document;
import opennlp.tools.util.Span;

/** Tests overlap retention through nested composites. */
class PiiCompositionTest {

  /**
   * Provides adjacent, contained, crossing and equal spans under two type-rank orders.
   *
   * @return Three spans, the type-rank selection and the nesting direction.
   */
  private static Stream<Arguments> arrangements() {
    final List<Span> spans = List.of(new Span(0, 1), new Span(0, 2), new Span(1, 3),
        new Span(2, 4), new Span(3, 4));
    final List<Arguments> cases = new ArrayList<>();
    for (final Span first : spans) {
      for (final Span second : spans) {
        for (final Span third : spans) {
          for (final boolean ranked : new boolean[] {false, true}) {
            for (final boolean leftNested : new boolean[] {false, true}) {
              cases.add(Arguments.of(first, second, third, ranked, leftNested));
            }
          }
        }
      }
    }
    return cases.stream();
  }

  /**
   * Checks that grouping the same ordered delegates does not change their result.
   *
   * @param first The first candidate span.
   * @param second The second candidate span.
   * @param third The third candidate span.
   * @param ranked Whether the candidates use built-in type priorities.
   * @param leftNested Whether to group the first two delegates.
   */
  @ParameterizedTest
  @MethodSource("arrangements")
  void testGroupingKeepsAllCandidates(Span first, Span second, Span third,
      boolean ranked, boolean leftNested) {
    final List<String> types = ranked
        ? List.of(PiiMention.TYPE_PHONE, PiiMention.TYPE_CARD, PiiMention.TYPE_EMAIL)
        : List.of("first", "second", "third");
    final PiiExtractor a = fixed(new PiiMention(first, types.get(0), "a"));
    final PiiExtractor b = fixed(new PiiMention(second, types.get(1), "b"));
    final PiiExtractor c = fixed(new PiiMention(third, types.get(2), "c"));
    final CompositePiiExtractor nested = leftNested
        ? new CompositePiiExtractor(new CompositePiiExtractor(a, b), c)
        : new CompositePiiExtractor(a, new CompositePiiExtractor(b, c));
    final List<PiiMention> expected = new CompositePiiExtractor(a, b, c).extract("abcd");

    Assertions.assertEquals(expected, nested.extract("abcd"));
    Assertions.assertEquals(expected, new PiiAnnotator(nested).annotate(Document.of("abcd"))
        .get(PiiAnnotator.PII).stream().map(annotation -> annotation.value()).toList());
  }

  /**
   * Checks the manual's overlapping detectors with optional Unicode before the mentions.
   *
   * @param prefix The text before the detector spans.
   */
  @ParameterizedTest
  @ValueSource(strings = {"", "😀 ", "𝕒 ", "e\u0301 "})
  void testNestedOverlapDoesNotLeaveAMentionUnmasked(String prefix) {
    final int offset = prefix.length();
    final PiiMention amy = new PiiMention(new Span(offset, offset + 3), "person", "Amy");
    final PiiMention middle = new PiiMention(new Span(offset + 1, offset + 5), "custom", "my B");
    final PiiMention bob = new PiiMention(new Span(offset + 4, offset + 7), "person", "Bob");
    final PiiExtractor combined = new CompositePiiExtractor(fixed(amy),
        new CompositePiiExtractor(fixed(middle), fixed(bob)));
    final Document result = new PiiAnnotator(combined).annotate(Document.of(prefix + "Amy Bob"));

    Assertions.assertEquals(List.of(amy, middle, bob), result.get(PiiAnnotator.PII).stream()
        .map(annotation -> annotation.value()).toList());
    Assertions.assertEquals(prefix + "*******", Masker.mask(result, PiiAnnotator.PII, '*'));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new Pseudonymizer().rewrite(result));
  }

  /** Checks delegate invocation order and preserves the configured nested view. */
  @Test
  void testDepthFirstDelegateOrderAndOriginalView() {
    final List<Integer> calls = new ArrayList<>();
    final List<PiiExtractor> delegates = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      final int index = i;
      delegates.add(text -> {
        calls.add(index);
        return List.of(new PiiMention(new Span(0, 1), "custom-" + index, "value-" + index));
      });
    }
    final CompositePiiExtractor left = new CompositePiiExtractor(delegates.subList(0, 2));
    final CompositePiiExtractor right = new CompositePiiExtractor(delegates.subList(2, 4));
    final CompositePiiExtractor outer = new CompositePiiExtractor(left, right);

    Assertions.assertEquals(List.of("custom-0", "custom-1", "custom-2", "custom-3"),
        outer.extract("x").stream().map(PiiMention::type).toList());
    Assertions.assertEquals(List.of(0, 1, 2, 3), calls);
    Assertions.assertEquals(List.of(left, right), outer.extractors());
    Assertions.assertSame(left, outer.extractors().getFirst());
  }

  /** Checks that deeply nested composites do not require recursive extraction calls. */
  @Test
  void testDeepComposition() {
    final PiiMention mention = new PiiMention(new Span(0, 1), "custom", "x");
    PiiExtractor extractor = fixed(mention);
    for (int depth = 0; depth < 10_000; depth++) {
      extractor = new CompositePiiExtractor(extractor);
    }
    final PiiExtractor nested = extractor;

    Assertions.assertEquals(List.of(mention), Assertions.assertDoesNotThrow(() -> nested.extract("x")));
  }

  /**
   * Checks concurrent nested extraction with shared stateless delegates.
   *
   * @throws Exception If a worker fails or does not finish within the time limit.
   */
  @Test
  @Timeout(60)
  void testConcurrentComposition() throws Exception {
    final Span first = new Span(0, 2);
    final Span middle = new Span(1, 3);
    final Span last = new Span(2, 4);
    final PiiExtractor extractor = new CompositePiiExtractor(covering(first),
        new CompositePiiExtractor(covering(middle), covering(last)));
    final CountDownLatch ready = new CountDownLatch(8);
    final List<Callable<Void>> calls = new ArrayList<>();
    for (int worker = 0; worker < 8; worker++) {
      final String text = "a" + worker + "b" + worker;
      final List<PiiMention> expected = List.of(
          new PiiMention(first, "custom", text.substring(0, 2)),
          new PiiMention(middle, "custom", text.substring(1, 3)),
          new PiiMention(last, "custom", text.substring(2, 4)));
      calls.add(() -> {
        ready.countDown();
        Assertions.assertTrue(ready.await(10, TimeUnit.SECONDS));
        for (int run = 0; run < 32; run++) {
          Assertions.assertEquals(expected, extractor.extract(text));
        }
        return null;
      });
    }
    try (var executor = Executors.newFixedThreadPool(8)) {
      for (final var result : executor.invokeAll(calls, 30, TimeUnit.SECONDS)) {
        result.get();
      }
    }
  }

  /**
   * Provides a detector that returns the text at one span.
   *
   * @param span The candidate span.
   * @return The detector.
   */
  private PiiExtractor covering(Span span) {
    return text -> List.of(new PiiMention(span, "custom", span.getCoveredText(text).toString()));
  }

  /**
   * Provides a detector that returns one candidate.
   *
   * @param mention The candidate.
   * @return The detector.
   */
  private PiiExtractor fixed(PiiMention mention) {
    return text -> List.of(mention);
  }
}
