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

package opennlp.tools.embeddings;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.DocumentAnalyzer;
import opennlp.tools.document.Layers;
import opennlp.tools.tokenize.SimpleTokenizer;
import opennlp.tools.tokenize.TokenizerAnnotator;
import opennlp.tools.util.Span;

/**
 * Examples using the document pipeline and tokenizer with a test embedder.
 * Character sums make the expected values explicit; they are not semantic embeddings.
 */
public class EmbeddingAnnotatorUsageTest {

  /** Sums character codes into three components by character index modulo three. */
  private static final class CharSumEmbedder implements TextEmbedder {

    /** {@inheritDoc} */
    @Override
    public float[] embed(CharSequence text) {
      if (text == null) {
        throw new IllegalArgumentException("text must not be null");
      }
      final float[] vector = new float[3];
      for (int i = 0; i < text.length(); i++) {
        vector[i % 3] += text.charAt(i);
      }
      return vector;
    }

    /** {@inheritDoc} */
    @Override
    public int dimension() {
      return 3;
    }
  }

  /** The manual's tokenizer-and-embedding pipeline preserves every token span. */
  @Test
  void testPipelineEmbedsEveryToken() {
    final EmbeddingAnnotator annotator =
        new EmbeddingAnnotator(new CharSumEmbedder(), Layers.TOKENS);
    final DocumentAnalyzer analyzer = DocumentAnalyzer.builder()
        .add(new TokenizerAnnotator(SimpleTokenizer.INSTANCE))
        .add(annotator)
        .build();
    final Document embedded = analyzer.analyze("Dogs bark loudly");

    Assertions.assertEquals("embeddings:opennlp:tokens", annotator.layer().id());
    Assertions.assertEquals(float[].class, annotator.layer().type());

    final List<Annotation<String>> tokens = embedded.get(Layers.TOKENS);
    final List<Annotation<float[]>> vectors = embedded.get(annotator.layer());
    Assertions.assertEquals(3, tokens.size());
    Assertions.assertEquals(tokens.size(), vectors.size());
    final Annotation<float[]> first = vectors.getFirst();
    final String covered = first.span().getCoveredText(embedded.text()).toString();
    Assertions.assertEquals("Dogs", covered);
    final float[] editable = first.value().clone();
    editable[0] = -1;
    Assertions.assertEquals(183, first.value()[0]);
    for (int i = 0; i < tokens.size(); i++) {
      Assertions.assertEquals(tokens.get(i).span(), vectors.get(i).span());
    }
    // "Dogs" = D(68) o(111) g(103) s(115): components (68+115, 111, 103).
    Assertions.assertArrayEquals(new float[] {183, 111, 103}, vectors.get(0).value());
    // "bark" = b(98) a(97) r(114) k(107): components (98+107, 97, 114).
    Assertions.assertArrayEquals(new float[] {205, 97, 114}, vectors.get(1).value());
    // "loudly" = l(108) o(111) u(117) d(100) l(108) y(121): (108+100, 111+108, 117+121).
    Assertions.assertArrayEquals(new float[] {208, 219, 238}, vectors.get(2).value());
  }

  /** Token and sentence vectors are computed from their respective covered texts. */
  @Test
  void testTokenAndSentenceAnnotatorsCoexist() {
    final Document base = EmbeddingAnnotatorTestSupport.sentencesAndTokens();

    final CharSumEmbedder embedder = new CharSumEmbedder();
    final EmbeddingAnnotator overTokens = new EmbeddingAnnotator(embedder, Layers.TOKENS);
    final EmbeddingAnnotator overSentences = new EmbeddingAnnotator(embedder, Layers.SENTENCES);
    final Document document = overSentences.annotate(overTokens.annotate(base));

    Assertions.assertEquals("embeddings:opennlp:tokens", overTokens.layer().id());
    Assertions.assertEquals("embeddings:opennlp:sentences", overSentences.layer().id());
    Assertions.assertTrue(document.layers().contains(overTokens.layer()));
    Assertions.assertTrue(document.layers().contains(overSentences.layer()));

    final List<Annotation<float[]>> sentences = document.get(overSentences.layer());
    Assertions.assertEquals(2, sentences.size());
    // "Dogs bark." = 68 111 103 115 32 98 97 114 107 46 folded by index modulo three.
    Assertions.assertArrayEquals(new float[] {326, 257, 308}, sentences.get(0).value());
    // "Cats nap." = 67 97 116 115 32 110 97 112 46 folded by index modulo three.
    Assertions.assertArrayEquals(new float[] {279, 241, 272}, sentences.get(1).value());
    Assertions.assertEquals(new Span(0, 10), sentences.get(0).span());
    Assertions.assertEquals(new Span(11, 20), sentences.get(1).span());

    final List<Annotation<float[]>> tokens = document.get(overTokens.layer());
    Assertions.assertEquals(6, tokens.size());
    // "Cats" = C(67) a(97) t(116) s(115): components (67+115, 97, 116).
    Assertions.assertArrayEquals(new float[] {182, 97, 116}, tokens.get(3).value());
    // "." is a single character, code 46, landing in the first component only.
    Assertions.assertArrayEquals(new float[] {46, 0, 0}, tokens.get(5).value());
  }

  /** The pipeline rejects a missing tokenizer before running any analysis. */
  @Test
  void testMissingPipelineInput() {
    final EmbeddingAnnotator annotator =
        new EmbeddingAnnotator(new CharSumEmbedder(), Layers.TOKENS);
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> DocumentAnalyzer.builder().add(annotator).build());
  }

  /** Two embedders over the same source collide under the current derived-key API. */
  @Test
  void testDuplicatePipelineOutput() {
    Assertions.assertThrows(IllegalArgumentException.class, () -> DocumentAnalyzer.builder()
        .add(new TokenizerAnnotator(SimpleTokenizer.INSTANCE))
        .add(new EmbeddingAnnotator(new CharSumEmbedder(), Layers.TOKENS))
        .add(new EmbeddingAnnotator(new CharSumEmbedder(), Layers.TOKENS))
        .build());
  }

  /** An empty analyzed document retains the declared token and vector layers. */
  @Test
  void testEmptyPipelineInput() {
    final EmbeddingAnnotator annotator =
        new EmbeddingAnnotator(new CharSumEmbedder(), Layers.TOKENS);
    final DocumentAnalyzer analyzer = DocumentAnalyzer.builder()
        .add(new TokenizerAnnotator(SimpleTokenizer.INSTANCE))
        .add(annotator)
        .build();
    final Document document = analyzer.analyze("");
    Assertions.assertTrue(document.layers().contains(Layers.TOKENS));
    Assertions.assertTrue(document.layers().contains(annotator.layer()));
    Assertions.assertTrue(document.get(annotator.layer()).isEmpty());
  }
}
