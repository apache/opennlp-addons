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

package opennlp.tools.postag;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks score precision, cache agreement and concurrent cache allocation. */
class FeedforwardPOSScoringTest {

  private static final int CACHE_ENTRY_LIMIT = 32768;
  private static final String[] SENTENCE = {"cat"};
  private static final Map<String, Integer> VOCABULARY = Map.of(
      FeedforwardPOSModel.UNKNOWN, 0, FeedforwardPOSModel.ABSENT, 0, "cat", 0);

  /**
   * A hidden-layer calculation with an independently known result.
   *
   * @param name The arithmetic condition.
   * @param embedding The input values.
   * @param weights The hidden-layer weights.
   * @param bias The hidden bias.
   * @param outputScale The output weight magnitude.
   * @param hidden The expected hidden value before the cube.
   */
  private record Calculation(String name, float[] embedding, float[] weights,
      float bias, float outputScale, double hidden) {
  }

  /** {@return powers of two and cancellation cases with known hidden values} */
  private static Stream<Arguments> calculations() {
    final float[] repeated = new float[256];
    final float[] weights = new float[256];
    Arrays.fill(repeated, Math.scalb(1f, 80));
    Arrays.fill(weights, Math.scalb(1f, 40));
    return Stream.of(
        new Calculation("product overflow", new float[] {Math.scalb(1f, 80)},
            new float[] {Math.scalb(1f, 80)}, 0, Math.scalb(1f, -120), Math.scalb(1d, 160)),
        new Calculation("product underflow", new float[] {Math.scalb(1f, -80)},
            new float[] {Math.scalb(1f, -80)}, 0, Math.scalb(1f, 120), Math.scalb(1d, -160)),
        new Calculation("product cancellation", new float[] {Math.nextUp(1f), 1f},
            new float[] {Math.nextUp(1f), -(1f + Math.scalb(1f, -22))}, 0,
            Math.scalb(1f, 120), Math.scalb(1d, -46)),
        new Calculation("cache range", repeated, weights, 0,
            Math.scalb(1f, -120), Math.scalb(1d, 128)),
        new Calculation("cache cancellation", new float[] {1f, Math.scalb(1f, -24)},
            new float[] {1f, 1f}, -1f, Math.scalb(1f, 80), Math.scalb(1d, -24)))
        .flatMap(calculation -> Stream.of(false, true).flatMap(pretrained ->
            Stream.of(false, true).map(negative -> Arguments.of(calculation.name(),
                calculation, pretrained, negative))));
  }

  /**
   * Finite parameters produce the calculated score before and after cache population.
   *
   * @param name The arithmetic condition.
   * @param calculation The input values and expected hidden sum.
   * @param pretrained Whether the calculation uses pretrained inputs.
   * @param negative Whether to negate the hidden sum.
   * @throws IOException If serialization or loading fails.
   */
  @ParameterizedTest(name = "{0}, pretrained={2}, negative={3}")
  @MethodSource("calculations")
  void testKnownScores(String name, Calculation calculation, boolean pretrained, boolean negative)
      throws IOException {
    final FeedforwardPOSModel model = model(calculation, pretrained, negative);
    final double hidden = negative ? -calculation.hidden() : calculation.hidden();
    final double score = hidden * hidden * hidden * calculation.outputScale();
    final double[] expected = {-score, score};
    final int[] features = model.featureIds(FeedforwardPOSContext.extract(SENTENCE, 0, null, null));
    final int[] vectors = model.pretrainedRows(SENTENCE, 0);
    assertArrayEquals(expected, model.score(features, vectors), name + " direct");
    final byte[] serialized = serialize(model);
    model.enableScoringCache();
    assertArrayEquals(expected, model.score(features, vectors), name + " first cache access");
    assertArrayEquals(expected, model.score(features, vectors), name + " repeated cache access");
    assertArrayEquals(serialized, serialize(model));

    final FeedforwardPOSTagger tagger = new FeedforwardPOSTagger(
        FeedforwardPOSModel.load(new ByteArrayInputStream(serialized)));
    final String expectedTag = negative ? "A" : "B";
    assertArrayEquals(new String[] {expectedTag}, tagger.tag(SENTENCE));
    assertArrayEquals(new String[] {expectedTag}, tagger.tag(SENTENCE, new Object[0]));
    final double probability = 1.0 / (1.0 + StrictMath.exp(-2 * Math.abs(score)));
    for (var sequence : List.of(tagger.topKSequences(SENTENCE)[0],
        tagger.topKSequences(SENTENCE, new Object[0])[0])) {
      assertEquals(List.of(expectedTag), sequence.getOutcomes());
      assertArrayEquals(new double[] {probability}, sequence.getProbs());
    }
  }

