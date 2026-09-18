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
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.LayerKey;
import opennlp.tools.util.Span;

/** Tests masking as the union of the positions each original span redacts. */
class MaskerOverlapTest {

  private static final LayerKey<String> FIRST = LayerKey.of("first", String.class);
  private static final LayerKey<String> SECOND = LayerKey.of("second", String.class);
  private static final LayerKey<MaskPolicy> POLICIES = LayerKey.of("policies", MaskPolicy.class);

  private enum Mode {
    SEPARATE_LAYERS, SINGLE_LAYER, POLICY_SELECTOR
  }

  /**
   * Provides policies and short texts for exhaustive UTF-16 span-pair checks.
   *
   * @return Text, mask character, format option, trailing count and overload.
   */
  private static Stream<Arguments> combinations() {
    final List<Arguments> cases = new ArrayList<>();
    for (final String text : List.of("123456", "12-34", "a𝕒b3", "𝕒-𝕓", "*12X")) {
      for (final char mask : new char[] {'*', 'X', '7', '-'}) {
        for (final boolean keepFormat : new boolean[] {false, true}) {
          for (final int trailing : new int[] {0, 1, 2, 6}) {
            for (final Mode mode : Mode.values()) {
              cases.add(Arguments.of(text, mask, keepFormat, trailing, mode));
            }
          }
        }
      }
    }
    return cases.stream();
  }

  /**
   * Checks both span orders, including empty, adjacent, nested and crossing spans.
   *
   * @param text The source text.
   * @param mask The replacement character.
   * @param keepFormat Whether punctuation stays visible.
   * @param trailing The number of trailing letters or digits to retain.
   * @param mode The masking overload.
   */
  @ParameterizedTest
  @MethodSource("combinations")
  void testSpanPairs(String text, char mask, boolean keepFormat, int trailing, Mode mode) {
    MaskPolicy policy = MaskPolicy.of(mask).keepingTrailing(trailing);
    if (keepFormat) {
      policy = policy.keepingFormat();
    }
    final List<Span> spans = new ArrayList<>();
    for (int start = 0; start <= text.length(); start++) {
      for (int end = start; end <= text.length(); end++) {
        spans.add(new Span(start, end));
      }
    }
    for (final Span first : spans) {
      for (final Span second : spans) {
        final String expected = expected(text, List.of(first, second), mask, keepFormat, trailing);
        final String actual = mask(text, first, second, policy, mode);
        Assertions.assertEquals(expected, actual, () -> first + " then " + second);
      }
    }
  }

  /**
   * Applies one policy through each supported layer arrangement.
   *
   * @param text The source text.
   * @param first The first span to mask.
   * @param second The second span to mask.
   * @param policy The policy for both spans.
   * @param mode The masking overload.
   * @return The masked text.
   */
  private String mask(String text, Span first, Span second, MaskPolicy policy, Mode mode) {
    final Annotation<String> a = new Annotation<>(first, "a");
    final Annotation<String> b = new Annotation<>(second, "b");
    if (mode == Mode.SEPARATE_LAYERS) {
      final Document document = Document.of(text).with(FIRST, List.of(a)).with(SECOND, List.of(b));
      return Masker.mask(document, List.of(FIRST, SECOND), policy);
    }
    final Document document = Document.of(text).with(FIRST, List.of(a, b));
    return mode == Mode.SINGLE_LAYER ? Masker.mask(document, FIRST, policy)
        : Masker.mask(document, FIRST, value -> policy);
  }

