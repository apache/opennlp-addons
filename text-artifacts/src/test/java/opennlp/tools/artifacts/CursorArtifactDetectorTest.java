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

package opennlp.tools.artifacts;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static opennlp.tools.artifacts.ArtifactTestSupport.cp;
import static opennlp.tools.artifacts.ArtifactTestSupport.tags;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests artifact types, original-text offsets, and multilingual text examples.
 */
public class CursorArtifactDetectorTest {

  private final CursorArtifactDetector detector = new CursorArtifactDetector();

  /**
   * @return One accepting case per artifact class: the text, the expected type, and the
   *         expected covered text.
   */
  private static Stream<Arguments> flagged() {
    final String replacementRun = cp(0xFFFD, 0xFFFD);
    final String nul = cp(0x0000);
    final String escape = cp(0x001B);
    final String noncharacterArabicRange = cp(0xFDD0);
    final String noncharacterPlaneEnd = cp(0xFFFE);
    final String privateUseRun = cp(0xE000, 0xF8FF);
    final String privateUseSupplementary = cp(0x100000);
    final String rightToLeftOverride = cp(0x202E);
    final String firstStrongIsolate = cp(0x2068);
    final String zeroWidthSpaceRun = cp(0x200B, 0x200B, 0x200B);
    final String byteOrderMark = cp(0xFEFF);
    // "don't" through a cp1252 read of its UTF-8 bytes: apostrophe U+2019 -> E2 80 99.
    final String curlyQuoteMojibake = cp(0x00E2, 0x20AC, 0x2122);
    // "e with acute" the same way: U+00E9 -> C3 A9.
    final String accentMojibake = cp(0x00C3, 0x00A9);
    return Stream.of(
        Arguments.of("bad " + replacementRun + " decode",
            TextArtifact.TYPE_REPLACEMENT, replacementRun),
        Arguments.of("nul" + nul + "byte", TextArtifact.TYPE_CONTROL, nul),
        Arguments.of("esc" + escape + "[31m", TextArtifact.TYPE_CONTROL, escape),
        Arguments.of("internal " + noncharacterArabicRange + " sentinel",
            TextArtifact.TYPE_NONCHARACTER, noncharacterArabicRange),
        Arguments.of("plane end " + noncharacterPlaneEnd,
            TextArtifact.TYPE_NONCHARACTER, noncharacterPlaneEnd),
        Arguments.of("icon font " + privateUseRun + " glyphs",
            TextArtifact.TYPE_PRIVATE_USE, privateUseRun),
        Arguments.of("plane 16 " + privateUseSupplementary + " glyph",
            TextArtifact.TYPE_PRIVATE_USE, privateUseSupplementary),
        Arguments.of("override " + rightToLeftOverride + "txet here",
            TextArtifact.TYPE_BIDI_CONTROL, rightToLeftOverride),
        Arguments.of("isolate " + firstStrongIsolate + "x",
            TextArtifact.TYPE_BIDI_CONTROL, firstStrongIsolate),
        Arguments.of("stuffed" + zeroWidthSpaceRun + "text",
            TextArtifact.TYPE_ZERO_WIDTH, zeroWidthSpaceRun),
        Arguments.of(byteOrderMark + "leading bom",
            TextArtifact.TYPE_ZERO_WIDTH, byteOrderMark),
        Arguments.of("don" + curlyQuoteMojibake + "t",
            TextArtifact.TYPE_MOJIBAKE, curlyQuoteMojibake),
        Arguments.of("caf" + accentMojibake,
            TextArtifact.TYPE_MOJIBAKE, accentMojibake));
  }

  /**
   * Reports the expected type and covered text.
   *
   * @param text The input text.
   * @param type The expected artifact type.
   * @param covered The expected source substring.
   */
  @ParameterizedTest
  @MethodSource("flagged")
  void testFlagsTheClassWithExactSpan(String text, String type, String covered) {
    final List<TextArtifact> artifacts = detector.detect(text);
    assertEquals(1, artifacts.size(), () -> "expected exactly one artifact in <" + text
        + "> but found " + artifacts);
    final TextArtifact artifact = artifacts.get(0);
    assertEquals(type, artifact.type());
    assertEquals(covered, artifact.span().getCoveredText(text).toString());
  }

