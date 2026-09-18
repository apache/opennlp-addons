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
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.Layers;
import opennlp.tools.util.Span;

/** Tests input validation and malformed embedding batches. */
class EmbeddingAnnotatorValidationTest {

  private static final Document INPUT = Document.of("ab cdef").with(Layers.TOKENS, List.of(
      new Annotation<>(new Span(0, 2), "ab"),
      new Annotation<>(new Span(3, 7), "cdef")));

  /** Null batches are rejected with an argument error, not a null dereference. */
  @Test
  void testNullBatch() {
    final BatchEmbedder embedder = new BatchEmbedder(2, null);
    final EmbeddingAnnotator annotator = new EmbeddingAnnotator(embedder, Layers.TOKENS);
    final IllegalArgumentException error = Assertions.assertThrows(IllegalArgumentException.class,
        () -> annotator.annotate(INPUT));
    Assertions.assertEquals("embedder returned a null batch", error.getMessage());
    Assertions.assertEquals(Set.of(Layers.TOKENS), INPUT.layers());
  }

  /**
   * Both missing and surplus vectors violate the batch-size contract.
   *
   * @param size The number of returned vectors.
   */
  @ParameterizedTest
  @ValueSource(ints = {0, 1, 3})
  void testWrongBatchSize(int size) {
    final BatchEmbedder embedder = new BatchEmbedder(2, new float[size][2]);
    final EmbeddingAnnotator annotator = new EmbeddingAnnotator(embedder, Layers.TOKENS);
    final IllegalArgumentException error = Assertions.assertThrows(IllegalArgumentException.class,
        () -> annotator.annotate(INPUT));
    Assertions.assertEquals("embedder returned " + size + " vectors for 2 texts; embedAll must "
        + "return one vector per input, in input order", error.getMessage());
  }

  /**
   * Null vectors identify their position in the distinct-text batch.
   *
   * @param index The position of the null vector.
   */
  @ParameterizedTest
  @ValueSource(ints = {0, 1})
  void testNullVector(int index) {
    final float[][] batch = new float[2][2];
    batch[index] = null;
    final EmbeddingAnnotator annotator =
        new EmbeddingAnnotator(new BatchEmbedder(2, batch), Layers.TOKENS);
    final IllegalArgumentException error = Assertions.assertThrows(IllegalArgumentException.class,
        () -> annotator.annotate(INPUT));
    Assertions.assertEquals("embedder returned a null vector at batch index " + index,
        error.getMessage());
  }

  /**
   * A vector must match the embedder's declared dimension, including later entries.
   *
   * @param dimension The incorrect returned vector length.
   */
  @ParameterizedTest
  @ValueSource(ints = {0, 1, 3, 4})
  void testWrongVectorDimension(int dimension) {
    final float[][] batch = {new float[2], new float[dimension]};
    final EmbeddingAnnotator annotator =
        new EmbeddingAnnotator(new BatchEmbedder(2, batch), Layers.TOKENS);
    final IllegalArgumentException error = Assertions.assertThrows(IllegalArgumentException.class,
        () -> annotator.annotate(INPUT));
    Assertions.assertEquals("embedder returned a vector of dimension " + dimension
        + " at batch index 1; expected 2", error.getMessage());
    Assertions.assertEquals(Set.of(Layers.TOKENS), INPUT.layers());
  }

  /** Consistent lengths are still invalid when they differ from the declared dimension. */
  @Test
  void testUniformWrongDimension() {
    final EmbeddingAnnotator annotator =
        new EmbeddingAnnotator(new BatchEmbedder(3, new float[2][2]), Layers.TOKENS);
    final IllegalArgumentException error = Assertions.assertThrows(IllegalArgumentException.class,
        () -> annotator.annotate(INPUT));
    Assertions.assertEquals("embedder returned a vector of dimension 2 at batch index 0; expected 3",
        error.getMessage());
  }

