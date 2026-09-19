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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.Layers;
import opennlp.tools.tokenize.Tokenizer;
import opennlp.tools.util.Span;
import opennlp.tools.util.normalizer.DehyphenationCharSequenceNormalizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks empty tokens, term ordering and preservation of input layers. */
class RetokenizingTermVectorContractTest {

  private static final String TEXT = "word litiga-\ntion word";
  private static final String NORMALIZED = "word litigation word";

  /**
   * Empty spans before, between and after words do not contribute a term or frequency.
   *
   * @param mode The term-vector mode.
   */
  @ParameterizedTest
  @EnumSource(TermVectorAnnotator.Mode.class)
  void testEmptyTokensAreOmitted(TermVectorAnnotator.Mode mode) {
    final Span[] spans = {
        new Span(0, 0), new Span(0, 4), new Span(4, 4),
        new Span(5, 15), new Span(15, 15), new Span(16, 20), new Span(20, 20)
    };
    final Document input = Document.of(TEXT).with(Layers.TOKENS,
        List.of(new Annotation<>(new Span(0, 4), "existing")));
    final Document output = annotator(spans, mode).annotate(input);
    final List<TermVector> expected = mode == TermVectorAnnotator.Mode.FULL
        ? List.of(TermVector.withSpans("word", List.of(new Span(0, 4), new Span(18, 22))),
            TermVector.withSpans("litigation", List.of(new Span(5, 17))))
        : List.of(TermVector.count("word", 2), TermVector.count("litigation", 1));

    assertEquals(expected, values(output));
    assertEquals(input.get(Layers.TOKENS), output.get(Layers.TOKENS));
    assertEquals(TEXT, output.text().toString());
    assertTrue(input.get(TermVectorAnnotator.TERM_VECTORS).isEmpty());
  }

  /**
   * A tokenizer that returns only empty spans produces a present, empty layer.
   *
   * @param mode The term-vector mode.
   */
  @ParameterizedTest
  @EnumSource(TermVectorAnnotator.Mode.class)
  void testOnlyEmptyTokensProduceNoTerms(TermVectorAnnotator.Mode mode) {
    final Document output = annotator(new Span[] {new Span(0, 0), new Span(20, 20)}, mode)
        .annotate(Document.of(TEXT));

    assertTrue(output.layers().contains(TermVectorAnnotator.TERM_VECTORS));
    assertEquals(List.of(), output.get(TermVectorAnnotator.TERM_VECTORS));
  }

  /**
   * Empty spans are omitted consistently with the existing token-layer annotator.
   *
   * @param mode The term-vector mode.
   */
  @ParameterizedTest
  @EnumSource(TermVectorAnnotator.Mode.class)
  void testExistingAnnotatorParity(TermVectorAnnotator.Mode mode) {
    final Span[] spans = {new Span(20, 20), new Span(16, 20), new Span(5, 15),
        new Span(0, 4), new Span(0, 0)};
    final List<Annotation<String>> tokens = new ArrayList<>();
    for (final Span span : spans) {
      tokens.add(new Annotation<>(span, span.getCoveredText(NORMALIZED).toString()));
    }
    final Document input = Document.of(NORMALIZED).with(Layers.TOKENS, tokens);
    final Document expected = new TermVectorAnnotator(mode).annotate(input);
    final Document actual = annotator(spans, mode).annotate(input);

    assertEquals(expected.get(TermVectorAnnotator.TERM_VECTORS),
        actual.get(TermVectorAnnotator.TERM_VECTORS));
  }

  /**
   * Builds an annotator with a tokenizer that returns the supplied spans.
   *
   * @param spans The spans in normalized text.
   * @param mode The term-vector mode.
   * @return The annotator.
   */
  private RetokenizingTermVectorAnnotator annotator(Span[] spans,
      TermVectorAnnotator.Mode mode) {
    final Tokenizer tokenizer = new Tokenizer() {
      /** {@inheritDoc} */
      @Override
      public String[] tokenize(String text) {
        return Span.spansToStrings(tokenizePos(text), text);
      }

      /** {@inheritDoc} */
      @Override
      public Span[] tokenizePos(String text) {
        assertEquals(NORMALIZED, text);
        return spans.clone();
      }
    };
    return new RetokenizingTermVectorAnnotator(
        DehyphenationCharSequenceNormalizer.getInstance(), tokenizer, mode);
  }

  /**
   * Returns the term-vector values in layer order.
   *
   * @param document The annotated document.
   * @return The term vectors.
   */
  private List<TermVector> values(Document document) {
    return document.get(TermVectorAnnotator.TERM_VECTORS).stream()
        .map(Annotation::value).toList();
  }
}