  /** @return Clean texts that must produce no artifact at all. */
  private static Stream<Arguments> clean() {
    return Stream.of(
        Arguments.of("plain ASCII", "plain ASCII text, with punctuation."),
        // Precomposed accents and typographic punctuation are not mojibake: their
        // single-byte images are not valid UTF-8 sequences.
        Arguments.of("accented words",
            "d" + cp(0x00E9) + "j" + cp(0x00E0) + " vu " + cp(0x2014) + " caf"
                + cp(0x00E9) + " na" + cp(0x00EF) + "ve"),
        Arguments.of("lone Latin-1 letters",
            "Jo" + cp(0x00E3) + "o n" + cp(0x00E3) + "o " + cp(0x00E9) + " s"
                + cp(0x00F3)),
        Arguments.of("whitespace controls",
            "tabs\tand\nnewlines\r\nare whitespace, not controls"),
        // ZWNJ between Arabic letters is orthographic.
        Arguments.of("joiner in Arabic",
            cp(0x0628, 0x200C, 0x0628)),
        // The family emoji: pictograph ZWJ pictograph ZWJ pictograph.
        Arguments.of("emoji family",
            "family: " + cp(0x1F469, 0x200D, 0x1F469, 0x200D, 0x1F466)),
        // Pictograph + variation selector + ZWJ + pictograph.
        Arguments.of("heart on fire",
            "pict " + cp(0x2764, 0xFE0F, 0x200D, 0x1F525)),
        Arguments.of("supplementary han", "han " + cp(0x23BB4) + " text"));
  }

  /**
   * Produces no artifacts for ordinary text examples.
   *
   * @param label The example description.
   * @param text The input text.
   */
  @ParameterizedTest
  @MethodSource("clean")
  void testCleanTextYieldsNothing(String label, String text) {
    assertEquals(List.of(), detector.detect(text),
        () -> "false positive in " + label + ": <" + text + ">");
  }

  /**
   * Reports an unpaired surrogate at the source character offset.
   *
   * @param surrogate The unpaired high or low surrogate.
   */
  @ParameterizedTest
  @ValueSource(chars = {0xD83D, 0xDC69})
  void testUnpairedSurrogates(char surrogate) {
    final List<TextArtifact> artifacts = detector.detect("x" + surrogate + "y");
    assertEquals(1, artifacts.size());
    assertEquals(TextArtifact.TYPE_UNPAIRED_SURROGATE, artifacts.get(0).type());
    assertEquals(1, artifacts.get(0).span().getStart());
    assertEquals(2, artifacts.get(0).span().getEnd());
  }

  /** A single zero-width space between Thai letters is a line-break hint, kept. */
  @Test
  void testSingleZeroWidthSpaceBetweenLettersIsOrthographic() {
    final String thai = cp(0x0E01, 0x0E23, 0x0E38, 0x0E07) + cp(0x200B)
        + cp(0x0E40, 0x0E17, 0x0E1E);
    assertEquals(List.of(), detector.detect(thai));
  }

  /** The same character next to punctuation is an artifact. */
  @Test
  void testZeroWidthOutsideLetterContextIsFlagged() {
    final List<TextArtifact> artifacts = detector.detect("end." + cp(0x200B) + " next");
    assertEquals(1, artifacts.size());
    assertEquals(TextArtifact.TYPE_ZERO_WIDTH, artifacts.get(0).type());
  }

  /** A ZWNJ between Persian letters, as the orthography writes it, is kept. */
  @Test
  void testJoinerBetweenLettersIsOrthographic() {
    final String persian = cp(0x0645, 0x06CC) + cp(0x200C) + cp(0x062E, 0x0648);
    assertEquals(List.of(), detector.detect(persian));
  }

  /** Multiple artifacts report in order of appearance with non-overlapping spans. */
  @Test
  void testMultipleFindingsInOrder() {
    final String text = "a" + cp(0x0007) + "b caf" + cp(0x00C3, 0x00A9) + " d"
        + cp(0xFFFD) + "e";
    final List<TextArtifact> artifacts = detector.detect(text);
    assertEquals(3, artifacts.size());
    assertEquals(TextArtifact.TYPE_CONTROL, artifacts.get(0).type());
    assertEquals(TextArtifact.TYPE_MOJIBAKE, artifacts.get(1).type());
    assertEquals(TextArtifact.TYPE_REPLACEMENT, artifacts.get(2).type());
    assertTrue(artifacts.get(0).span().getEnd() <= artifacts.get(1).span().getStart());
    assertTrue(artifacts.get(1).span().getEnd() <= artifacts.get(2).span().getStart());
  }

  /** ASCII separates damaged runs into independent spans. */
  @Test
  void testMojibakeRunsAreSeparatedByAscii() {
    final String damagedE = cp(0x00C3, 0x00A9);
    final String text = damagedE + "t" + damagedE + " chez papa";
    final List<TextArtifact> artifacts = detector.detect(text);
    assertEquals(2, artifacts.size());
    assertEquals(TextArtifact.TYPE_MOJIBAKE, artifacts.get(0).type());
    assertEquals(TextArtifact.TYPE_MOJIBAKE, artifacts.get(1).type());
    assertEquals(0, artifacts.get(0).span().getStart());
    assertEquals(2, artifacts.get(0).span().getEnd());
    assertEquals(3, artifacts.get(1).span().getStart());
  }

