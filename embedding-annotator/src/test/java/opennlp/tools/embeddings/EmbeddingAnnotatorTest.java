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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.LayerKey;
import opennlp.tools.document.Layers;
import opennlp.tools.embeddings.EmbeddingAnnotatorTestSupport.RecordingEmbedder;
import opennlp.tools.util.Span;

public class EmbeddingAnnotatorTest {

  /** Embeds text as its length and first character code, enough to observe alignment. */
  private static final TextEmbedder FIXTURE = new TextEmbedder() {
    /** {@inheritDoc} */
    @Override
    public float[] embed(CharSequence text) {
      return new float[] {text.length(), text.isEmpty() ? 0 : text.charAt(0)};
    }

    /** {@inheritDoc} */
    @Override
    public int dimension() {
      return 2;
    }
  };

  /** Token and sentence vectors keep their source spans under separate keys. */
  @Test
  void testTokenAndSentenceEmbeddingsCoexist() {
    final Document base = EmbeddingAnnotatorTestSupport.sentencesAndTokens();

    final EmbeddingAnnotator overTokens = new EmbeddingAnnotator(FIXTURE, Layers.TOKENS);
    final EmbeddingAnnotator overSentences =
        new EmbeddingAnnotator(FIXTURE, Layers.SENTENCES);
    final Document document = overSentences.annotate(overTokens.annotate(base));

    final List<Annotation<float[]>> tokens = document.get(overTokens.layer());
    final List<Annotation<float[]>> sentences = document.get(overSentences.layer());
    Assertions.assertEquals(6, tokens.size());
    Assertions.assertEquals(2, sentences.size());
    Assertions.assertEquals(4.0f, tokens.get(0).value()[0]);
    Assertions.assertEquals('D', (int) tokens.get(0).value()[1]);
    Assertions.assertEquals(10.0f, sentences.get(0).value()[0]);
    Assertions.assertEquals(new Span(0, 4), tokens.get(0).span());
    Assertions.assertNotEquals(overTokens.layer(), overSentences.layer());
  }

