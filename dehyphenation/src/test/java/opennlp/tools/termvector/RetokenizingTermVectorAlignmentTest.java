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

package opennlp.tools.termvector;

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.tokenize.WhitespaceTokenizer;
import opennlp.tools.util.Span;
import opennlp.tools.util.normalizer.DehyphenationCharSequenceNormalizer;
import opennlp.tools.util.normalizer.OffsetAwareNormalizer;
import opennlp.tools.util.normalizer.TextNormalizer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Checks original-text offsets after dehyphenation and case conversion. */
class RetokenizingTermVectorAlignmentTest {

  private static final String EMOJI = "\uD83D\uDE00";
  private static final String INDENT = "\t \u00A0";
  private static final int[] HYPHENS = {'-', 0x00AD, 0x2010};
  private static final String[] BREAKS = {
      "\n", "\u000B", "\f", "\r", "\r\n", "\u0085", "\u2028", "\u2029"
  };

  /** Checks the manual example's term, count and source-text extraction. */
  @Test
  void testManualExample() {
    final OffsetAwareNormalizer pipeline = TextNormalizer.builder()
        .with(DehyphenationCharSequenceNormalizer.getInstance())
        .fullCaseFold()
        .buildAligned();

    final Document annotated = new RetokenizingTermVectorAnnotator(
        pipeline, WhitespaceTokenizer.INSTANCE).annotate(Document.of("word litiga-\ntion word"));
    final TermVector term = annotated.get(TermVectorAnnotator.TERM_VECTORS).get(1).value();
    final String original = term.spans().get(0).getCoveredText(annotated.text()).toString();

    assertEquals("litigation", term.term());
    assertEquals(1, term.frequency());
    assertEquals("litiga-\ntion", original);
    assertEquals(List.of(new Span(5, 17)), term.spans());
  }

  /**
   * Supplies the supported hyphens and breaks in each vector mode.
   *
   * @return The dehyphenation cases.
   */
  private static Stream<Arguments> joins() {
    return IntStream.of(HYPHENS).boxed().flatMap(hyphen ->
        IntStream.range(0, BREAKS.length).boxed().flatMap(lineBreak ->
            Stream.of(TermVectorAnnotator.Mode.values())
                .map(mode -> Arguments.of(hyphen, lineBreak, mode))));
  }

  /**
   * Repeated words and joined words retain their counts and UTF-16 source offsets.
   *
   * @param hyphen The hyphen code point.
   * @param lineBreak The index of the break text.
   * @param mode The vector mode.
   */
  @ParameterizedTest(name = "{index}: hyphen={0}, break={1}, mode={2}")
  @MethodSource("joins")
  void testJoinedWordSpans(int hyphen, int lineBreak, TermVectorAnnotator.Mode mode) {
    final String brokenWord = "LITIGA" + (char) hyphen + BREAKS[lineBreak] + INDENT + "TION";
    final String text = EMOJI + " WORD " + brokenWord + " WORD";
    final OffsetAwareNormalizer pipeline = TextNormalizer.builder()
        .with(DehyphenationCharSequenceNormalizer.getInstance())
        .fullCaseFold()
        .buildAligned();
    final Document input = Document.of(text);
    final Document output = new RetokenizingTermVectorAnnotator(
        pipeline, WhitespaceTokenizer.INSTANCE, mode).annotate(input);
    final List<TermVector> vectors = output.get(TermVectorAnnotator.TERM_VECTORS).stream()
        .map(Annotation::value).toList();

    assertEquals(List.of(EMOJI, "word", "litigation"),
        vectors.stream().map(TermVector::term).toList());
    assertEquals(List.of(1, 2, 1),
        vectors.stream().map(TermVector::frequency).toList());
    assertEquals(text, output.text().toString());
    if (mode == TermVectorAnnotator.Mode.FULL) {
      assertEquals(List.of(new Span(0, EMOJI.length())), vectors.get(0).spans());
      assertEquals(List.of(new Span(text.indexOf("WORD"), text.indexOf("WORD") + 4),
          new Span(text.lastIndexOf("WORD"), text.length())), vectors.get(1).spans());
      final Span expected = new Span(text.indexOf(brokenWord),
          text.indexOf(brokenWord) + brokenWord.length());
      assertEquals(List.of(expected), vectors.get(2).spans());
      assertEquals(brokenWord, vectors.get(2).spans().get(0).getCoveredText(text).toString());
    } else {
      assertEquals(List.of(List.of(), List.of(), List.of()),
          vectors.stream().map(TermVector::spans).toList());
    }
  }
}