  /**
   * Latin-1 decoders preserve C1 bytes as controls, so their UTF-8 damage requires an
   * identity fallback where Windows-1252 assigns a printable character instead.
   *
   * @param damaged The Latin-1 image of valid multi-byte UTF-8.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "\u00C2\u0080",
      "\u00E2\u0082\u00AC"
  })
  void testLatin1C1Mojibake(String damaged) {
    final List<TextArtifact> artifacts = detector.detect("value " + damaged + " end");

    assertEquals(1, artifacts.size());
    assertEquals(TextArtifact.TYPE_MOJIBAKE, artifacts.get(0).type());
    assertEquals(damaged, artifacts.get(0).span().getCoveredText(
        "value " + damaged + " end").toString());
  }

  /** Orphan tag characters expose hidden ASCII as one Unicode-tag artifact. */
  @Test
  void testUnicodeTagRunIsFlagged() {
    final String hidden = tags("secret", true);
    final String text = "visible " + hidden + " text";
    final List<TextArtifact> artifacts = detector.detect(text);

    assertEquals(1, artifacts.size());
    assertEquals(TextArtifact.TYPE_UNICODE_TAG, artifacts.get(0).type());
    assertEquals(hidden, artifacts.get(0).span().getCoveredText(text).toString());
  }

  /** Reports samples from the Tags block, including deprecated and unassigned values. */
  @Test
  void testWholeUnicodeTagsBlockIsClassified() {
    final String hidden = cp(0xE0000, 0xE0001, 0xE0010, 0xE007F);
    final List<TextArtifact> artifacts = detector.detect(hidden);

    assertEquals(1, artifacts.size());
    assertEquals(TextArtifact.TYPE_UNICODE_TAG, artifacts.get(0).type());
    assertEquals(hidden, artifacts.get(0).span().getCoveredText(hidden).toString());
  }

  /** Does not report the England flag's Unicode tag sequence. */
  @Test
  void testEmojiTagFlagIsOrthographic() {
    final String england = cp(0x1F3F4) + tags("gbeng", true);
    assertEquals(List.of(), detector.detect(england));
  }

  /** An unterminated subdivision flag is hidden tag text, not a valid emoji sequence. */
  @Test
  void testMalformedEmojiTagFlagIsFlagged() {
    final String hidden = tags("gbeng", false);
    final String malformed = cp(0x1F3F4) + hidden;
    final List<TextArtifact> artifacts = detector.detect(malformed);

    assertEquals(1, artifacts.size());
    assertEquals(TextArtifact.TYPE_UNICODE_TAG, artifacts.get(0).type());
    assertEquals(hidden, artifacts.get(0).span().getCoveredText(malformed).toString());
  }

  /**
   * @return Single-byte text mappings that fail UTF-8 validation, with the reason.
   */
  private static Stream<Arguments> invalidUtf8Images() {
    return Stream.of(
        // C0 80 is the classic overlong NUL; C0 maps from U+00C0.
        Arguments.of("overlong", "x " + cp(0x00C0, 0x20AC) + " y"),
        // A bare continuation byte image: U+00A9 -> A9 with no lead.
        Arguments.of("bare continuation", "copyright " + cp(0x00A9) + " sign"),
        // ED A0 80 encodes a surrogate; ED maps from U+00ED.
        Arguments.of("encoded surrogate", cp(0x00ED, 0x00A0, 0x20AC)));
  }

  /**
   * Does not report invalid UTF-8 mappings as mojibake.
   *
   * @param label The invalid encoding case.
   * @param text The input text.
   */
  @ParameterizedTest
  @MethodSource("invalidUtf8Images")
  void testInvalidUtf8ImagesAreNotMojibake(String label, String text) {
    assertEquals(List.of(), detector.detect(text),
        () -> "false positive for " + label + ": <" + text + ">");
  }

  /** Adjacent control and replacement runs produce separate spans. */
  @Test
  void testAdjacentDifferentTypeRunsAbut() {
    final String text = "x" + cp(0x0007, 0xFFFD) + "y";
    final List<TextArtifact> artifacts = detector.detect(text);
    assertEquals(2, artifacts.size());
    assertEquals(TextArtifact.TYPE_CONTROL, artifacts.get(0).type());
    assertEquals(1, artifacts.get(0).span().getStart());
    assertEquals(2, artifacts.get(0).span().getEnd());
    assertEquals(TextArtifact.TYPE_REPLACEMENT, artifacts.get(1).type());
    assertEquals(2, artifacts.get(1).span().getStart());
    assertEquals(3, artifacts.get(1).span().getEnd());
  }