  /**
   * Matching dimensions retain all components without resizing or normalization.
   *
   * @param dimension The declared and returned vector length.
   */
  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 384, 768})
  void testMatchingDimensions(int dimension) {
    final float[][] batch = new float[2][dimension];
    for (int i = 0; i < dimension; i++) {
      batch[0][i] = i + 1;
      batch[1][i] = -i - 1;
    }
    final BatchEmbedder embedder = new BatchEmbedder(dimension, batch);
    final EmbeddingAnnotator annotator = new EmbeddingAnnotator(embedder, Layers.TOKENS);
    final List<Annotation<float[]>> vectors = annotator.annotate(INPUT).get(annotator.layer());
    Assertions.assertSame(batch[0], vectors.get(0).value());
    Assertions.assertSame(batch[1], vectors.get(1).value());
    Assertions.assertEquals(1, embedder.batchCalls);
    Assertions.assertEquals(1, embedder.dimensionCalls);
  }

  /**
   * The annotator does not impose an additional finite-component policy.
   *
   * @param component The value to preserve.
   */
  @ParameterizedTest
  @ValueSource(floats = {Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY,
      Float.MAX_VALUE, Float.MIN_VALUE, -0.0f})
  void testComponentsAreNotChanged(float component) {
    final float[][] batch = {{component}, {component}};
    final EmbeddingAnnotator annotator =
        new EmbeddingAnnotator(new BatchEmbedder(1, batch), Layers.TOKENS);
    final List<Annotation<float[]>> vectors = annotator.annotate(INPUT).get(annotator.layer());
    for (final Annotation<float[]> vector : vectors) {
      Assertions.assertEquals(Float.floatToRawIntBits(component),
          Float.floatToRawIntBits(vector.value()[0]));
    }
  }

  /** Duplicate output layers fail before inference or dimension lookup. */
  @Test
  void testDuplicateLayerDoesNotInvokeEmbedder() {
    final BatchEmbedder embedder = new BatchEmbedder(2, new float[2][2]);
    final EmbeddingAnnotator annotator = new EmbeddingAnnotator(embedder, Layers.TOKENS);
    final Document annotated = INPUT.with(annotator.layer(), List.of());
    final IllegalArgumentException error = Assertions.assertThrows(IllegalArgumentException.class,
        () -> annotator.annotate(annotated));
    Assertions.assertTrue(error.getMessage().contains("already present"));
    Assertions.assertTrue(error.getMessage().contains(annotator.layer().id()));
    Assertions.assertEquals(0, embedder.batchCalls);
    Assertions.assertEquals(0, embedder.dimensionCalls);
  }

  /** Missing input layers fail without contacting the embedder. */
  @Test
  void testMissingLayerDoesNotInvokeEmbedder() {
    final BatchEmbedder embedder = new BatchEmbedder(2, null);
    final EmbeddingAnnotator annotator = new EmbeddingAnnotator(embedder, Layers.TOKENS);
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> annotator.annotate(Document.of("ab cdef")));
    Assertions.assertEquals(0, embedder.batchCalls);
    Assertions.assertEquals(0, embedder.dimensionCalls);
  }

  /**
   * An empty input layer creates an empty output without an embedding call.
   *
   * @param text The text with an empty source layer.
   */
  @ParameterizedTest
  @ValueSource(strings = {"", "text without annotations"})
  void testEmptyLayerDoesNotInvokeEmbedder(String text) {
    final BatchEmbedder embedder = new BatchEmbedder(2, null);
    final EmbeddingAnnotator annotator = new EmbeddingAnnotator(embedder, Layers.TOKENS);
    final Document input = Document.of(text).with(Layers.TOKENS, List.of());
    final Document output = annotator.annotate(input);
    Assertions.assertEquals(Set.of(Layers.TOKENS, annotator.layer()), output.layers());
    Assertions.assertTrue(output.get(annotator.layer()).isEmpty());
    Assertions.assertEquals(0, embedder.batchCalls);
    Assertions.assertEquals(0, embedder.dimensionCalls);
  }

  /** Constructor errors identify the null argument. */
  @Test
  void testConstructorMessages() {
    Assertions.assertEquals("embedder must not be null",
        Assertions.assertThrows(IllegalArgumentException.class,
            () -> new EmbeddingAnnotator(null, Layers.TOKENS)).getMessage());
    Assertions.assertEquals("source must not be null",
        Assertions.assertThrows(IllegalArgumentException.class,
            () -> new EmbeddingAnnotator(new BatchEmbedder(2, null), null)).getMessage());
  }

  /**
   * Backend failures propagate without wrapping or adding an output layer.
   *
   * @param stage The failing backend operation.
   */
  @ParameterizedTest
  @ValueSource(strings = {"batch", "dimension"})
  void testBackendFailure(String stage) {
    final IllegalStateException failure = new IllegalStateException("backend unavailable");
    final TextEmbedder embedder = new TextEmbedder() {
      /** {@inheritDoc} */
      @Override
      public float[][] embedAll(List<? extends CharSequence> texts) {
        if (stage.equals("batch")) {
          throw failure;
        }
        return new float[2][2];
      }

      /** {@inheritDoc} */
      @Override
      public float[] embed(CharSequence text) {
        return Assertions.fail("annotator must use embedAll");
      }

      /** {@inheritDoc} */
      @Override
      public int dimension() {
        throw failure;
      }
    };
    final EmbeddingAnnotator annotator = new EmbeddingAnnotator(embedder, Layers.TOKENS);
    Assertions.assertSame(failure, Assertions.assertThrows(IllegalStateException.class,
        () -> annotator.annotate(INPUT)));
    Assertions.assertEquals(Set.of(Layers.TOKENS), INPUT.layers());
  }

  /** Supplies a chosen batch and records calls without running a model. */
  private static final class BatchEmbedder implements TextEmbedder {

    private final int dimension;
    private final float[][] batch;
    private int batchCalls;
    private int dimensionCalls;

    /**
     * Creates a backend response, including deliberately invalid responses.
     *
     * @param dimension The declared vector length.
     * @param batch The batch to return.
     */
    private BatchEmbedder(int dimension, float[][] batch) {
      this.dimension = dimension;
      this.batch = batch;
    }

    /** {@inheritDoc} */
    @Override
    public float[][] embedAll(List<? extends CharSequence> texts) {
      batchCalls++;
      return batch;
    }

    /** {@inheritDoc} */
    @Override
    public float[] embed(CharSequence text) {
      return Assertions.fail("annotator must use embedAll");
    }

    /** {@inheritDoc} */
    @Override
    public int dimension() {
      dimensionCalls++;
      return dimension;
    }
  }
}
