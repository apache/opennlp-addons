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

package opennlp.embeddings;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import opennlp.embeddings.StaticEmbeddingModel.Normalization;
import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.DocumentAnalyzer;
import opennlp.tools.document.Layers;
import opennlp.tools.embeddings.EmbeddingAnnotator;
import opennlp.tools.tokenize.SimpleTokenizer;
import opennlp.tools.tokenize.TokenizerAnnotator;
import opennlp.tools.util.Span;

/** Runs the annotator with locally generated static-model fixtures. */
class EmbeddingAnnotatorModelTest {

  /**
   * A loaded WordPiece model produces the expected token vectors through the pipeline.
   *
   * @param normalization The model's normalization setting.
   * @param dir The temporary model directory.
   * @throws IOException Thrown if a fixture cannot be written or loaded.
   */
  @ParameterizedTest
  @EnumSource(Normalization.class)
  void testWordPieceTokenPipeline(Normalization normalization, @TempDir Path dir)
      throws IOException {
    final StaticEmbeddingModel model = EmbeddingTestFixtures.loadAnalogyModel(dir, normalization);
    final EmbeddingAnnotator annotator = new EmbeddingAnnotator(model, Layers.TOKENS);
    final DocumentAnalyzer analyzer = DocumentAnalyzer.builder()
        .add(new TokenizerAnnotator(SimpleTokenizer.INSTANCE))
        .add(annotator)
        .build();
    final Document document = analyzer.analyze("king queen king xyzzy");
    final List<Annotation<float[]>> vectors = document.get(annotator.layer());
    Assertions.assertEquals(4, vectors.size());
    assertVector(3, 3, normalization, vectors.get(0).value());
    assertVector(2, 4, normalization, vectors.get(1).value());
    Assertions.assertSame(vectors.get(0).value(), vectors.get(2).value());
    Assertions.assertArrayEquals(new float[model.dimension()], vectors.get(3).value());
    for (int i = 0; i < vectors.size(); i++) {
      Assertions.assertEquals(document.get(Layers.TOKENS).get(i).span(), vectors.get(i).span());
      Assertions.assertEquals(model.dimension(), vectors.get(i).value().length);
    }
  }

  /**
   * Sentence pooling belongs to the model; the annotator preserves its result and span.
   *
   * @param normalization The model's normalization setting.
   * @param dir The temporary model directory.
   * @throws IOException Thrown if a fixture cannot be written or loaded.
   */
  @ParameterizedTest
  @EnumSource(Normalization.class)
  void testWordPieceSentenceLayer(Normalization normalization, @TempDir Path dir)
      throws IOException {
    final StaticEmbeddingModel model = EmbeddingTestFixtures.loadAnalogyModel(dir, normalization);
    final Document input = Document.of("king queen. apple.").with(Layers.SENTENCES, List.of(
        new Annotation<>(new Span(0, 11), "first"),
        new Annotation<>(new Span(12, 18), "second")));
    final EmbeddingAnnotator annotator = new EmbeddingAnnotator(model, Layers.SENTENCES);
    final List<Annotation<float[]>> vectors = annotator.annotate(input).get(annotator.layer());
    assertVector(2.5f, 3.5f, normalization, vectors.get(0).value());
    assertVector(-3, -1, normalization, vectors.get(1).value());
    Assertions.assertEquals(new Span(0, 11), vectors.get(0).span());
    Assertions.assertEquals(new Span(12, 18), vectors.get(1).span());
  }

  /**
   * SentencePiece-backed embedding retains all model dimensions through the same API.
   *
   * @param dir The temporary model directory.
   * @throws IOException Thrown if a fixture cannot be written or loaded.
   */
  @Test
  void testSentencePieceModel(@TempDir Path dir) throws IOException {
    EmbeddingTestFixtures.writeSentencePieceDirectory(dir);
    final StaticEmbeddingModel model = StaticEmbeddingModel.loadSentencePiece(
        dir.resolve("sentencepiece.bpe.model"), dir.resolve("tokenizer.json"),
        dir.resolve("model.safetensors"), Normalization.L2);
    final Document input = Document.of("a a").with(Layers.TOKENS, List.of(
        new Annotation<>(new Span(0, 1), "a"), new Annotation<>(new Span(2, 3), "a")));
    final EmbeddingAnnotator annotator = new EmbeddingAnnotator(model, Layers.TOKENS);
    final List<Annotation<float[]>> vectors = annotator.annotate(input).get(annotator.layer());
    Assertions.assertEquals(EmbeddingTestFixtures.SENTENCEPIECE_DIMENSION, model.dimension());
    Assertions.assertEquals(model.dimension(), vectors.get(0).value().length);
    Assertions.assertArrayEquals(model.embed("a"), vectors.get(0).value());
    Assertions.assertSame(vectors.get(0).value(), vectors.get(1).value());
    Assertions.assertEquals(new Span(2, 3), vectors.get(1).span());
  }

  /**
   * Compares a fixture vector with an independently calculated expected value.
   *
   * @param first The unnormalized first component.
   * @param second The unnormalized second component.
   * @param normalization Whether to normalize the expected vector.
   * @param actual The annotator's vector.
   */
  private void assertVector(float first, float second, Normalization normalization, float[] actual) {
    final double divisor = normalization == Normalization.L2
        ? Math.sqrt((double) first * first + (double) second * second) : 1;
    Assertions.assertArrayEquals(new float[] {(float) (first / divisor), (float) (second / divisor)},
        actual, 1e-6f);
  }
}
