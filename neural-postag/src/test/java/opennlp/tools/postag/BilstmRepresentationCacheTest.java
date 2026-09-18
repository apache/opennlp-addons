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
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.util.CollectionObjectStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks model-scoped cache retention, entry limits and result ownership. */
class BilstmRepresentationCacheTest {

  private static final int WORKERS = 8;
  private static final String CACHE_FIELD = "representationCache";
  private static final String NEW_TOKEN_PREFIX = "seed-new-";
  private static final String[] TOKENS = {"The", "cat", "runs", "CAT", "", "unseen", "\uD801\uDC00"};

  /** {@return decoder, encoder and pretrained-vector combinations} */
  private static Stream<Arguments> models() {
    return Stream.of(false, true).flatMap(crf -> IntStream.rangeClosed(1, 2).boxed()
        .flatMap(layers -> IntStream.range(0, 3)
            .mapToObj(vectors -> Arguments.of(crf, layers, vectors))));
  }

  /**
   * Additional taggers and repeated enabling retain a populated cache.
   *
   * @param crf Whether the model uses a CRF.
   * @param layers The encoder layer count.
   * @param vectors Zero without vectors, one with vectors, two with an adapter.
   * @throws Exception If training or cache inspection fails.
   */
  @ParameterizedTest
  @MethodSource("models")
  void testRepeatedEnabling(boolean crf, int layers, int vectors) throws Exception {
    final BilstmPOSModel model = model(crf, layers, vectors);
    final BilstmPOSTagger first = new BilstmPOSTagger(model);
    final String[] expected = first.tag(TOKENS);
    final Map<String, double[]> cache = cache(model);
    assertEquals(TOKENS.length, cache.size());
    final BilstmPOSTagger second = new BilstmPOSTagger(model);
    assertSame(cache, cache(model));
    model.enableRepresentationCache();
    assertSame(cache, cache(model));
    assertEquals(TOKENS.length, cache.size());
    assertArrayEquals(expected, second.tag(TOKENS));
  }

  /**
   * Caller mutation cannot change cached or uncached representations and scores.
   *
   * @param crf Whether the model uses a CRF.
   * @param layers The encoder layer count.
   * @param vectors The pretrained-vector mode.
   * @throws Exception If training or cache inspection fails.
   */
  @ParameterizedTest
  @MethodSource("models")
  void testResultOwnership(boolean crf, int layers, int vectors) throws Exception {
    final BilstmPOSModel model = model(crf, layers, vectors);
    final double[][] expected = model.score(TOKENS);
    final double[][] representations = Arrays.stream(TOKENS)
        .map(model::wordRepresentation).toArray(double[][]::new);
    assertNull(cache(model));
    model.enableRepresentationCache();
    for (int i = 0; i < TOKENS.length; i++) {
      final double[] first = model.wordRepresentation(TOKENS[i]);
      assertArrayEquals(representations[i], first);
      Arrays.fill(first, Double.NaN);
      final double[] second = model.wordRepresentation(TOKENS[i]);
      assertNotSame(first, second);
      assertArrayEquals(representations[i], second);
      Arrays.fill(second, Double.POSITIVE_INFINITY);
    }
    final double[][] scores = model.score(TOKENS);
    assertScores(expected, scores);
    Arrays.fill(scores[0], Double.NaN);
    assertScores(expected, model.score(TOKENS));
  }

  /**
   * Caches are excluded from model files and are not shared by loaded models.
   *
   * @param crf Whether the model uses a CRF.
   * @param layers The encoder layer count.
   * @param vectors The pretrained-vector mode.
   * @throws Exception If model I/O or cache inspection fails.
   */
  @ParameterizedTest
  @MethodSource("models")
  void testModelScope(boolean crf, int layers, int vectors) throws Exception {
    final BilstmPOSModel model = model(crf, layers, vectors);
    final byte[] before = serialize(model);
    model.enableRepresentationCache();
    final double[][] expected = model.score(TOKENS);
    assertArrayEquals(before, serialize(model));
    final BilstmPOSModel loaded = BilstmPOSModel.load(new ByteArrayInputStream(before));
    assertNull(cache(loaded));
    loaded.enableRepresentationCache();
    assertNotSame(cache(model), cache(loaded));
    assertTrue(cache(loaded).isEmpty());
    assertScores(expected, loaded.score(TOKENS));
    loaded.wordRepresentation("loaded-only");
    assertNull(cache(model).get("loaded-only"));
  }

