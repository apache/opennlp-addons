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

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.LayerKey;
import opennlp.tools.document.Layers;
import opennlp.tools.embeddings.EmbeddingAnnotatorTestSupport.RecordingEmbedder;
import opennlp.tools.util.Span;

/** Tests original-text offsets, annotation order, and per-document batching. */
class EmbeddingAnnotatorAlignmentTest {

  /**
   * Covered text is embedded unchanged, regardless of the annotation value.
   *
   * @param covered The original text covered by each annotation.
   */
  @ParameterizedTest
  @ValueSource(strings = {"Dogs", "\uD83D\uDE00", "e\u0301", "\u00E9", "\u732B", "\t\n"})
  void testUnicodeCoveredText(String covered) {
    final String text = covered + " " + covered;
    final Span first = new Span(0, covered.length());
    final Span second = new Span(covered.length() + 1, text.length());
    final Document input = Document.of(text).with(Layers.TOKENS, List.of(
        new Annotation<>(first, "normalized"), new Annotation<>(second, "different value")));
    final RecordingEmbedder embedder = new RecordingEmbedder();
    final EmbeddingAnnotator annotator = new EmbeddingAnnotator(embedder, Layers.TOKENS);
    final List<Annotation<float[]>> vectors = annotator.annotate(input).get(annotator.layer());
    Assertions.assertEquals(List.of(covered), embedder.seen);
    Assertions.assertEquals(first, vectors.get(0).span());
    Assertions.assertEquals(second, vectors.get(1).span());
    Assertions.assertArrayEquals(new float[] {0, covered.length()}, vectors.get(0).value());
    Assertions.assertSame(vectors.get(0).value(), vectors.get(1).value());
  }

  /** Empty spans are embedded as empty text rather than omitted. */
  @Test
  void testEmptySpans() {
    final Document input = Document.of("ab").with(Layers.TOKENS, List.of(
        new Annotation<>(new Span(0, 0), "start"),
        new Annotation<>(new Span(2, 2), "end")));
    final RecordingEmbedder embedder = new RecordingEmbedder();
    final EmbeddingAnnotator annotator = new EmbeddingAnnotator(embedder, Layers.TOKENS);
    final List<Annotation<float[]>> vectors = annotator.annotate(input).get(annotator.layer());
    Assertions.assertEquals(List.of(""), embedder.seen);
    Assertions.assertEquals(1, embedder.batchCalls);
    Assertions.assertEquals(new Span(0, 0), vectors.get(0).span());
    Assertions.assertEquals(new Span(2, 2), vectors.get(1).span());
    Assertions.assertSame(vectors.get(0).value(), vectors.get(1).value());
  }

  /** Deduplication uses exact text, not case folding or canonical Unicode equivalence. */
  @Test
  void testDistinctTextIsNotNormalized() {
    final Document input = Document.of("Dogs dogs \u00E9 e\u0301").with(Layers.TOKENS, List.of(
        new Annotation<>(new Span(0, 4), "dogs"),
        new Annotation<>(new Span(5, 9), "dogs"),
        new Annotation<>(new Span(10, 11), "e"),
        new Annotation<>(new Span(12, 14), "e")));
    final RecordingEmbedder embedder = new RecordingEmbedder();
    final EmbeddingAnnotator annotator = new EmbeddingAnnotator(embedder, Layers.TOKENS);
    annotator.annotate(input);
    Assertions.assertEquals(List.of("Dogs", "dogs", "\u00E9", "e\u0301"), embedder.seen);
  }

