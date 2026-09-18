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
package opennlp.tools.util.normalizer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.util.Span;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies the {@link DehyphenationCharSequenceNormalizer} join rule across the recognized
 * hyphens, line breaks, and continuation-line indentation, the no-join cases that must pass
 * through untouched, and the {@link Alignment} fidelity that lets a consumer report the
 * joined word against the original text.
 */
public class DehyphenationCharSequenceNormalizerTest {

  private static final DehyphenationCharSequenceNormalizer NORMALIZER =
      DehyphenationCharSequenceNormalizer.getInstance();

  /**
   * Deserialization restores the shared normalizer and aligned output.
   *
   * @throws IOException If serialization fails.
   * @throws ClassNotFoundException If the normalizer class cannot be loaded.
   */
  @Test
  void testSerializationRestoresSharedInstance() throws IOException, ClassNotFoundException {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
      output.writeObject(NORMALIZER);
    }
    try (ObjectInputStream input = new ObjectInputStream(
        new ByteArrayInputStream(bytes.toByteArray()))) {
      final DehyphenationCharSequenceNormalizer restored =
          (DehyphenationCharSequenceNormalizer) input.readObject();
      assertSame(NORMALIZER, restored);
      assertEquals("litigation", restored.normalize("litiga-\ntion").toString());
      assertEquals(new Span(0, 12),
          restored.normalizeAligned("litiga-\ntion").toOriginalSpan(0, 10));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "litiga-\ntion",       // U+000A line feed
      "litiga-\u000Btion",   // U+000B vertical tab
      "litiga-\ftion",       // U+000C form feed
      "litiga-\rtion",       // U+000D carriage return
      "litiga-\r\ntion",     // U+000D U+000A carriage return + line feed, one break
      "litiga-\u0085tion",   // U+0085 next line
      "litiga-\u2028tion",   // U+2028 line separator
      "litiga-\u2029tion"})  // U+2029 paragraph separator
  void testJoinAcrossEveryLineBreakForm(String text) {
    assertEquals("litigation", NORMALIZER.normalize(text).toString());
  }

  @Test
  void testFormFeedPageBreakDoesNotJoinAcrossThePage() {
    // The form feed after the line break is a second line break (a page break), not
    // continuation-line indentation, so the join must stop: swallowing it would fuse the
    // word with the page header, corrupting "litiga-" into "litigaPage".
    final String text = "litiga-\n\fPage 3\ntion";
    assertEquals(text, NORMALIZER.normalize(text).toString());
  }

  @Test
  void testJoinAcrossSoftHyphen() {
    assertEquals("litigation", NORMALIZER.normalize("litiga\u00AD\ntion").toString());
  }

  @Test
  void testJoinAcrossTypesetHyphen() {
    // PDF extractors such as Poppler and PDFBox emit U+2010 HYPHEN for typeset hyphens.
    assertEquals("wellknown", NORMALIZER.normalize("well\u2010\nknown").toString());
  }

  @Test
  void testNonBreakingHyphenIsLeftAlone() {
    // U+2011 NON-BREAKING HYPHEN asserts the word was NOT broken at this hyphen, so a
    // line break after it is not a hyphenation break and must not join.
    assertEquals("well\u2011\nknown", NORMALIZER.normalize("well\u2011\nknown").toString());
  }

  @Test
  void testHyphenatedCompoundSplitAtItsBreakJoinsAsDocumented() {
    // The documented dictionary-free trade-off: a genuine compound split at its own hyphen
    // joins too, "well-\nknown" to "wellknown".
    assertEquals("wellknown", NORMALIZER.normalize("well-\nknown").toString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"com-\r\n  plete", "com-\n\t\tplete", "com-\n \u00A0plete"})
  void testJoinConsumesContinuationLineIndentation(String text) {
    assertEquals("complete", NORMALIZER.normalize(text).toString());
  }

  @Test
  void testMultipleJoinsInOneText() {
    assertEquals("litigation complete",
        NORMALIZER.normalize("litiga-\ntion com-\nplete").toString());
  }

  @Test
  void testInlineHyphenIsLeftAlone() {
    // No line break follows the hyphen, so the compound passes through.
    assertEquals("well-known", NORMALIZER.normalize("well-known").toString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"ver-\n3", "end-\n."})
  void testHyphenBreakBeforeNonLetterIsLeftAlone(String text) {
    // A break after the hyphen is not enough; the continuation must start with a letter.
    assertEquals(text, NORMALIZER.normalize(text).toString());
  }

  @Test
  void testHyphenAtEndOfTextIsLeftAlone() {
    assertEquals("word-", NORMALIZER.normalize("word-").toString());
  }

  @Test
  void testHyphenWithoutPrecedingLetterIsLeftAlone() {
    assertEquals("-\nword", NORMALIZER.normalize("-\nword").toString());
  }

  @Test
  void testLineBreakWithoutHyphenIsLeftAlone() {
    assertEquals("word\nnext", NORMALIZER.normalize("word\nnext").toString());
  }

  @Test
  void testNoOpReturnsSameInstanceAndIdentityAlignment() {
    final String text = "an ordinary sentence.";
    assertSame(text, NORMALIZER.normalize(text));

    final AlignedText aligned = NORMALIZER.normalizeAligned(text);
    assertEquals(text, aligned.normalizedString());
    assertEquals(new Span(0, text.length()), aligned.toOriginalSpan(0, text.length()));
    assertEquals(new Span(3, 11), aligned.toOriginalSpan(3, 11));
    assertEquals(new Span(3, 11), aligned.toNormalizedSpan(3, 11));
  }

  @Test
  void testAlignedNormalizationMatchesPlainNormalization() {
    final String text = "litiga-\ntion com-\r\n  plete";
    assertEquals(NORMALIZER.normalize(text).toString(),
        NORMALIZER.normalizeAligned(text).normalizedString());
  }

  @Test
  void testJoinedWordSpanCoversBothHalvesAndTheBreakInTheOriginal() {
    // Original: "litiga-\ntion" is 12 code units; the joined "litigation" is 10.
    final AlignedText aligned = NORMALIZER.normalizeAligned("litiga-\ntion");
    assertEquals("litigation", aligned.normalizedString());
    assertEquals(new Span(0, 12), aligned.toOriginalSpan(0, 10));
    // The deleted hyphen maps forward to an empty span at the join point.
    assertEquals(new Span(6, 6), aligned.toNormalizedSpan(6, 7));
  }

  @Test
  void testSpansAroundTheEditMapUnchanged() {
    final String text = "a litiga-\ntion b";
    final AlignedText aligned = NORMALIZER.normalizeAligned(text);
    assertEquals("a litigation b", aligned.normalizedString());
    assertEquals(new Span(0, 1), aligned.toOriginalSpan(0, 1));
    assertEquals(new Span(2, 14), aligned.toOriginalSpan(2, 12));
    assertEquals(new Span(15, 16), aligned.toOriginalSpan(13, 14));
  }

  @Test
  void testCarriageReturnLineFeedWithIndentationAlignment() {
    // Original: "com-\r\n  plete" is 13 code units; the joined "complete" is 8.
    final AlignedText aligned = NORMALIZER.normalizeAligned("com-\r\n  plete");
    assertEquals("complete", aligned.normalizedString());
    assertEquals(new Span(0, 13), aligned.toOriginalSpan(0, 8));
    assertEquals(new Span(3, 3), aligned.toNormalizedSpan(3, 4));
  }

  @Test
  void testJoinAfterSupplementaryPlaneLetter() {
    // U+10400 DESERET CAPITAL LETTER LONG I is a supplementary-plane letter (a surrogate
    // pair in UTF-16), so the codePointBefore path must recognize it.
    final String text = "\uD801\uDC00-\nbc";
    final AlignedText aligned = NORMALIZER.normalizeAligned(text);
    assertEquals("\uD801\uDC00bc", aligned.normalizedString());
    assertEquals(new Span(0, 6), aligned.toOriginalSpan(0, 4));
  }

  @Test
  void testSupplementaryCharactersKeepCodeUnitOffsets() {
    // The emoji after the join occupies two UTF-16 code units; the trailing letter's
    // original span must still be reported in code units.
    final String text = "a-\nb😀c";
    final AlignedText aligned = NORMALIZER.normalizeAligned(text);
    assertEquals("ab😀c", aligned.normalizedString());
    assertEquals(new Span(6, 7), aligned.toOriginalSpan(4, 5));
    assertEquals(new Span(0, 6), aligned.toOriginalSpan(0, 4));
  }

  @Test
  void testNullIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> NORMALIZER.normalize(null));
    assertThrows(IllegalArgumentException.class, () -> NORMALIZER.normalizeAligned(null));
  }
  /** Retains empty-input coverage from core's shared normalizer contract suite. */
  @Test
  void testEmptyInputContract() {
    assertEquals("", NORMALIZER.normalize("").toString());
    assertEquals("", NORMALIZER.normalizeAligned("").normalizedString());
  }

}