  /**
   * Builds a model with one active hidden input block and opposite output weights.
   *
   * @param calculation The input arrays.
   * @param pretrained Whether to use the pretrained center-word block.
   * @param negative Whether to negate hidden weights and bias.
   * @return The model.
   */
  private FeedforwardPOSModel model(Calculation calculation, boolean pretrained, boolean negative) {
    final int width = calculation.embedding().length;
    final int embeddingSize = pretrained ? 1 : width;
    final int pretrainedSize = pretrained ? width : 0;
    final float[][] hiddenWeights = new float[1][12 * embeddingSize + 5 * pretrainedSize];
    final int offset = pretrained ? 12 * embeddingSize + 2 * pretrainedSize : 0;
    for (int i = 0; i < width; i++) {
      hiddenWeights[0][offset + i] = negative ? -calculation.weights()[i] : calculation.weights()[i];
    }
    return new FeedforwardPOSModel(VOCABULARY, VOCABULARY, VOCABULARY, VOCABULARY,
        new String[] {"A", "B"}, embeddingSize,
        pretrained ? new float[1][1] : new float[][] {calculation.embedding()}, hiddenWeights,
        new float[] {negative ? -calculation.bias() : calculation.bias()},
        new float[][] {{-calculation.outputScale()}, {calculation.outputScale()}}, new float[2],
        pretrainedSize, pretrained ? Map.of("cat", 0) : Map.of(),
        pretrained ? new float[][] {calculation.embedding()} : new float[0][]);
  }

