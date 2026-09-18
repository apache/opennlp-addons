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

import java.util.ArrayList;
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
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests noise categories, exclusions, and dictionary-based repairs.
 */
public class StructuralNoiseScorerTest {

  private final StructuralNoiseScorer scorer = new StructuralNoiseScorer();

  /**
   * @return English words with consonant runs, repeated letters, or low vowel proportions.
   */
  static Stream<String> representativeWords() {
    return Stream.of("strengths", "catchphrase", "rhythms", "bookkeeper",
        "latchstring", "twelfths");
  }

  /** Keeps representative English words clean. */
  @ParameterizedTest
  @MethodSource("representativeWords")
  void testRepresentativeWordsProduceNoNoise(String word) {
    assertEquals(List.of(), scorer.score("before " + word + " after", List.of()),
        word);
  }

  /** Keeps ordinary prose clean. */
  @Test
  void testOrdinaryProseStaysClean() {
    assertEquals(List.of(), scorer.score(
        "The quick brown fox jumps over the lazy dog, twelfth night approaches.",
        List.of()));
  }

  /** Structural signals apply to ASCII letters only; other scripts never flag. */
  @ParameterizedTest
  @ValueSource(strings = {
      "می‌خواهم",
      "กรุงเทพมหานคร",
      "東京都千代田区"})
  void testOtherScriptsAreNeverFlagged(String text) {
    assertEquals(List.of(), scorer.score(text, List.of()));
  }

  /** A long camel-case identifier is neither binary-ish nor gibberish. */
  @Test
  void testCamelCaseIdentifierStaysClean() {
    assertEquals(List.of(),
        scorer.score("see AbstractSingletonProxyFactoryBean docs", List.of()));
  }

  /** Accepts a long identifier with one digit. */
  @Test
  void testLongIdentifierWithOneDigitStaysClean() {
    assertEquals(List.of(), scorer.score("DocumentationVersion2Api", List.of()));
  }

  /** Requires a dictionary-supported repair for misspelled results. */
  @Test
  void testMisspelledBoundaryRequiresARepairToAKnownWord() {
    final StructuralNoiseScorer withDictionary =
        new StructuralNoiseScorer(Set.of("modern")::contains);
    assertEquals(List.of(), withDictionary.score("rxodern", List.of()));
    assertEquals(NoiseSpan.SEVERITY_MISSPELLED,
        withDictionary.score("rnodern", List.of()).get(0).severity());
  }

  /** Reports a consonant-run signal starting at seven consecutive consonants. */
  @Test
  void testDamagedBoundaryStartsAtSevenConsonants() {
    assertEquals(List.of(), scorer.score("astrchmo", List.of()));
    assertEquals(NoiseSpan.SEVERITY_DAMAGED,
        scorer.score("astrchmfo", List.of()).get(0).severity());
  }

  /** Reports gibberish for multiple structural signals. */
  @Test
  void testGibberishBoundaryStartsAtTwoSignals() {
    assertEquals(NoiseSpan.SEVERITY_DAMAGED,
        scorer.score("asdkfjqwza", List.of()).get(0).severity());
    assertEquals(NoiseSpan.SEVERITY_GIBBERISH,
        scorer.score("asdkfjqwzx", List.of()).get(0).severity());
  }

  /** Reports binary content starting at 24 characters. */
  @Test
  void testBinaryishBoundaryStartsAtTwentyFourCharacters() {
    assertEquals(List.of(), scorer.score("AbAbAbAbAbAbAbAbAbAbAbA", List.of()));
    assertEquals(NoiseSpan.SEVERITY_BINARYISH,
        scorer.score("AbAbAbAbAbAbAbAbAbAbAbAb", List.of()).get(0).severity());
  }

  /**
   * @return Tokens with multiple structural signals.
   */
  static Stream<Arguments> gibberish() {
    return Stream.of(
        // Vowel share exactly 0.10 over ten letters plus a nine-consonant run.
        Arguments.of("asdkfjqwzx"),
        // No vowels, a consonant run, and repeated characters.
        Arguments.of("xxxxxxxx"),
        // Vowelless and a twelve-consonant run.
        Arguments.of("zxkcvbnmsdfg"));
  }

