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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.postag.BilstmPOSTrainer.MultiTaskSample;
import opennlp.tools.util.CollectionObjectStream;
import opennlp.tools.util.ObjectStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Checks sample ownership and training with reusable input buffers. */
class BilstmSampleOwnershipTest {

  private enum Field {
    TOKENS, UPOS, XPOS, FEATS
  }

  /** {@return constructor and accessor mutations for every sample field} */
  private static Stream<Arguments> mutations() {
    return Stream.of(Field.values()).flatMap(field -> Stream.of(0, 1, 2)
        .flatMap(index -> Stream.of(null, "changed")
            .flatMap(value -> Stream.of(false, true)
                .map(accessor -> Arguments.of(field, index, value, accessor)))));
  }

  /**
   * External arrays cannot change validated sample values.
   *
   * @param field The sample component.
   * @param index The element to change.
   * @param value The replacement.
   * @param accessor Whether to change an accessor result instead of constructor input.
   */
  @ParameterizedTest
  @MethodSource("mutations")
  void testArrayMutation(Field field, int index, String value, boolean accessor) {
    final String[][] input = rows().get(0);
    final String[] expected = input[field.ordinal()].clone();
    final MultiTaskSample sample = sample(input, 3);
    final int hash = sample.hashCode();
    final String[] changed = accessor ? field(sample, field) : input[field.ordinal()];
    changed[index] = value;
    assertArrayEquals(expected, field(sample, field));
    assertEquals(hash, sample.hashCode());
  }

  /**
   * Equivalent samples and record copies compare by content, including absent fields.
   *
   * @param auxiliaries The optional arrays to include.
   */
  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void testValueEquality(int auxiliaries) {
    final MultiTaskSample first = sample(rows().get(0), auxiliaries);
    final MultiTaskSample second = sample(rows().get(0), auxiliaries);
    final MultiTaskSample copy = new MultiTaskSample(first.tokens(), first.upos(),
        first.xpos(), first.feats());
    assertEquals(first, second);
    assertEquals(second, first);
    assertEquals(first, copy);
    assertEquals(second, copy);
    assertEquals(first.hashCode(), second.hashCode());
    assertEquals(first.hashCode(), copy.hashCode());
    assertEquals(first, first);
    assertNotEquals(null, first);
    assertNotEquals("sample", first);
    if ((auxiliaries & 1) == 0) {
      assertNull(first.xpos());
    }
    if ((auxiliaries & 2) == 0) {
      assertNull(first.feats());
    }
    assertNotEquals(first, sample(rows().get(0), auxiliaries ^ 1));
    assertNotEquals(first, sample(rows().get(0), auxiliaries ^ 2));
  }

  /** {@return a differing element in each component} */
  private static Stream<Arguments> differences() {
    return Stream.of(Field.values()).flatMap(field -> Stream.of(0, 1, 2)
        .map(index -> Arguments.of(field, index)));
  }

  /**
   * Equality includes every token and tagging value.
   *
   * @param field The component to change.
   * @param index The differing element.
   */
  @ParameterizedTest
  @MethodSource("differences")
  void testDifferentValues(Field field, int index) {
    final String[][] input = rows().get(0);
    final MultiTaskSample first = sample(input, 3);
    input[field.ordinal()][index] = "changed";
    final MultiTaskSample second = sample(input, 3);
    assertNotEquals(first, second);
    assertNotEquals(second, first);
  }

  /** {@return decoder depths and worker counts with each optional tagging combination} */
  private static Stream<Arguments> trainingModes() {
    return Stream.of(false, true).flatMap(crf -> Stream.of(1, 2)
        .flatMap(layers -> Stream.of(1, 2).flatMap(threads -> Stream.of(0, 1, 2, 3)
            .map(auxiliaries -> Arguments.of(crf, layers, threads, auxiliaries)))));
  }

  /**
   * A stream can reuse and clear its buffers without changing the accumulated corpus.
   *
   * @param crf Whether to train CRF weights.
   * @param layers The encoder depth.
   * @param threads The worker count.
   * @param auxiliaries The optional arrays to include.
   * @throws IOException If training or model serialization fails.
   */
  @ParameterizedTest
  @MethodSource("trainingModes")
  void testReusableStream(boolean crf, int layers, int threads, int auxiliaries)
      throws IOException {
    final BilstmPOSTrainer.Settings settings = settings(crf, layers, threads);
    final List<MultiTaskSample> corpus = rows().stream()
        .map(row -> sample(row, auxiliaries)).toList();
    final BilstmPOSModel expected = BilstmPOSTrainer.trainMultiTask(
        new CollectionObjectStream<>(corpus), settings, this::vector, null);
    final BilstmPOSModel actual = BilstmPOSTrainer.trainMultiTask(
        reusingStream(auxiliaries), settings, this::vector, null);
    assertArrayEquals(bytes(expected), bytes(actual));
    assertArrayEquals(new BilstmPOSTagger(expected).tag(new String[] {"a", "cat", "runs"}),
        new BilstmPOSTagger(actual).tag(new String[] {"a", "cat", "runs"}));
  }

