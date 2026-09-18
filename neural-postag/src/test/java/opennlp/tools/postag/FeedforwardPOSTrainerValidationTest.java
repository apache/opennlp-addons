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
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import opennlp.tools.util.ObjectStreamUtils;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks numerical inputs and failure reporting during feedforward POS training. */
class FeedforwardPOSTrainerValidationTest {

  private static final String CAT = "cat";
  private static final String DOG = "dog";
  private static final String OWL = "owl";
  private static final List<POSSample> SAMPLES = List.of(
      new POSSample(new String[] {"Cat", "DOG"}, new String[] {"NN", "VB"}));

  /** {@return non-finite values for each floating-point setting} */
  private static Stream<Arguments> nonFiniteSettings() {
    return Stream.of("learningRate", "l2", "dropout").flatMap(field ->
        Stream.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
            .map(value -> Arguments.of(field, value)));
  }

  /**
   * Floating-point settings must reject NaN and infinities.
   *
   * @param field The setting name.
   * @param value The invalid value.
   */
  @ParameterizedTest
  @MethodSource("nonFiniteSettings")
  void testNonFiniteSetting(String field, double value) {
    final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> new FeedforwardPOSTrainer.Settings(2, 4, 1, 2,
            field.equals("learningRate") ? value : 0.05,
            field.equals("l2") ? value : 0,
            field.equals("dropout") ? value : 0, 1, 1, 17));
    assertTrue(error.getMessage().contains(field));
  }

  /** {@return non-finite vector components in training and additional words} */
  private static Stream<Arguments> nonFiniteVectors() {
    return Stream.of(CAT, DOG, OWL).flatMap(word ->
        Stream.of(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
            .flatMap(value -> Stream.of(0, 1, 2)
                .map(index -> Arguments.of(word, value, index))));
  }

  /**
   * Both vector overloads reject invalid components before optimization.
   *
   * @param word The word with an invalid vector.
   * @param value The invalid component.
   * @param index The component index.
   */
  @ParameterizedTest
  @MethodSource("nonFiniteVectors")
  void testNonFiniteVector(String word, float value, int index) {
    final float[] invalid = {0.1f, 0.2f, 0.3f};
    invalid[index] = value;
    final Function<CharSequence, float[]> vectors = token -> token.toString().equals(word)
        ? invalid : new float[] {0.1f, 0.2f, 0.3f};
    final IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> {
      if (word.equals(CAT)) {
        FeedforwardPOSTrainer.train(ObjectStreamUtils.createObjectStream(SAMPLES),
            settings(0.05, 0), vectors);
      } else {
        FeedforwardPOSTrainer.train(ObjectStreamUtils.createObjectStream(SAMPLES),
            settings(0.05, 0), vectors, List.of("OWL"));
      }
    });
    assertTrue(error.getMessage().contains(word), error.getMessage());
    assertTrue(error.getMessage().contains("index " + index), error.getMessage());
    assertEquals(Float.floatToIntBits(value), Float.floatToIntBits(invalid[index]));
  }

  /** {@return finite settings that overflow the optimizer's arithmetic} */
  private static Stream<Arguments> divergentSettings() {
    return Stream.of(
        Arguments.of(Double.MAX_VALUE, 0.0),
        Arguments.of((double) Float.MAX_VALUE * 16, 0.0),
        Arguments.of(0.05, Double.MAX_VALUE));
  }

  /**
   * Arithmetic overflow must be reported by training.
   *
   * @param learningRate The finite step size.
   * @param l2 The finite regularization value.
   */
  @ParameterizedTest
  @MethodSource("divergentSettings")
  void testTrainingDivergence(double learningRate, double l2) {
    final IllegalStateException error = assertThrows(IllegalStateException.class,
        () -> FeedforwardPOSTrainer.train(ObjectStreamUtils.createObjectStream(SAMPLES),
            settings(learningRate, l2)));
    assertTrue(error.getMessage().contains("non-finite"), error.getMessage());
  }

  /**
   * Small positive learning rates remain valid and produce loadable models.
   *
   * @param learningRate The finite positive step size.
   * @throws IOException If training or model loading fails.
   */
  @ParameterizedTest
  @ValueSource(doubles = {Double.MIN_VALUE, Double.MIN_NORMAL, 0.05})
  void testFiniteSettings(double learningRate) throws IOException {
    final FeedforwardPOSModel model = FeedforwardPOSTrainer.train(
        ObjectStreamUtils.createObjectStream(SAMPLES), settings(learningRate, 0));
    assertLoadable(model);
  }

  /**
   * Finite regularization is accepted when the arithmetic remains finite.
   *
   * @param l2 The non-negative regularization value.
   * @throws IOException If training or model loading fails.
   */
  @ParameterizedTest
  @ValueSource(doubles = {0.0, -0.0, Double.MIN_NORMAL, 1e100})
  void testFiniteRegularization(double l2) throws IOException {
    final FeedforwardPOSModel model = FeedforwardPOSTrainer.train(
        ObjectStreamUtils.createObjectStream(SAMPLES), settings(0.05, l2));
    assertLoadable(model);
  }

  /**
   * Finite vector components are accepted without clipping or source-array modification.
   *
   * @param value The vector component to preserve.
   * @throws IOException If training or model loading fails.
   */
  @ParameterizedTest
  @ValueSource(floats = {0.0f, -0.0f, Float.MIN_VALUE, Float.MIN_NORMAL,
      Float.MAX_VALUE, -Float.MAX_VALUE})
  void testFiniteVectors(float value) throws IOException {
    final float[] source = {value, 0.25f};
    final FeedforwardPOSModel model = FeedforwardPOSTrainer.train(
        ObjectStreamUtils.createObjectStream(SAMPLES), settings(0.05, 0),
        word -> source, List.of("OWL"));
    assertArrayEquals(new float[] {value, 0.25f}, source);
    final int row = model.pretrainedRows(new String[] {OWL}, 0)[2];
    assertArrayEquals(source, model.pretrainedVectors()[row]);
    Arrays.fill(source, 99.0f);
    assertArrayEquals(new float[] {value, 0.25f}, model.pretrainedVectors()[row]);
    assertLoadable(model);
  }

  /**
   * Confirms that a trained model satisfies the serialized model contract.
   *
   * @param model The trained model.
   * @throws IOException If serialization or loading fails.
   */
  private void assertLoadable(FeedforwardPOSModel model) throws IOException {
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    model.serialize(output);
    final FeedforwardPOSModel reloaded = FeedforwardPOSModel.load(
        new ByteArrayInputStream(output.toByteArray()));
    assertArrayEquals(model.tags(), reloaded.tags());
  }

  /**
   * Creates small, deterministic training settings without dropout.
   *
   * @param learningRate The step size.
   * @param l2 The regularization value.
   * @return The settings.
   */
  private FeedforwardPOSTrainer.Settings settings(double learningRate, double l2) {
    return new FeedforwardPOSTrainer.Settings(2, 4, 1, 2, learningRate, l2, 0, 1, 1, 17);
  }
}
