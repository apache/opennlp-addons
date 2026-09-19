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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.Layers;
import opennlp.tools.tokenize.WhitespaceTokenizer;
import opennlp.tools.util.Span;
import opennlp.tools.util.normalizer.DehyphenationCharSequenceNormalizer;
import opennlp.tools.util.normalizer.OffsetAwareNormalizer;
import opennlp.tools.util.normalizer.TextNormalizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks terms, counts and source offsets after dehyphenation. */
public class RetokenizingTermVectorAnnotatorTest {

  private static final OffsetAwareNormalizer DEHYPHENATION =
      DehyphenationCharSequenceNormalizer.getInstance();

  /** {@return an annotator using dehyphenation and whitespace tokenization} */
  private RetokenizingTermVectorAnnotator annotator() {
    return new RetokenizingTermVectorAnnotator(DEHYPHENATION, WhitespaceTokenizer.INSTANCE);
  }

  /** A joined word produces one term with the original-text occurrence span. */
  @Test
  void testDehyphenationFusesTheBrokenWordIntoOneTerm() {
    final String text = "word litiga-\ntion word";
    assertEquals(4, WhitespaceTokenizer.INSTANCE.tokenizePos(text).length);

    final Document document = annotator().annotate(Document.of(text));

    final List<Annotation<TermVector>> vectors =
        document.get(TermVectorAnnotator.TERM_VECTORS);
    assertEquals(2, vectors.size());
    // First-occurrence order: "word" precedes "litigation".
    assertEquals(new TermVector("word", 2, List.of(new Span(0, 4), new Span(18, 22))),
        vectors.get(0).value());
    assertEquals(new TermVector("litigation", 1, List.of(new Span(5, 17))),
        vectors.get(1).value());
    // The occurrence span covers the original "litiga-\ntion", not the normalized form.
    assertEquals("litiga-\ntion",
        vectors.get(1).value().spans().get(0).getCoveredText(text).toString());
  }

  /** Dehyphenation followed by case conversion preserves original-text offsets. */
  @Test
  void testComposedPipelineKeepsOriginalExactSpans() {
    final OffsetAwareNormalizer pipeline = TextNormalizer.builder()
        .with(DEHYPHENATION)
        .fullCaseFold()
        .buildAligned();
    final String text = "LITIGA-\nTION settles";
    final Document document = new RetokenizingTermVectorAnnotator(pipeline,
        WhitespaceTokenizer.INSTANCE).annotate(Document.of(text));

    final List<Annotation<TermVector>> vectors =
        document.get(TermVectorAnnotator.TERM_VECTORS);
    assertEquals(2, vectors.size());
    assertEquals(new TermVector("litigation", 1, List.of(new Span(0, 12))),
        vectors.get(0).value());
    assertEquals(new TermVector("settles", 1, List.of(new Span(13, 20))),
        vectors.get(1).value());
    assertEquals("LITIGA-\nTION",
        vectors.get(0).value().spans().get(0).getCoveredText(text).toString());
  }

  @Test
  void testScoringOnlyModeCountsWithoutOffsets() {
    final Document document = new RetokenizingTermVectorAnnotator(DEHYPHENATION,
        WhitespaceTokenizer.INSTANCE, TermVectorAnnotator.Mode.SCORING_ONLY)
        .annotate(Document.of("word litiga-\ntion word"));

    final List<Annotation<TermVector>> vectors =
        document.get(TermVectorAnnotator.TERM_VECTORS);
    assertEquals(2, vectors.size());
    assertEquals(TermVector.count("word", 2), vectors.get(0).value());
    assertEquals(TermVector.count("litigation", 1), vectors.get(1).value());
    for (final Annotation<TermVector> vector : vectors) {
      assertTrue(vector.value().spans().isEmpty());
    }
  }

  /** This deletion-only normalizer maps non-empty terms to non-empty source spans. */
  @Test
  void testEveryOccurrenceSpanCoversOriginalText() {
    final String text = "word litiga-\ntion com-\r\n  plete";
    final Document document = annotator().annotate(Document.of(text));
    for (final Annotation<TermVector> vector : document.get(TermVectorAnnotator.TERM_VECTORS)) {
      for (final Span span : vector.value().spans()) {
        assertTrue(span.getStart() < span.getEnd(),
            "occurrence span of " + vector.value().term() + " must not be empty or inverted");
        assertTrue(span.getEnd() <= text.length());
      }
    }
  }

  /**
   * With no edit to apply, the annotator's output equals {@link TermVectorAnnotator}'s:
   * same terms, same counts, same spans, same order.
   */
  @Test
  void testNoEditDocumentMatchesTermVectorAnnotator() {
    final String text = "The dog barks";
    final Document retokenized = annotator().annotate(Document.of(text));

    final List<Annotation<String>> tokens = new ArrayList<>();
    for (final Span span : WhitespaceTokenizer.INSTANCE.tokenizePos(text)) {
      tokens.add(new Annotation<>(span, span.getCoveredText(text).toString()));
    }
    final Document rolledUp = new TermVectorAnnotator(DEHYPHENATION)
        .annotate(Document.of(text).with(Layers.TOKENS, tokens));

    assertEquals(rolledUp.get(TermVectorAnnotator.TERM_VECTORS),
        retokenized.get(TermVectorAnnotator.TERM_VECTORS));
  }

  @Test
  void testEmptyDocumentYieldsPresentButEmptyLayer() {
    final Document document = annotator().annotate(Document.of(""));
    assertTrue(document.layers().contains(TermVectorAnnotator.TERM_VECTORS));
    assertTrue(document.get(TermVectorAnnotator.TERM_VECTORS).isEmpty());
  }

  @Test
  void testRequiresNothingAndProvidesTermVectors() {
    final RetokenizingTermVectorAnnotator annotator = annotator();
    assertEquals(Set.of(), annotator.requires());
    assertEquals(Set.of(TermVectorAnnotator.TERM_VECTORS), annotator.provides());
  }

  @Test
  void testNullArgumentsAreRejected() {
    final WhitespaceTokenizer tokenizer = WhitespaceTokenizer.INSTANCE;
    assertThrows(IllegalArgumentException.class,
        () -> new RetokenizingTermVectorAnnotator(null, tokenizer));
    assertThrows(IllegalArgumentException.class,
        () -> new RetokenizingTermVectorAnnotator(DEHYPHENATION, null));
    assertThrows(IllegalArgumentException.class,
        () -> new RetokenizingTermVectorAnnotator(DEHYPHENATION, tokenizer, null));
    assertThrows(IllegalArgumentException.class, () -> annotator().annotate(null));
  }
}
