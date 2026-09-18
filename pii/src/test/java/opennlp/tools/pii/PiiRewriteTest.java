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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.document.Annotation;
import opennlp.tools.util.Span;

/** Tests rewritten text and mapping of every source span in short documents. */
class PiiRewriteTest {

  private static final String TYPE = "id";
  private static final String ANNOTATION_TYPE = "token";
  private static final double PROBABILITY = 0.75;
  private static final HmacTokenizer TOKENIZER = new HmacTokenizer(
      "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

  private enum Mode {
    NUMBERED, BRACKETED, HMAC
  }

  /**
   * Enumerates zero, one and two non-overlapping replacements for each source text.
   *
   * @return The text, ordered replacement spans and rewriting method.
   */
  private static Stream<Arguments> replacements() {
    final List<Arguments> cases = new ArrayList<>();
    for (final String text : List.of("", "abcdefgh", "😀abc𝕒")) {
      final List<List<Span>> plans = new ArrayList<>();
      plans.add(List.of());
      for (int start = 0; start < text.length(); start++) {
        for (int end = start + 1; end <= text.length(); end++) {
          final Span first = new Span(start, end);
          plans.add(List.of(first));
          for (int nextStart = end; nextStart < text.length(); nextStart++) {
            for (int nextEnd = nextStart + 1; nextEnd <= text.length(); nextEnd++) {
              plans.add(List.of(first, new Span(nextStart, nextEnd)));
            }
          }
        }
      }
      for (final List<Span> plan : plans) {
        for (final Mode mode : Mode.values()) {
          cases.add(Arguments.of(text, plan, mode));
        }
      }
    }
    return cases.stream();
  }

  /**
   * Checks all source offsets and spans, including empty spans inside replacements.
   *
   * @param text The original text.
   * @param spans The replacement locations.
   * @param mode The rewriting method.
   */
  @ParameterizedTest
  @MethodSource("replacements")
  void testEverySourceSpan(String text, List<Span> spans, Mode mode) {
    final List<PiiMention> mentions = spans.stream()
        .map(span -> new PiiMention(span, TYPE, "value" + span.getStart())).toList();
    final PiiRewrite rewrite = rewrite(text, mentions, mode);
    final PiiRewrite reversed = rewrite(text, mentions.reversed(), mode);
    Assertions.assertEquals(rewrite.text(), reversed.text());
    Assertions.assertEquals(rewrite.mentions(), reversed.mentions());
    final List<Span> labels = rewrite.mentions().stream().map(PiiMention::span).toList();
    assertText(text, spans, rewrite);

    for (int start = 0; start <= text.length(); start++) {
      final int mappedStart = boundary(start, false, spans, labels);
      Assertions.assertEquals(mappedStart, rewrite.mapOffset(start));
      for (int end = start; end <= text.length(); end++) {
        final Span source = new Span(start, end, ANNOTATION_TYPE, PROBABILITY);
        final int mappedEnd = start == end ? mappedStart : boundary(end, true, spans, labels);
        final Span expected = new Span(mappedStart, mappedEnd, ANNOTATION_TYPE, PROBABILITY);
        final Span actual = rewrite.mapSpan(source);
        Assertions.assertEquals(expected, actual, () -> "source " + source);
        Assertions.assertEquals(PROBABILITY, actual.getProb());

        final Object value = new Object();
        final List<Annotation<Object>> mapped = rewrite.remap(List.of(new Annotation<>(source, value)));
        if (start == end) {
          Assertions.assertTrue(mapped.isEmpty(), () -> "empty source " + source);
        } else {
          Assertions.assertEquals(1, mapped.size());
          Assertions.assertEquals(expected, mapped.getFirst().span());
          Assertions.assertSame(value, mapped.getFirst().value());
        }
      }
    }
  }

  /**
   * Reconstructs expected text from source gaps and the returned replacement labels.
   *
   * @param text The source text.
   * @param spans The original replacement locations.
   * @param rewrite The result to check.
   */
  private void assertText(String text, List<Span> spans, PiiRewrite rewrite) {
    final StringBuilder expected = new StringBuilder();
    int copied = 0;
    for (int i = 0; i < spans.size(); i++) {
      expected.append(text, copied, spans.get(i).getStart());
      final PiiMention label = rewrite.mentions().get(i);
      Assertions.assertEquals(expected.length(), label.span().getStart());
      expected.append(label.normalized());
      Assertions.assertEquals(expected.length(), label.span().getEnd());
      Assertions.assertEquals(TYPE, label.type());
      copied = spans.get(i).getEnd();
    }
    expected.append(text, copied, text.length());
    Assertions.assertEquals(expected.toString(), rewrite.text());
  }

  /**
   * Maps a boundary by scanning original and rewritten replacement intervals.
   *
   * @param offset The source boundary.
   * @param end Whether an interior boundary maps to the replacement end.
   * @param sources Original replacement spans.
   * @param labels Rewritten replacement spans.
   * @return The rewritten boundary.
   */
  private int boundary(int offset, boolean end, List<Span> sources, List<Span> labels) {
    int shift = 0;
    for (int i = 0; i < sources.size(); i++) {
      final Span source = sources.get(i);
      final Span label = labels.get(i);
      if (offset <= source.getStart()) {
        return offset + shift;
      }
      if (offset < source.getEnd()) {
        return end ? label.getEnd() : label.getStart();
      }
      shift += label.length() - source.length();
    }
    return offset + shift;
  }

  /**
   * Selects a rewriter with shortening, expanding or keyed labels.
   *
   * @param text The source text.
   * @param mentions The mentions to replace.
   * @param mode The rewriting method.
   * @return The rewritten text and offset mapping.
   */
  private PiiRewrite rewrite(CharSequence text, List<PiiMention> mentions, Mode mode) {
    return switch (mode) {
      case NUMBERED -> new Pseudonymizer().rewrite(text, mentions);
      case BRACKETED -> new Pseudonymizer("[", "]").rewrite(text, mentions);
      case HMAC -> TOKENIZER.rewrite(text, mentions);
    };
  }

  /**
   * Rejects offsets outside the original source, including integer limits.
   *
   * @param offset The invalid offset.
   */
  @ParameterizedTest
  @ValueSource(ints = {-1, 9, Integer.MIN_VALUE, Integer.MAX_VALUE})
  void testRejectsInvalidOffsets(int offset) {
    final PiiRewrite rewrite = rewrite("abcdefgh", List.of(
        new PiiMention(new Span(2, 6), TYPE, "value")), Mode.NUMBERED);
    Assertions.assertThrows(IndexOutOfBoundsException.class, () -> rewrite.mapOffset(offset));
    if (offset >= 0) {
      Assertions.assertThrows(IndexOutOfBoundsException.class,
          () -> rewrite.mapSpan(new Span(offset, offset)));
      Assertions.assertThrows(IndexOutOfBoundsException.class,
          () -> rewrite.mapSpan(new Span(0, offset)));
    }
  }

  /** Checks ownership of the source text and mention list after rewriting. */
  @Test
  void testResultDoesNotRetainMutableInputs() {
    final StringBuilder source = new StringBuilder("abcdefgh");
    final List<PiiMention> mentions = new ArrayList<>();
    mentions.add(new PiiMention(new Span(2, 6), TYPE, "value"));
    final PiiRewrite rewrite = rewrite(source, mentions, Mode.NUMBERED);
    source.setLength(0);
    mentions.clear();

    Assertions.assertEquals("abID-1gh", rewrite.text());
    Assertions.assertEquals(8, rewrite.mapOffset(8));
    Assertions.assertEquals(new Span(2, 6), rewrite.mentions().getFirst().span());
    Assertions.assertThrows(UnsupportedOperationException.class, () -> rewrite.mentions().clear());
  }

  /** Checks the positional-only annotation contract of remap. */
  @Test
  void testRejectsAnnotationsWithoutSpans() {
    final PiiRewrite rewrite = rewrite("abcdefgh", List.of(), Mode.NUMBERED);
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> rewrite.remap(List.of(Annotation.of("category"))));
  }
}
