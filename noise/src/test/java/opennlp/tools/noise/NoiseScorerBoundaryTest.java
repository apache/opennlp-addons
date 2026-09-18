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

package opennlp.tools.noise;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.util.Span;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tests the score thresholds and original-text offsets. */
public class NoiseScorerBoundaryTest {

  private final NoiseScorer scorer = new StructuralNoiseScorer();

  /** @return Tokens below and at the structural thresholds. */
  static Stream<Arguments> structuralBoundaries() {
    return Stream.of(
        Arguments.of("consonant run 6", "astrchmo", null, 0.0),
        Arguments.of("consonant run 7", "astrchmfo", NoiseSpan.SEVERITY_DAMAGED, 0.5),
        Arguments.of("repeat run 3", "aaab", null, 0.0),
        Arguments.of("repeat run 4", "aaaab", NoiseSpan.SEVERITY_DAMAGED, 0.5),
        Arguments.of("vowelless letters 4", "bcdf", null, 0.0),
        Arguments.of("vowelless letters 5", "bcdfg", NoiseSpan.SEVERITY_DAMAGED, 0.5),
        Arguments.of("vowel ratio above 0.10", "bcdafghjk", null, 0.0),
        Arguments.of("vowel ratio 0.10", "bcdafghjkl", NoiseSpan.SEVERITY_DAMAGED, 0.5),
        Arguments.of("vowel ratio with 7 letters", "bcdafgh", null, 0.0),
        Arguments.of("vowel ratio with 8 letters", "bcdafghj", null, 0.0),
        Arguments.of("alternations 3", "a1b23456", null, 0.0),
        Arguments.of("alternations 4", "a1b2edoo", NoiseSpan.SEVERITY_DAMAGED, 0.5),
        Arguments.of("alternating token length 7", "a1b2edo", null, 0.0),
        Arguments.of("alternating token length 8", "a1b2edoo", NoiseSpan.SEVERITY_DAMAGED, 0.5),
        Arguments.of("signals 0", "hello", null, 0.0),
        Arguments.of("signals 1", "bcdfg", NoiseSpan.SEVERITY_DAMAGED, 0.5),
        Arguments.of("signals 2", "bcdfghj", NoiseSpan.SEVERITY_GIBBERISH, 0.5),
        Arguments.of("signals 3", "bbbbbbb", NoiseSpan.SEVERITY_GIBBERISH, 0.75),
        Arguments.of("signals 4", "bbbbbbb1c2d3f4g", NoiseSpan.SEVERITY_GIBBERISH, 1.0));
  }

  /** Verifies each structural threshold boundary. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("structuralBoundaries")
  void testStructuralThreshold(String name, String token, String severity, double score) {
    final List<NoiseSpan> expected = severity == null ? List.of()
        : List.of(new NoiseSpan(new Span(0, token.length()), severity, score));
    assertEquals(expected, scorer.score(token, List.of()), name);
  }

  /** @return Tokens below and at the binary-content thresholds. */
  static Stream<Arguments> binaryBoundaries() {
    return Stream.of(
        Arguments.of("length 23", "Ab".repeat(11) + "A", false),
        Arguments.of("length 24", "Ab".repeat(12), true),
        Arguments.of("letter case changes 11", "Ab".repeat(6) + "ab".repeat(6), false),
        Arguments.of("letter case changes 12", "Ab".repeat(6) + "AB".repeat(6), true),
        Arguments.of("digit and case changes 7", "Ab".repeat(4) + "1" + "ab".repeat(7) + "a", false),
        Arguments.of("digit and case changes 8", "Ab".repeat(4) + "A1" + "ab".repeat(7), true),
        Arguments.of("base64 plus", "ab".repeat(12) + "+", true),
        Arguments.of("base64 slash", "ab".repeat(12) + "/", true),
        Arguments.of("base64 padding", "ab".repeat(12) + "=", true),
        Arguments.of("non-base64 character", "Ab".repeat(12) + "_", false));
  }

  /** Verifies each binary-content threshold boundary. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("binaryBoundaries")
  void testBinaryThreshold(String name, String token, boolean binary) {
    final List<NoiseSpan> expected = binary ? List.of(new NoiseSpan(
        new Span(0, token.length()), NoiseSpan.SEVERITY_BINARYISH, token.length() / 48.0))
        : List.of();
    assertEquals(expected, scorer.score(token, List.of()), name);
  }

  /** Verifies binary-content score saturation. */
  @ParameterizedTest
  @ValueSource(ints = {47, 48, 49})
  void testBinaryScoreSaturation(int length) {
    final String token = "Ab".repeat(25).substring(0, length);
    assertEquals(List.of(new NoiseSpan(new Span(0, length), NoiseSpan.SEVERITY_BINARYISH,
        Math.min(1.0, length / 48.0))), scorer.score(token, List.of()));
  }

  /** Skips dictionary repair below the minimum core length. */
  @Test
  void testMinimumCoreLengthForDictionaryRepair() {
    final NoiseScorer withDictionary = new StructuralNoiseScorer(Set.of("m", "mx")::contains);
    assertEquals(List.of(), withDictionary.score("rn", List.of()));
    assertEquals(List.of(new NoiseSpan(new Span(0, 3), NoiseSpan.SEVERITY_MISSPELLED, 0.9)),
        withDictionary.score("rnx", List.of()));
  }

  /** Lets dictionary acceptance override structural signals. */
  @Test
  void testLowercaseDictionaryAcceptancePrecedesStructuralSignals() {
    final NoiseScorer withDictionary = new StructuralNoiseScorer(Set.of("xxxxxxxx")::contains);
    assertEquals(List.of(), withDictionary.score("XXXXXXXX", List.of()));
  }

  /** Leaves empty, punctuation-only, and non-ASCII inputs unscored. */
  @ParameterizedTest
  @ValueSource(strings = {"", " \t\n", "()[]{}", "bcdfg\u00e9", "bcdfg\ud83d\ude00"})
  void testUnscoredInputs(String text) {
    assertEquals(List.of(), scorer.score(new StringBuilder(text), List.of()));
  }

  /** Preserves UTF-16 offsets after supplementary characters. */
  @Test
  void testOriginalOffsetsAfterSupplementaryCharacterAndPunctuation() {
    final String text = "\ud83d\ude00 (bcdfg),\u2003end";
    assertEquals(List.of(new NoiseSpan(new Span(4, 9), NoiseSpan.SEVERITY_DAMAGED, 0.5)),
        scorer.score(new StringBuilder(text), List.of()));
  }

  /** Merges findings across supported whitespace characters. */
  @ParameterizedTest
  @ValueSource(strings = {" ", "\t", "\r\n", "\u00a0", "\u2003"})
  void testWhitespaceSeparatedFindingsMerge(String separator) {
    final String text = "bcdfg" + separator + "bcdfg";
    assertEquals(List.of(new NoiseSpan(new Span(0, text.length()),
        NoiseSpan.SEVERITY_DAMAGED, 0.5)), scorer.score(text, List.of()));
  }
}