  /** {@return invalid scores injected at either output, through all tagging methods} */
  private static Stream<Arguments> invalidScores() {
    return Stream.of(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
        .flatMap(value -> Stream.of(0, 1).flatMap(index ->
            IntStream.range(0, 4).mapToObj(method -> Arguments.of(value, index, method))));
  }

  /**
   * A model failure is reported consistently by each tagging entry point.
   *
   * @param value The invalid output bias used to inject the failure.
   * @param index The affected tag.
   * @param method The public entry point.
   */
  @ParameterizedTest
  @MethodSource("invalidScores")
  void testInvalidScore(float value, int index, int method) {
    final FeedforwardPOSModel model = model(new Calculation("zero", new float[1],
        new float[1], 0, 1, 0), false, false);
    model.outputBias()[index] = value;
    final FeedforwardPOSTagger tagger = new FeedforwardPOSTagger(model);
    final IllegalStateException error = assertThrows(IllegalStateException.class, () -> {
      switch (method) {
        case 0 -> tagger.tag(SENTENCE);
        case 1 -> tagger.tag(SENTENCE, new Object[0]);
        case 2 -> tagger.topKSequences(SENTENCE);
        case 3 -> tagger.topKSequences(SENTENCE, new Object[0]);
        default -> throw new AssertionError(method);
      }
    });
    assertTrue(error.getMessage().contains("non-finite"), error.getMessage());
  }

  /**
   * Zero scores are a valid tie and retain the first tag.
   *
   * @param pretrained Whether the model uses pretrained vectors.
   */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testZeroScores(boolean pretrained) {
    final FeedforwardPOSModel model = model(new Calculation("zero", new float[1],
        new float[1], 0, 1, 0), pretrained, false);
    final FeedforwardPOSTagger tagger = new FeedforwardPOSTagger(model);
    assertArrayEquals(new String[] {"A"}, tagger.tag(SENTENCE));
    assertEquals(List.of("A"), tagger.topKSequences(SENTENCE)[0].getOutcomes());
    assertArrayEquals(new double[] {0.5}, tagger.topKSequences(SENTENCE)[0].getProbs());
  }

  /** {@return independent contention runs with shared or distinct cache keys} */
  private static Stream<Arguments> contentionCases() {
    return IntStream.range(0, 8).boxed().flatMap(round ->
        Stream.of(false, true).map(shared -> Arguments.of(round, shared)));
  }

  /**
   * Constructing taggers concurrently initializes one cache on their shared model.
   *
   * @param round An independent initialization run.
   * @throws Exception If a worker or cache inspection fails.
   */
  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void testConcurrentInitialization(int round) throws Exception {
    final FeedforwardPOSModel model = new FeedforwardPOSModel(VOCABULARY, VOCABULARY,
        VOCABULARY, VOCABULARY, new String[] {"A"}, 1, new float[100000][1],
        new float[1][12], new float[1], new float[1][1], new float[1]);
    final CountDownLatch ready = new CountDownLatch(8);
    final CountDownLatch start = new CountDownLatch(1);
    final Callable<Object> initialize = () -> {
      ready.countDown();
      if (!start.await(10, TimeUnit.SECONDS)) {
        throw new AssertionError("workers did not start");
      }
      new FeedforwardPOSTagger(model);
      return field(model, "cache");
    };
    final Set<Object> caches = Collections.newSetFromMap(new IdentityHashMap<>());
    try (var workers = Executors.newFixedThreadPool(8)) {
      final var futures = IntStream.range(0, 8).mapToObj(index -> workers.submit(initialize)).toList();
      try {
        assertTrue(ready.await(10, TimeUnit.SECONDS), "workers were not ready");
      } finally {
        start.countDown();
      }
      for (var future : futures) {
        caches.add(future.get(10, TimeUnit.SECONDS));
      }
    }
    assertEquals(1, caches.size(), "cache identities in round " + round);
  }

  /**
   * Concurrent misses cannot allocate more contribution entries than the remaining budget.
   *
   * @param round An independent contention run.
   * @param shared Whether all requests use the same embedding row.
   * @throws Exception If a worker or reflection access fails.
   */
  @ParameterizedTest
  @MethodSource("contentionCases")
  void testConcurrentBudget(int round, boolean shared) throws Exception {
    final float[][] embeddings = new float[8][128];
    final float[][] hiddenWeights = new float[256][12 * 128];
    for (float[] row : embeddings) {
      Arrays.fill(row, 0.125f);
    }
    for (float[] row : hiddenWeights) {
      Arrays.fill(row, 0.125f);
    }
    final FeedforwardPOSModel model = new FeedforwardPOSModel(VOCABULARY, VOCABULARY,
        VOCABULARY, VOCABULARY, new String[] {"A"}, 128, embeddings, hiddenWeights,
        new float[256], new float[1][256], new float[1]);
    model.enableScoringCache();
    final Object cache = field(model, "cache");
    final AtomicInteger remaining = (AtomicInteger) field(cache, "remaining");
    final int budget = shared ? 8 : 1;
    remaining.set(budget);
    final CountDownLatch ready = new CountDownLatch(8);
    final CountDownLatch start = new CountDownLatch(1);
    final List<Callable<double[]>> requests = IntStream.range(0, 8).mapToObj(index ->
        (Callable<double[]>) () -> {
          final int[] features = new int[12];
          Arrays.fill(features, shared ? 0 : index);
          ready.countDown();
          if (!start.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("workers did not start");
          }
          return model.score(features);
        }).toList();
    try (var workers = Executors.newFixedThreadPool(8)) {
      final var futures = requests.stream().map(workers::submit).toList();
      try {
        assertTrue(ready.await(10, TimeUnit.SECONDS), "workers were not ready");
      } finally {
        start.countDown();
      }
      for (var future : futures) {
        assertArrayEquals(new double[] {0}, future.get(10, TimeUnit.SECONDS));
      }
    }
    final int entries = cachedEntries(cache);
    assertTrue(entries <= budget,
        "round " + round + " stored " + entries + " entries with budget " + budget);
    assertEquals(budget - entries, remaining.get());
    model.score(new int[12]);
    assertEquals(budget, cachedEntries(cache));
    assertEquals(0, remaining.get());
  }

  /**
   * Cache hits and misses agree after the cache reaches its entry limit.
   *
   * @param budget The cache capacity for this test.
   * @throws ReflectiveOperationException If cache inspection fails.
   */
  @ParameterizedTest
  @ValueSource(ints = {0, 1, 3, CACHE_ENTRY_LIMIT})
  void testFullCache(int budget) throws ReflectiveOperationException {
    final int rows = (budget + 11) / 12 + 2;
    final float[][] embeddings = new float[rows][2];
    for (int row = 0; row < rows; row++) {
      embeddings[row][0] = (row + 1) * 0.125f;
      embeddings[row][1] = -(row + 1) * 0.0625f;
    }
    final float[][] weights = new float[1][24];
    for (int slot = 0; slot < 12; slot++) {
      weights[0][slot * 2] = 0.5f;
      weights[0][slot * 2 + 1] = 0.25f;
    }
    final FeedforwardPOSModel model = new FeedforwardPOSModel(VOCABULARY, VOCABULARY,
        VOCABULARY, VOCABULARY, new String[] {"A"}, 2, embeddings, weights,
        new float[1], new float[][] {{1f}}, new float[1]);
    model.enableScoringCache();
    final Object cache = field(model, "cache");
    final AtomicInteger remaining = (AtomicInteger) field(cache, "remaining");
    assertEquals(CACHE_ENTRY_LIMIT, remaining.get());
    remaining.set(budget);
    for (int row = 0; row < rows; row++) {
      final int[] features = new int[12];
      Arrays.fill(features, row);
      final double hidden = 9 * (row + 1) / 16.0;
      final double[] expected = {hidden * hidden * hidden};
      assertArrayEquals(expected, model.score(features));
      assertArrayEquals(expected, model.score(features));
    }
    assertEquals(budget, cachedEntries(cache));
    assertEquals(0, remaining.get());
  }

  /**
   * Counts published contribution arrays.
   *
   * @param cache The model cache.
   * @return The number of stored entries.
   * @throws ReflectiveOperationException If cache inspection fails.
   */
  private int cachedEntries(Object cache) throws ReflectiveOperationException {
    int entries = 0;
    for (AtomicReferenceArray<?> slot : (AtomicReferenceArray<?>[]) field(cache, "bySlot")) {
      for (int row = 0; row < slot.length(); row++) {
        if (slot.get(row) != null) {
          entries++;
        }
      }
    }
    return entries;
  }

  /**
   * Reads private cache state without adding production inspection methods.
   *
   * @param owner The field owner.
   * @param name The field name.
   * @return The field value.
   * @throws ReflectiveOperationException If access fails.
   */
  private Object field(Object owner, String name) throws ReflectiveOperationException {
    final Field field = owner.getClass().getDeclaredField(name);
    return MethodHandles.privateLookupIn(owner.getClass(), MethodHandles.lookup())
        .findVarHandle(owner.getClass(), name, field.getType()).get(owner);
  }

  /**
   * Serializes a model to compare its data before and after scoring.
   *
   * @param model The model.
   * @return The binary model.
   * @throws IOException If serialization fails.
   */
  private byte[] serialize(FeedforwardPOSModel model) throws IOException {
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    model.serialize(output);
    return output.toByteArray();
  }
}