  /**
   * Concurrent tagger construction retains a shared model's existing entries.
   *
   * @param round An independent contention run.
   * @throws Exception If a worker or cache inspection fails.
   */
  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void testConcurrentEnabling(int round) throws Exception {
    final BilstmPOSModel model = model(round % 2 == 0, 1, 0);
    model.enableRepresentationCache();
    final double[][] expected = model.score(TOKENS);
    final Map<String, double[]> cache = cache(model);
    concurrently(index -> {
      new BilstmPOSTagger(model).tag(TOKENS);
      assertSame(cache, cache(model));
      assertScores(expected, model.score(TOKENS));
      return null;
    });
    assertSame(cache, cache(model));
  }

  /** {@return budgets near the entry limit and shared or distinct requests} */
  private static Stream<Arguments> budgets() {
    return Stream.of(1, 3).flatMap(budget -> Stream.of(false, true)
        .map(shared -> Arguments.of(budget, shared)));
  }

  /**
   * A coordinated size check cannot admit more entries than the limit permits.
   * Duplicate insertions must not consume capacity needed by other tokens.
   *
   * @param budget The number of entries still available.
   * @param shared Whether all workers request the same new token.
   * @throws Exception If training, workers or cache inspection fail.
   */
  @ParameterizedTest
  @MethodSource("budgets")
  void testConcurrentLimit(int budget, boolean shared) throws Exception {
    final BilstmPOSModel model = model(false, 1, 0);
    final int limit = limit();
    populate(model, limit - budget);
    final AdmissionMap entries = new AdmissionMap(cache(model), WORKERS, false);
    field(CACHE_FIELD).setVolatile(model, entries);
    final double[] expected = model.wordRepresentation("seed-0");
    concurrently(index -> {
      assertArrayEquals(expected, model.wordRepresentation(NEW_TOKEN_PREFIX + (shared ? 0 : index)));
      return null;
    });
    entries.coordinate = false;
    assertEquals(limit - budget + (shared ? 1 : budget), entries.entries.size());
    for (int i = 0; i < WORKERS; i++) {
      assertArrayEquals(expected, model.wordRepresentation("seed-later-" + i));
    }
    assertEquals(limit, entries.entries.size());
    assertArrayEquals(expected, model.wordRepresentation("seed-overflow"));
    assertNull(entries.entries.get("seed-overflow"));
  }

  /**
   * An unsuccessful map insertion returns its reserved capacity.
   *
   * @param budget The number of entries still available.
   * @throws Exception If training or cache inspection fails.
   */
  @ParameterizedTest
  @ValueSource(ints = {1, 3})
  void testInsertionFailure(int budget) throws Exception {
    final BilstmPOSModel model = model(false, 1, 0);
    final int limit = limit();
    populate(model, limit - budget);
    final AdmissionMap entries = new AdmissionMap(cache(model), 0, true);
    field(CACHE_FIELD).setVolatile(model, entries);
    assertThrows(IllegalStateException.class, () -> model.wordRepresentation("seed-failed"));
    for (int i = 0; i < budget; i++) {
      model.wordRepresentation("seed-retry-" + i);
    }
    assertEquals(limit, entries.entries.size());
  }

  /**
   * Trains a small model with one-character token encodings for capacity tests.
   *
   * @param crf Whether the model uses a CRF.
   * @param layers The encoder layer count.
   * @param vectors The pretrained-vector mode.
   * @return The trained model, with caching disabled.
   * @throws IOException If reading samples fails.
   */
  private BilstmPOSModel model(boolean crf, int layers, int vectors) throws IOException {
    final var samples = new CollectionObjectStream<>(List.of(
        new POSSample(new String[] {"The", "cat", "runs"}, new String[] {"D", "N", "V"})));
    final var settings = new BilstmPOSTrainer.Settings(1, 1, 1, 1, 1, 1, 0.01, 5.0,
        0.0, 1, 1, 17, 1, 0.0, 0, crf, layers, 0.0, 0.0, 1.0, 0.0, vectors == 2);
    return vectors == 0 ? BilstmPOSTrainer.train(samples, settings)
        : BilstmPOSTrainer.train(samples, settings, word -> new float[] {word.length(), 0.25f});
  }

  /**
   * Fills the cache through its normal insertion path.
   *
   * @param model The model to populate.
   * @param entries The number of distinct tokens to cache.
   */
  private void populate(BilstmPOSModel model, int entries) {
    model.enableRepresentationCache();
    for (int i = 0; i < entries; i++) {
      model.wordRepresentation("seed-" + i);
    }
  }

  /**
   * Executes concurrent requests from a common start signal with bounded waits.
   *
   * @param request The worker-indexed action.
   * @throws Exception If a worker fails or times out.
   */
  private void concurrently(CheckedRequest request) throws Exception {
    final CountDownLatch ready = new CountDownLatch(WORKERS);
    final CountDownLatch start = new CountDownLatch(1);
    try (var workers = Executors.newFixedThreadPool(WORKERS)) {
      final var futures = IntStream.range(0, WORKERS).mapToObj(index -> workers.submit(
          (Callable<Object>) () -> {
            ready.countDown();
            assertTrue(start.await(10, TimeUnit.SECONDS));
            return request.run(index);
          })).toList();
      try {
        assertTrue(ready.await(10, TimeUnit.SECONDS));
      } finally {
        start.countDown();
      }
      for (var future : futures) {
        future.get(10, TimeUnit.SECONDS);
      }
    }
  }