  /**
   * Computes expected masking from right to left on each original span.
   *
   * @param text The source text.
   * @param spans The spans to mask.
   * @param mask The replacement character.
   * @param keepFormat Whether punctuation stays visible.
   * @param trailing The number of trailing letters or digits to retain.
   * @return The union of the independently masked positions.
   */
  private String expected(String text, List<Span> spans, char mask, boolean keepFormat, int trailing) {
    final StringBuilder expected = new StringBuilder(text);
    for (final Span span : spans) {
      final String original = text.substring(span.getStart(), span.getEnd());
      int retained = 0;
      for (int end = original.length(); end > 0; ) {
        final int cp = original.codePointBefore(end);
        final int start = end - Character.charCount(cp);
        final boolean alphanumeric = Character.isLetterOrDigit(cp);
        final boolean keep = alphanumeric ? retained++ < trailing : keepFormat;
        if (!keep) {
          for (int i = start; i < end; i++) {
            expected.setCharAt(span.getStart() + i, mask);
          }
        }
        end = start;
      }
    }
    return expected.toString();
  }

  /**
   * Checks the documented precedence when policies use different mask characters.
   *
   * @param reverse Whether the right span is processed first.
   * @param expected The redacted text.
   */
  @ParameterizedTest
  @CsvSource({"false, **###6", "true, ***##6"})
  void testSelectorWithDifferentMaskCharacters(boolean reverse, String expected) {
    final MaskPolicy leftPolicy = MaskPolicy.of('*').keepingTrailing(1);
    final MaskPolicy rightPolicy = MaskPolicy.of('#').keepingTrailing(1);
    final Document document = withPolicies(leftPolicy, rightPolicy, reverse);

    Assertions.assertEquals(expected, Masker.mask(document, POLICIES, policy -> policy));
    Assertions.assertEquals("123456", document.text());
  }

  /**
   * Checks that selecting different trailing counts does not expose prior redactions.
   *
   * @param reverse Whether the right span is processed first.
   */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testSelectorWithDifferentTrailingCounts(boolean reverse) {
    final MaskPolicy leftPolicy = MaskPolicy.of('*').keepingTrailing(2);
    final MaskPolicy rightPolicy = MaskPolicy.of('*').keepingTrailing(1);
    final Document document = withPolicies(leftPolicy, rightPolicy, reverse);

    Assertions.assertEquals("*****6", Masker.mask(document, POLICIES, policy -> policy));
  }

  /**
   * Creates crossing spans with a policy stored as each annotation's value.
   *
   * @param leftPolicy The policy for the left span.
   * @param rightPolicy The policy for the right span.
   * @param reverse Whether the right span is processed first.
   * @return The document with both annotations in the requested order.
   */
  private Document withPolicies(MaskPolicy leftPolicy, MaskPolicy rightPolicy, boolean reverse) {
    final Annotation<MaskPolicy> left = new Annotation<>(new Span(0, 4), leftPolicy);
    final Annotation<MaskPolicy> right = new Annotation<>(new Span(2, 6), rightPolicy);
    return Document.of("123456")
        .with(POLICIES, reverse ? List.of(right, left) : List.of(left, right));
  }

  /**
   * Rejects layers without positional spans before invoking a policy selector.
   *
   * @param empty Whether the document-scoped layer has no annotations.
   */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testRejectsDocumentScopedLayers(boolean empty) {
    final LayerKey<String> category = LayerKey.document("category", String.class);
    final Document document = Document.of("123456")
        .with(category, empty ? List.of() : List.of(Annotation.of("private")));
    final MaskPolicy policy = MaskPolicy.of('*');

    Assertions.assertAll(
        () -> Assertions.assertThrows(IllegalArgumentException.class,
            () -> Masker.mask(document, category, '*')),
        () -> Assertions.assertThrows(IllegalArgumentException.class,
            () -> Masker.mask(document, category, policy)),
        () -> Assertions.assertThrows(IllegalArgumentException.class,
            () -> Masker.mask(document, List.of(category), '*')),
        () -> Assertions.assertThrows(IllegalArgumentException.class,
            () -> Masker.mask(document, List.of(category), policy)),
        () -> Assertions.assertThrows(IllegalArgumentException.class,
            () -> Masker.mask(document, category, value -> {
              Assertions.fail("A document-scoped layer must be rejected before selecting a policy");
              return policy;
            })));
  }
}