  /** Reports a high surrogate at the end of the input. */
  @Test
  void testUnpairedHighSurrogateAtEndOfText() {
    final String text = "abc" + (char) 0xD83D;
    final List<TextArtifact> artifacts = detector.detect(text);
    assertEquals(1, artifacts.size());
    assertEquals(TextArtifact.TYPE_UNPAIRED_SURROGATE, artifacts.get(0).type());
    assertEquals(3, artifacts.get(0).span().getStart());
    assertEquals(text.length(), artifacts.get(0).span().getEnd());
  }

  /** Reports a replacement run through the end of the input. */
  @Test
  void testRunEndingExactlyAtTextLength() {
    final String text = "ok" + cp(0xFFFD, 0xFFFD);
    final List<TextArtifact> artifacts = detector.detect(text);
    assertEquals(1, artifacts.size());
    assertEquals(TextArtifact.TYPE_REPLACEMENT, artifacts.get(0).type());
    assertEquals(2, artifacts.get(0).span().getStart());
    assertEquals(text.length(), artifacts.get(0).span().getEnd());
  }

  /** Reports sorted spans without overlap for mixed artifact types. */
  @Test
  void testSpansAreSortedAndNeverOverlap() {
    final String text = "a." + cp(0x200B, 0x200B) + "b" + cp(0x0007, 0x001B)
        + " caf" + cp(0x00C3, 0x00A9) + " d" + cp(0xFFFD, 0xFDD0);
    final List<TextArtifact> artifacts = detector.detect(text);
    assertEquals(5, artifacts.size());
    for (int k = 0; k + 1 < artifacts.size(); k++) {
      final TextArtifact current = artifacts.get(k);
      final TextArtifact next = artifacts.get(k + 1);
      assertTrue(current.span().getStart() < next.span().getStart(),
          () -> "spans not sorted: " + artifacts);
      assertTrue(current.span().getEnd() <= next.span().getStart(),
          () -> "spans overlap: " + artifacts);
    }
  }

  /** Produces the same results for String and StringBuilder inputs. */
  @Test
  void testCharSequenceInputMatchesStringInput() {
    final String text = "a" + cp(0x0007) + " caf" + cp(0x00C3, 0x00A9) + " " + cp(0xFFFD);
    final List<TextArtifact> fromString = detector.detect(text);
    final List<TextArtifact> fromBuilder = detector.detect(new StringBuilder(text));
    assertEquals(3, fromString.size());
    assertEquals(fromString, fromBuilder);
  }

  /** Rejects a null character sequence. */
  @Test
  void testRejectsNull() {
    assertThrows(IllegalArgumentException.class, () -> detector.detect(null));
  }

  /** Returns an empty result for empty text. */
  @Test
  void testEmptyTextYieldsNothing() {
    assertEquals(List.of(), detector.detect(""));
  }

  /** A type-limited detector reports only the requested artifact family. */
  @Test
  void testTypeLimitedDetector() {
    final String mojibake = cp(0x00C3, 0x00A9);
    final String hidden = tags("secret", true);
    final String text = "control " + cp(0x0007) + " caf" + mojibake + " " + hidden;

    final List<TextArtifact> artifacts = new CursorArtifactDetector(
        Set.of(TextArtifact.TYPE_MOJIBAKE)).detect(text);

    assertEquals(1, artifacts.size());
    assertEquals(TextArtifact.TYPE_MOJIBAKE, artifacts.get(0).type());
  }

  /** Constructor validation rejects absent, empty, and unknown type sets. */
  @Test
  void testTypeLimitedDetectorRejectsInvalidSets() {
    final IllegalArgumentException nullTypes = assertThrows(IllegalArgumentException.class,
        () -> new CursorArtifactDetector(null));
    final IllegalArgumentException emptyTypes = assertThrows(IllegalArgumentException.class,
        () -> new CursorArtifactDetector(Set.of()));
    final IllegalArgumentException unknownType = assertThrows(IllegalArgumentException.class,
        () -> new CursorArtifactDetector(Set.of("unknown")));

    assertEquals("types must not be null or empty", nullTypes.getMessage());
    assertEquals("types must not be null or empty", emptyTypes.getMessage());
    assertEquals("types contains an unrecognized type: unknown", unknownType.getMessage());
  }

  /** Changes to the supplied set do not alter the detector configuration. */
  @Test
  void testTypeLimitedDetectorDefensivelyCopiesSet() {
    final Set<String> types = new HashSet<>();
    types.add(TextArtifact.TYPE_CONTROL);
    final CursorArtifactDetector controlOnly = new CursorArtifactDetector(types);
    types.add(TextArtifact.TYPE_MOJIBAKE);
    final String text = cp(0x0007) + " " + cp(0x00C3, 0x00A9);

    final List<TextArtifact> artifacts = controlOnly.detect(text);

    assertEquals(1, artifacts.size());
    assertEquals(TextArtifact.TYPE_CONTROL, artifacts.get(0).type());
  }
}