  /**
   * A callback cannot edit the corpus through sample accessors during training.
   *
   * @param crf Whether to train CRF weights.
   * @param layers The encoder depth.
   * @param threads The worker count.
   * @param auxiliaries The optional arrays to include.
   * @throws IOException If training or model serialization fails.
   */
  @ParameterizedTest
  @MethodSource("trainingModes")
  void testAccessorMutationDuringTraining(boolean crf, int layers, int threads,
      int auxiliaries) throws IOException {
    final BilstmPOSTrainer.Settings settings = settings(crf, layers, threads);
    final List<MultiTaskSample> corpus = rows().stream()
        .map(row -> sample(row, auxiliaries)).toList();
    final BilstmPOSModel expected = BilstmPOSTrainer.trainMultiTask(
        new CollectionObjectStream<>(corpus), settings, this::vector, null);
    final Function<CharSequence, float[]> vectors = word -> {
      for (MultiTaskSample sample : corpus) {
        for (Field field : Field.values()) {
          final String[] exposed = field(sample, field);
          if (exposed != null) {
            Arrays.fill(exposed, null);
          }
        }
      }
      return vector(word);
    };
    final BilstmPOSModel actual = BilstmPOSTrainer.trainMultiTask(
        new CollectionObjectStream<>(corpus), settings, vectors, null);
    assertArrayEquals(bytes(expected), bytes(actual));
  }

  /** {@return independent token, UPOS, XPOS and feature arrays for a small corpus} */
  private List<String[][]> rows() {
    return List.of(
        new String[][] {{"the", "cat", "runs"}, {"D", "N", "V"},
            {"DT", "NN", "VBZ"}, {"_", "Number=Sing", "Tense=Pres"}},
        new String[][] {{"a", "dog", "sits"}, {"D", "N", "V"},
            {"DT", "NN", "VBZ"}, {"_", "Number=Sing", "Tense=Pres"}},
        new String[][] {{"the", "dogs", "run"}, {"D", "N", "V"},
            {"DT", "NNS", "VBP"}, {"_", "Number=Plur", "Tense=Pres"}});
  }

  /**
   * Creates a sample with selected optional taggings.
   *
   * @param row The four component arrays.
   * @param auxiliaries Bit zero enables XPOS; bit one enables morphological features.
   * @return The sample.
   */
  private MultiTaskSample sample(String[][] row, int auxiliaries) {
    return new MultiTaskSample(row[0], row[1], (auxiliaries & 1) != 0 ? row[2] : null,
        (auxiliaries & 2) != 0 ? row[3] : null);
  }

  /**
   * Reads a sample component through its public accessor.
   *
   * @param sample The sample.
   * @param field The component.
   * @return The component array, or null for an absent auxiliary tagging.
   */
  private String[] field(MultiTaskSample sample, Field field) {
    return switch (field) {
      case TOKENS -> sample.tokens();
      case UPOS -> sample.upos();
      case XPOS -> sample.xpos();
      case FEATS -> sample.feats();
    };
  }

  /**
   * Uses the same arrays for each read and clears them at end of stream.
   *
   * @param auxiliaries The optional arrays to include.
   * @return The reusable-buffer stream.
   */
  private ObjectStream<MultiTaskSample> reusingStream(int auxiliaries) {
    final List<String[][]> rows = rows();
    return new ObjectStream<>() {
      private final String[][] buffer = new String[4][3];
      private int next;

      /** {@inheritDoc} */
      @Override
      public MultiTaskSample read() {
        if (next == rows.size()) {
          for (String[] field : buffer) {
            Arrays.fill(field, null);
          }
          return null;
        }
        final String[][] row = rows.get(next++);
        for (int i = 0; i < buffer.length; i++) {
          System.arraycopy(row[i], 0, buffer[i], 0, buffer[i].length);
        }
        return sample(buffer, auxiliaries);
      }
    };
  }

  /**
   * Provides small deterministic pretrained vectors.
   *
   * @param word The lookup word.
   * @return The vector.
   */
  private float[] vector(CharSequence word) {
    return new float[] {word.length() * 0.1f, 0.25f, -0.125f};
  }

  /**
   * Creates a short training run with dropout and optional auxiliary losses.
   *
   * @param crf Whether to train CRF weights.
   * @param layers The encoder depth.
   * @param threads The worker count.
   * @return The training settings.
   */
  private BilstmPOSTrainer.Settings settings(boolean crf, int layers, int threads) {
    return new BilstmPOSTrainer.Settings(4, 2, 2, 4, 2, 2, 0.01, 5.0, 0.15,
        1, 12, 17L, threads, 0.1, 0, crf, layers, 0.1, 0.1, 1.0, 0.0, false);
  }

  /**
   * Serializes parameters for an exact training-result comparison.
   *
   * @param model The trained model.
   * @return Its bytes.
   * @throws IOException If serialization fails.
   */
  private byte[] bytes(BilstmPOSModel model) throws IOException {
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    model.serialize(output);
    return output.toByteArray();
  }
}