  /** A typed, application-defined key works like a built-in source key. */
  @Test
  void testCustomLayerAndUnchangedInput() {
    final LayerKey<String> source = LayerKey.of("app:phrases", String.class);
    final LayerKey<String> language = LayerKey.document("app:language", String.class);
    final Document input = Document.of("a phrase")
        .with(language, List.of(Annotation.of("en")))
        .with(source, List.of(new Annotation<>(new Span(2, 8, "NP", 0.75), "PHRASE")));
    final EmbeddingAnnotator annotator = new EmbeddingAnnotator(new RecordingEmbedder(),
        LayerKey.of("app:phrases", String.class));
    final Document output = annotator.annotate(input);
    Assertions.assertEquals("embeddings:app:phrases", annotator.layer().id());
    Assertions.assertEquals(Set.of(source), annotator.requires());
    Assertions.assertEquals(Set.of(annotator.layer()), annotator.provides());
    Assertions.assertEquals(Set.of(source, language), input.layers());
    Assertions.assertEquals(input.text(), output.text());
    Assertions.assertEquals(input.get(source), output.get(source));
    Assertions.assertEquals(input.get(language), output.get(language));
    Assertions.assertSame(input.get(source).get(0).span(),
        output.get(annotator.layer()).get(0).span());
    Assertions.assertThrows(UnsupportedOperationException.class,
        () -> output.get(annotator.layer()).clear());
    Assertions.assertThrows(UnsupportedOperationException.class,
        () -> annotator.requires().clear());
    Assertions.assertThrows(UnsupportedOperationException.class,
        () -> annotator.provides().clear());
  }

  /** Generated overlapping and unordered spans match an independent list-based mapping. */
  @Test
  void testGeneratedSpanRouting() {
    final String text = "red blue red \u9752\uD83D\uDE00 blue";
    final Random random = new Random(1888);
    for (int trial = 0; trial < 200; trial++) {
      final List<Annotation<String>> source = new ArrayList<>();
      final List<String> expectedTexts = new ArrayList<>();
      final int size = 1 + random.nextInt(30);
      final int[] expectedRoutes = new int[size];
      for (int i = 0; i < size; i++) {
        final int start = random.nextInt(text.length() + 1);
        final int end = start + random.nextInt(text.length() - start + 1);
        source.add(new Annotation<>(new Span(start, end), "ignored"));
        final String covered = text.substring(start, end);
        if (!expectedTexts.contains(covered)) {
          expectedTexts.add(covered);
        }
        expectedRoutes[i] = expectedTexts.indexOf(covered);
      }
      final Document input = Document.of(text).with(Layers.TOKENS, source);
      final RecordingEmbedder embedder = new RecordingEmbedder();
      final EmbeddingAnnotator annotator = new EmbeddingAnnotator(embedder, Layers.TOKENS);
      final List<Annotation<float[]>> vectors = annotator.annotate(input).get(annotator.layer());
      Assertions.assertEquals(expectedTexts, embedder.seen, "batch texts in trial " + trial);
      Assertions.assertEquals(1, embedder.batchCalls);
      Assertions.assertEquals(size, vectors.size());
      for (int i = 0; i < size; i++) {
        Assertions.assertSame(source.get(i).span(), vectors.get(i).span());
        Assertions.assertArrayEquals(
            new float[] {expectedRoutes[i], source.get(i).span().length()}, vectors.get(i).value());
        for (int j = 0; j < i; j++) {
          if (expectedRoutes[i] == expectedRoutes[j]) {
            Assertions.assertSame(vectors.get(i).value(), vectors.get(j).value());
          }
        }
      }
    }
  }

  /** Deduplication does not retain vectors between documents. */
  @Test
  void testNoCrossDocumentCache() {
    final Document input = Document.of("Dogs").with(Layers.TOKENS,
        List.of(new Annotation<>(new Span(0, 4), "Dogs")));
    final RecordingEmbedder embedder = new RecordingEmbedder();
    final EmbeddingAnnotator annotator = new EmbeddingAnnotator(embedder, Layers.TOKENS);
    final float[] first = annotator.annotate(input).get(annotator.layer()).get(0).value();
    final float[] second = annotator.annotate(input).get(annotator.layer()).get(0).value();
    Assertions.assertEquals(2, embedder.batchCalls);
    Assertions.assertNotSame(first, second);
    Assertions.assertArrayEquals(first, second);
  }
}