  /** Reports tokens with at least two structural signals as gibberish. */
  @ParameterizedTest
  @MethodSource("gibberish")
  void testTwoAgreeingSignalsAreGibberish(String token) {
    final String text = "start " + token + " end";
    final List<NoiseSpan> found = scorer.score(text, List.of());
    assertEquals(1, found.size());
    assertEquals(NoiseSpan.SEVERITY_GIBBERISH, found.get(0).severity());
    assertEquals(token, found.get(0).span().getCoveredText(text).toString());
  }

  /** One signal alone is damage: an eight-consonant run with healthy vowels. */
  @Test
  void testSingleSignalIsDamage() {
    final List<NoiseSpan> found = scorer.score("a astrchmfko z", List.of());
    assertEquals(1, found.size());
    assertEquals(NoiseSpan.SEVERITY_DAMAGED, found.get(0).severity());
  }

  /** Heavy letter-digit interleaving is damage. */
  @Test
  void testDigitInterleavingIsDamage() {
    final List<NoiseSpan> found = scorer.score("see c0mput3r there", List.of());
    assertEquals(1, found.size());
    assertEquals(NoiseSpan.SEVERITY_DAMAGED, found.get(0).severity());
  }

  /** Common punctuation is excluded from the encoded-content span. */
  @Test
  void testBase64ShapedRunIsBinaryish() {
    final String token = "QWxhZGRpbjpvcGVuIHNlc2FtZQ==";
    final String text = "payload (" + token + ").";
    final List<NoiseSpan> found = scorer.score(text, List.of());
    assertEquals(1, found.size());
    assertEquals(NoiseSpan.SEVERITY_BINARYISH, found.get(0).severity());
    assertEquals(token, found.get(0).span().getCoveredText(text).toString());
  }

  /** An excluded region is not scored at all. */
  @Test
  void testExcludedRegionIsNotScored() {
    final String token = "QWxhZGRpbjF2cGVuNHNlc2FtZQ";
    final String text = "x " + token + " zxkcvbnmsdfg";
    final List<NoiseSpan> found =
        scorer.score(text, List.of(new Span(2, 2 + token.length())));
    assertEquals(1, found.size());
    assertEquals(NoiseSpan.SEVERITY_GIBBERISH, found.get(0).severity());
  }

  /** Ignores empty exclusions. */
  @Test
  void testEmptyExclusionDoesNotSuppressAToken() {
    final String text = "zxkcvbnmsdfg";
    assertEquals(scorer.score(text, List.of()),
        scorer.score(text, List.of(new Span(3, 3))));
  }

  /** Prevents merging across excluded whitespace. */
  @Test
  void testFindingsDoNotMergeAcrossExcludedWhitespace() {
    final String text = "bcdfg \t bcdfg";
    assertEquals(List.of(
        new NoiseSpan(new Span(0, 5), NoiseSpan.SEVERITY_DAMAGED, 0.5),
        new NoiseSpan(new Span(8, 13), NoiseSpan.SEVERITY_DAMAGED, 0.5)),
        scorer.score(text, List.of(new Span(6, 7))));
  }