  /** Public entry points reject null arguments. */
  @Test
  void testInvalidArguments() {
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new EmbeddingAnnotator(null, Layers.TOKENS));
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> new EmbeddingAnnotator(FIXTURE, null));
    final EmbeddingAnnotator annotator = new EmbeddingAnnotator(FIXTURE, Layers.TOKENS);
    Assertions.assertThrows(IllegalArgumentException.class, () -> annotator.annotate(null));
  }

  /** An empty source layer still produces a present, empty vector layer. */
  @Test
  void testEmptySourceLayerYieldsEmptyProvidedLayer() {
    final Document base = Document.of("no tokens were found").with(Layers.TOKENS, List.of());
    final EmbeddingAnnotator annotator = new EmbeddingAnnotator(FIXTURE, Layers.TOKENS);
    final Document document = annotator.annotate(base);
    Assertions.assertTrue(document.layers().contains(annotator.layer()));
    Assertions.assertTrue(document.get(annotator.layer()).isEmpty());
  }

  /** Missing input layers are distinct from present, empty layers. */
  @Test
  void testAbsentSourceLayerIsRejected() {
    final EmbeddingAnnotator annotator = new EmbeddingAnnotator(FIXTURE, Layers.TOKENS);
    final IllegalArgumentException e = Assertions.assertThrows(IllegalArgumentException.class,
        () -> annotator.annotate(Document.of("never tokenized")));
    Assertions.assertEquals("document lacks the required layer opennlp:tokens<String>",
        e.getMessage());
  }

  /** Document-scoped layers have no spans and cannot supply covered text. */
  @Test
  void testDocumentScopedSourceLayerIsRejected() {
    final LayerKey<String> language = Layers.documentKey("language", String.class);
    final IllegalArgumentException e = Assertions.assertThrows(IllegalArgumentException.class,
        () -> new EmbeddingAnnotator(FIXTURE, language));
    Assertions.assertEquals("source must be a positional layer: opennlp:language<String>",
        e.getMessage());
  }

  /** The default embedding loop must also return vectors of the declared dimension. */
  @Test
  void testInconsistentVectorDimensionsAreRejected() {
    final Document base = Document.of("ab cdef").with(Layers.TOKENS, List.of(
        new Annotation<>(new Span(0, 2), "ab"),
        new Annotation<>(new Span(3, 7), "cdef")));
    final EmbeddingAnnotator annotator =
        new EmbeddingAnnotator(new TextLengthEmbedder(), Layers.TOKENS);
    final IllegalArgumentException error = Assertions.assertThrows(IllegalArgumentException.class,
        () -> annotator.annotate(base));
    Assertions.assertEquals("embedder returned a vector of dimension 4 at batch index 1; expected 2",
        error.getMessage());
  }

  /** Null vectors from the default embedding loop are rejected. */
  @Test
  void testNullVectorFromEmbedderFailsLoud() {
    final Document base = Document.of("boom").with(Layers.TOKENS,
        List.of(new Annotation<>(new Span(0, 4), "boom")));
    final EmbeddingAnnotator annotator =
        new EmbeddingAnnotator(new NullVectorEmbedder(), Layers.TOKENS);
    final IllegalArgumentException e = Assertions.assertThrows(
        IllegalArgumentException.class, () -> annotator.annotate(base));
    Assertions.assertEquals("embedder returned a null vector at batch index 0", e.getMessage());
  }

  /** A second annotation attempt identifies the duplicate output layer. */
  @Test
  void testAnnotatingSameDocumentTwiceIsRejected() {
    final Document base = Document.of("once").with(Layers.TOKENS,
        List.of(new Annotation<>(new Span(0, 4), "once")));
    final EmbeddingAnnotator annotator = new EmbeddingAnnotator(FIXTURE, Layers.TOKENS);
    final Document annotated = annotator.annotate(base);
    final IllegalArgumentException e = Assertions.assertThrows(
        IllegalArgumentException.class, () -> annotator.annotate(annotated));
    Assertions.assertTrue(e.getMessage().contains("already present"));
    Assertions.assertTrue(e.getMessage().contains("embeddings:opennlp:tokens"));
  }

  /**
   * Concurrent calls with a stateless embedder match sequential results.
   *
   * @throws Exception Thrown if a worker fails or does not finish in time.
   */
  @Test
  void testConcurrentAnnotationYieldsIdenticalResults() throws Exception {
    final Document docA = Document.of("Dogs bark.").with(Layers.TOKENS, List.of(
        new Annotation<>(new Span(0, 4), "Dogs"),
        new Annotation<>(new Span(5, 9), "bark"),
        new Annotation<>(new Span(9, 10), ".")));
    final Document docB = Document.of("Cats nap.").with(Layers.TOKENS, List.of(
        new Annotation<>(new Span(0, 4), "Cats"),
        new Annotation<>(new Span(5, 8), "nap"),
        new Annotation<>(new Span(8, 9), ".")));
    final EmbeddingAnnotator annotator = new EmbeddingAnnotator(FIXTURE, Layers.TOKENS);
    final List<Annotation<float[]>> expectedA = annotator.annotate(docA).get(annotator.layer());
    final List<Annotation<float[]>> expectedB = annotator.annotate(docB).get(annotator.layer());

    final int threads = 8;
    final ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      final CountDownLatch start = new CountDownLatch(1);
      final List<Future<?>> workers = new ArrayList<>(threads);
      for (int t = 0; t < threads; t++) {
        final Document input = t % 2 == 0 ? docA : docB;
        final List<Annotation<float[]>> expected = t % 2 == 0 ? expectedA : expectedB;
        workers.add(pool.submit(() -> {
          start.await();
          for (int round = 0; round < 50; round++) {
            final List<Annotation<float[]>> actual =
                annotator.annotate(input).get(annotator.layer());
            Assertions.assertEquals(expected.size(), actual.size());
            for (int i = 0; i < expected.size(); i++) {
              Assertions.assertEquals(expected.get(i).span(), actual.get(i).span());
              Assertions.assertArrayEquals(expected.get(i).value(), actual.get(i).value());
            }
          }
          return null;
        }));
      }
      start.countDown();
      for (final Future<?> worker : workers) {
        worker.get(30, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  /** One batch embeds distinct texts; repeated texts share a vector on separate spans. */
  @Test
  void testDistinctCoveredTextsEmbedInOneBatch() {
    final Document base = Document.of("Dogs chase Dogs").with(Layers.TOKENS, List.of(
        new Annotation<>(new Span(0, 4), "Dogs"),
        new Annotation<>(new Span(5, 10), "chase"),
        new Annotation<>(new Span(11, 15), "Dogs")));
    final RecordingEmbedder embedder = new RecordingEmbedder();
    final EmbeddingAnnotator annotator = new EmbeddingAnnotator(embedder, Layers.TOKENS);
    final List<Annotation<float[]>> vectors = annotator.annotate(base).get(annotator.layer());

    Assertions.assertEquals(1, embedder.batchCalls);
    Assertions.assertEquals(0, embedder.singleCalls);
    Assertions.assertEquals(List.of("Dogs", "chase"), embedder.seen);

    Assertions.assertEquals(3, vectors.size());
    Assertions.assertEquals(new Span(0, 4), vectors.get(0).span());
    Assertions.assertEquals(new Span(5, 10), vectors.get(1).span());
    Assertions.assertEquals(new Span(11, 15), vectors.get(2).span());
    Assertions.assertArrayEquals(new float[] {0, 4}, vectors.get(0).value());
    Assertions.assertArrayEquals(new float[] {1, 5}, vectors.get(1).value());
    Assertions.assertSame(vectors.get(0).value(), vectors.get(2).value());
  }

  /** A short batch is rejected before vectors can be assigned to the wrong spans. */
  @Test
  void testShortBatchFromEmbedderFailsLoud() {
    final Document base = Document.of("ab cd").with(Layers.TOKENS, List.of(
        new Annotation<>(new Span(0, 2), "ab"),
        new Annotation<>(new Span(3, 5), "cd")));
    final EmbeddingAnnotator annotator =
        new EmbeddingAnnotator(new ShortBatchEmbedder(), Layers.TOKENS);
    final IllegalArgumentException e = Assertions.assertThrows(
        IllegalArgumentException.class, () -> annotator.annotate(base));
    Assertions.assertEquals("embedder returned 1 vectors for 2 texts; embedAll must "
        + "return one vector per input, in input order", e.getMessage());
  }

  /** Returns one fewer vector than requested. */
  private static final class ShortBatchEmbedder implements TextEmbedder {

    /** {@inheritDoc} */
    @Override
    public float[][] embedAll(List<? extends CharSequence> texts) {
      return new float[texts.size() - 1][];
    }

    /** {@inheritDoc} */
    @Override
    public float[] embed(CharSequence text) {
      return new float[2];
    }

    /** {@inheritDoc} */
    @Override
    public int dimension() {
      return 2;
    }
  }

  /** Returns one component per character, violating the declared dimension. */
  private static final class TextLengthEmbedder implements TextEmbedder {

    /** {@inheritDoc} */
    @Override
    public float[] embed(CharSequence text) {
      if (text == null) {
        throw new IllegalArgumentException("text must not be null");
      }
      final float[] vector = new float[text.length()];
      for (int i = 0; i < vector.length; i++) {
        vector[i] = 1;
      }
      return vector;
    }

    /** {@inheritDoc} */
    @Override
    public int dimension() {
      return 2;
    }
  }

  /** Returns a null vector for every input. */
  private static final class NullVectorEmbedder implements TextEmbedder {

    /** {@inheritDoc} */
    @Override
    public float[] embed(CharSequence text) {
      return null;
    }

    /** {@inheritDoc} */
    @Override
    public int dimension() {
      return 2;
    }
  }
}