  /** A worker-indexed request that can report checked test failures. */
  @FunctionalInterface
  private interface CheckedRequest {

    /**
     * Runs the request for one worker.
     *
     * @param index The worker index.
     * @return The request result.
     * @throws Exception If the request fails.
     */
    Object run(int index) throws Exception;
  }

  /**
   * Compares each token's scores exactly.
   *
   * @param expected The direct scores.
   * @param actual The scores under test.
   */
  private void assertScores(double[][] expected, double[][] actual) {
    assertEquals(expected.length, actual.length);
    for (int i = 0; i < expected.length; i++) {
      assertArrayEquals(expected[i], actual[i]);
    }
  }

  /**
   * Serializes a model for cache-independent format checks.
   *
   * @param model The model to serialize.
   * @return The model bytes.
   * @throws IOException If serialization fails.
   */
  private byte[] serialize(BilstmPOSModel model) throws IOException {
    final var output = new ByteArrayOutputStream();
    model.serialize(output);
    return output.toByteArray();
  }

  /**
   * Reads the cache without adding a production inspection method.
   *
   * @param model The model to inspect.
   * @return Its cache, or null when disabled.
   * @throws ReflectiveOperationException If inspection fails.
   */
  @SuppressWarnings("unchecked")
  private Map<String, double[]> cache(BilstmPOSModel model) throws ReflectiveOperationException {
    return (Map<String, double[]>) field(CACHE_FIELD).getVolatile(model);
  }

  /**
   * Reads the model's declared entry limit.
   *
   * @return The maximum entry count.
   * @throws ReflectiveOperationException If inspection fails.
   */
  private int limit() throws ReflectiveOperationException {
    return (int) field("REPRESENTATION_CACHE_LIMIT").get();
  }

  /**
   * Accesses a model field for cache tests.
   *
   * @param name The field name.
   * @return The accessible field.
   * @throws ReflectiveOperationException If the field does not exist.
   */
  private VarHandle field(String name) throws ReflectiveOperationException {
    final Field field = BilstmPOSModel.class.getDeclaredField(name);
    final var lookup = MethodHandles.privateLookupIn(BilstmPOSModel.class, MethodHandles.lookup());
    return Modifier.isStatic(field.getModifiers())
        ? lookup.findStaticVarHandle(BilstmPOSModel.class, name, field.getType())
        : lookup.findVarHandle(BilstmPOSModel.class, name, field.getType());
  }

  /** Coordinates size observations or injects one insertion failure. */
  private static final class AdmissionMap extends AbstractMap<String, double[]> {

    private final Map<String, double[]> entries = new ConcurrentHashMap<>();
    private final CyclicBarrier observations;
    private final AtomicBoolean fail;
    private volatile boolean coordinate;

    /**
     * Copies entries and configures one of the admission checks.
     *
     * @param source The populated cache.
     * @param workers The observation barrier size, or zero without a barrier.
     * @param fail Whether to fail the first insertion.
     */
    private AdmissionMap(Map<String, double[]> source, int workers, boolean fail) {
      entries.putAll(source);
      observations = workers > 0 ? new CyclicBarrier(workers) : null;
      coordinate = workers > 0;
      this.fail = new AtomicBoolean(fail);
    }

    /** {@inheritDoc} */
    @Override
    public double[] get(Object key) {
      final double[] value = entries.get(key);
      if (coordinate && key instanceof String token && token.startsWith(NEW_TOKEN_PREFIX)) {
        awaitObservation();
      }
      return value;
    }

    /** {@inheritDoc} */
    @Override
    public Set<Entry<String, double[]>> entrySet() {
      return entries.entrySet();
    }

    /** {@inheritDoc} */
    @Override
    public int size() {
      final int size = entries.size();
      if (coordinate) {
        awaitObservation();
      }
      return size;
    }

    /**
     * Waits for every worker to observe a lookup or size before any proceeds.
     *
     * @throws AssertionError If a worker is interrupted or the barrier times out.
     */
    private void awaitObservation() {
      try {
        observations.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("cache observations interrupted", e);
      } catch (Exception e) {
        throw new AssertionError("cache observations did not complete", e);
      }
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException If the configured insertion failure is pending.
     */
    @Override
    public double[] putIfAbsent(String key, double[] value) {
      if (fail.compareAndSet(true, false)) {
        throw new IllegalStateException("injected insertion failure");
      }
      return entries.putIfAbsent(key, value);
    }
  }
}