  /** Rejects exclusions outside the text bounds. */
  @Test
  void testExclusionsMustFitTheText() {
    final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> scorer.score("text", List.of(new Span(3, 5))));
    assertEquals("exclude spans must fit within text", error.getMessage());
    assertThrows(IllegalArgumentException.class,
        () -> scorer.score("", List.of(new Span(1, 1))));
  }

  /** Accepts unordered, duplicate, and overlapping exclusions. */
  @Test
  void testUnsortedOverlappingExclusionsAreAccepted() {
    final String text = "bcdfg hello bcdfg end bcdfg";
    final List<Span> exclude = List.of(new Span(12, 16), new Span(0, 3),
        new Span(11, 14), new Span(0, 3));
    assertEquals(List.of(new NoiseSpan(new Span(22, 27),
        NoiseSpan.SEVERITY_DAMAGED, 0.5)), scorer.score(text, exclude));
  }

  /** Accepts findings adjacent to excluded spans. */
  @Test
  void testTouchingExclusionsDoNotSuppressAToken() {
    final String text = " bcdfg ";
    assertEquals(scorer.score(text, List.of()),
        scorer.score(text, List.of(new Span(0, 1), new Span(6, 7))));
  }

  /** Adjacent findings merge into one span of the worse severity. */
  @Test
  void testAdjacentFindingsMergeToTheWorseSeverity() {
    final String text = "xxxxxxxx c0mput3r";
    final List<NoiseSpan> found = scorer.score(text, List.of());
    assertEquals(1, found.size());
    assertEquals(NoiseSpan.SEVERITY_GIBBERISH, found.get(0).severity());
    assertEquals(text, found.get(0).span().getCoveredText(text).toString());
  }

  /**
   * Merging preserves the higher severity's score even if the lower severity has
   * a larger numeric score.
   */
  @Test
  void testMergedSpanCarriesTheSurvivingSeveritysScore() {
    final StructuralNoiseScorer withDictionary =
        new StructuralNoiseScorer(Set.of("modern")::contains);
    final String text = "rnodern zxkcvbnmsdfg";
    final List<NoiseSpan> found = withDictionary.score(text, List.of());
    assertEquals(1, found.size());
    assertEquals(NoiseSpan.SEVERITY_GIBBERISH, found.get(0).severity());
    assertEquals(0.5, found.get(0).score(),
        "the gibberish tier's own score, not the misspelled 0.9");
    assertEquals(text, found.get(0).span().getCoveredText(text).toString());
  }

  /** When both findings share the surviving severity, the higher score wins. */
  @Test
  void testMergedSpansOfOneSeverityKeepTheHigherScore() {
    final String text = "zxkcvbnmsdfg zxkcvbnmsdfgggg";
    final List<NoiseSpan> found = scorer.score(text, List.of());
    assertEquals(1, found.size());
    assertEquals(NoiseSpan.SEVERITY_GIBBERISH, found.get(0).severity());
    assertEquals(0.75, found.get(0).score(),
        "three agreeing signals on the worse neighbor");
  }

  /** With a dictionary, one confusion repair reaching a word means misspelled. */
  @Test
  void testConfusionRepairsAreMisspelledWithADictionary() {
    final Set<String> words = Set.of("modern", "times", "word");
    final StructuralNoiseScorer withDictionary =
        new StructuralNoiseScorer(words::contains);
    final String text = "rnodern tirnes and vvord";
    final List<NoiseSpan> found = withDictionary.score(text, List.of());
    assertEquals(2, found.size());
    assertEquals(NoiseSpan.SEVERITY_MISSPELLED, found.get(0).severity());
    assertEquals("rnodern tirnes",
        found.get(0).span().getCoveredText(text).toString());
    assertEquals(NoiseSpan.SEVERITY_MISSPELLED, found.get(1).severity());
    assertEquals("vvord", found.get(1).span().getCoveredText(text).toString());
  }

  /** Without a dictionary the same text yields nothing; the tier needs evidence. */
  @Test
  void testConfusionsWithoutADictionaryAreNotReported() {
    assertEquals(List.of(), scorer.score("rnodern tirnes and vvord", List.of()));
  }

  /** A word the dictionary accepts is never flagged, whatever its structure. */
  @Test
  void testDictionaryAcceptedTokenOverridesStructure() {
    final StructuralNoiseScorer withDictionary =
        new StructuralNoiseScorer(Set.of("zxkcvbnmsdfg")::contains);
    assertEquals(List.of(), withDictionary.score("zxkcvbnmsdfg", List.of()));
  }

  /** Rejects null API arguments. */
  @Test
  void testRejectsContractViolations() {
    assertThrows(IllegalArgumentException.class, () -> scorer.score(null, List.of()));
    assertThrows(IllegalArgumentException.class, () -> scorer.score("x", null));
    final List<Span> holdsNull = new ArrayList<>();
    holdsNull.add(null);
    assertThrows(IllegalArgumentException.class, () -> scorer.score("x", holdsNull));
    assertThrows(IllegalArgumentException.class,
        () -> new StructuralNoiseScorer(null));
  }

  /** A payload past the saturation length scores the maximum of its tier. */
  @Test
  void testLongPayloadSaturatesTheScore() {
    final String longRun = "QWxh1ZGRp2bjF2c3BlbjRzZXNhbWU5QWxh1ZGRp2bjF2c3BlbjR"
        + "zZXNhbWU5";
    final List<NoiseSpan> found = scorer.score(longRun, List.of());
    assertEquals(1, found.size());
    assertEquals(NoiseSpan.SEVERITY_BINARYISH, found.get(0).severity());
    assertEquals(1.0, found.get(0).score());
  }
}
