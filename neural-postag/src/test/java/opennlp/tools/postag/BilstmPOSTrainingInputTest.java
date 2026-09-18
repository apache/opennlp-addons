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
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import opennlp.tools.util.CollectionObjectStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks training settings, sample fields and pretrained vector input. */
class BilstmPOSTrainingInputTest {

  /** {@return uppercase and code-point lowercase vocabulary pairs} */
  private static Stream<Arguments> casePairs() {
    return Stream.of(Arguments.of("İ", "i"), Arguments.of("ΟΣ", "οσ"),
        Arguments.of("𐐀", "𐐨"));
  }

  /**
   * Training, pretrained lookup and model reload use the same case mapping.
   *
   * @param upper The training token.
   * @param lower The corresponding code-point lowercase token.
   * @throws IOException If training or model persistence fails.
   */
  @ParameterizedTest
  @MethodSource("casePairs")
  void testCodePointVocabulary(String upper, String lower) throws IOException {
    final float[] vector = {0.5f, -0.25f};
    final BilstmPOSModel model = BilstmPOSTrainer.train(
        new CollectionObjectStream<>(List.of(
            new POSSample(new String[] {upper}, new String[] {"N"}))),
        settings(Parameter.DROPOUT, 0), word -> vector);
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    model.serialize(bytes);
    final BilstmPOSModel reloaded = BilstmPOSModel.load(
        new ByteArrayInputStream(bytes.toByteArray()));
    for (BilstmPOSModel candidate : List.of(model, reloaded)) {
      assertEquals(lower, BilstmPOSModel.normalize(upper));
      assertTrue(candidate.wordId(upper) > 0);
      assertEquals(candidate.wordId(upper), candidate.wordId(lower));
      assertArrayEquals(vector, candidate.pretrainedVector(lower));
    }
    assertEquals("*UNK*", BilstmPOSModel.normalize("*UNK*"));
  }


  private static final String CAT = "cat";
  private static final String UNSEEN = "unseen";
  private static final String UNKNOWN = "*UNK*";
  private static final List<POSSample> CORPUS = List.of(
      new POSSample(new String[] {"the", CAT}, new String[] {"D", "N"}),
      new POSSample(new String[] {"a", "dog"}, new String[] {"D", "N"}));

  /** Floating-point settings, in the order used by the fixture. */
  private enum Parameter {
    LEARNING_RATE, CLIP_NORM, DROPOUT, WORD_DROPOUT, PRETRAINED_DROPOUT,
    ENCODER_DROPOUT, AUX_LOSS_WEIGHT, PRETRAINED_TUNING
  }

  /** Public vector-based training paths. */
  private enum Mode {
    SINGLE, SINGLE_LEXICON, MULTI, MULTI_LEXICON
  }

  /** {@return non-finite inputs for all floating-point settings} */
  private static Stream<Arguments> invalidSettings() {
    return Arrays.stream(Parameter.values()).flatMap(parameter -> Stream.of(Double.NaN,
        Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
        .map(value -> Arguments.of(parameter, value)));
  }

  /**
   * Non-finite settings fail before the caller can start training.
   *
   * @param parameter The setting to replace.
   * @param value The invalid value.
   */
  @ParameterizedTest
  @MethodSource("invalidSettings")
  void testNonFiniteSettings(Parameter parameter, double value) {
    assertThrows(IllegalArgumentException.class, () -> settings(parameter, value));
  }

  /** {@return finite range boundaries for all floating-point settings} */
  private static Stream<Arguments> finiteSettings() {
    return Arrays.stream(Parameter.values()).flatMap(parameter -> Stream.of(-1.0, -0.0, 0.0,
        Double.MIN_VALUE, Math.nextDown(1.0), 1.0, Double.MAX_VALUE)
        .map(value -> Arguments.of(parameter, value)));
  }

  /**
   * Finite inputs follow each setting's documented interval.
   *
   * @param parameter The setting to replace.
   * @param value The boundary value.
   */
  @ParameterizedTest
  @MethodSource("finiteSettings")
  void testFiniteSettingBounds(Parameter parameter, double value) {
    final boolean valid = switch (parameter) {
      case LEARNING_RATE, CLIP_NORM -> value > 0;
      case AUX_LOSS_WEIGHT, PRETRAINED_TUNING -> value >= 0;
      default -> value >= 0 && value < 1;
    };
    if (valid) {
      assertDoesNotThrow(() -> settings(parameter, value));
    } else {
      assertThrows(IllegalArgumentException.class, () -> settings(parameter, value));
    }
  }

  /** {@return non-finite vector components in training and extra-word lookups} */
  private static Stream<Arguments> invalidVectors() {
    return Arrays.stream(Mode.values()).flatMap(mode -> Stream.of(CAT, UNSEEN)
        .filter(word -> word.equals(CAT) || mode == Mode.SINGLE_LEXICON || mode == Mode.MULTI_LEXICON)
        .flatMap(word -> Stream.of(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
            .flatMap(value -> Stream.of(0, 1, 2)
                .map(index -> Arguments.of(mode, word, value, index)))));
  }

  /**
   * Invalid vectors identify the normalized word and component index.
   *
   * @param mode The public training path.
   * @param word The word receiving the invalid vector.
   * @param value The invalid component.
   * @param index Its position in the vector.
   */
  @ParameterizedTest
  @MethodSource("invalidVectors")
  void testNonFiniteVector(Mode mode, String word, float value, int index) {
    final Function<CharSequence, float[]> source = token -> {
      final float[] vector = new float[3];
      if (word.contentEquals(token)) {
        vector[index] = value;
      }
      return vector;
    };
    final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> train(mode, settings(Parameter.DROPOUT, 0), source));
    assertTrue(error.getMessage().contains(word), error.getMessage());
    assertTrue(error.getMessage().contains(Integer.toString(index)), error.getMessage());
  }

  /** {@return finite vector extremes on each public training path} */
  private static Stream<Arguments> finiteVectors() {
    return Arrays.stream(Mode.values()).flatMap(mode -> Stream.of(-0.0f, 0.0f, Float.MIN_VALUE,
        -Float.MIN_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE)
        .map(value -> Arguments.of(mode, value)));
  }

  /**
   * Finite vectors remain accepted, copied and readable from a saved model.
   *
   * @param mode The public training path.
   * @param value The finite component value.
   * @throws IOException If training or model loading fails.
   */
  @ParameterizedTest
  @MethodSource("finiteVectors")
  void testFiniteVectors(Mode mode, float value) throws IOException {
    final float[] provided = {value, 0.0f, -0.0f};
    final float[] expected = provided.clone();
    final BilstmPOSModel model = train(mode, settings(Parameter.DROPOUT, 0), word -> provided);
    Arrays.fill(provided, 17.0f);
    assertArrayEquals(expected, model.pretrainedVector(CAT));
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    model.serialize(output);
    final BilstmPOSModel loaded = BilstmPOSModel.load(new ByteArrayInputStream(output.toByteArray()));
    assertArrayEquals(expected, loaded.pretrainedVector(CAT));
    for (double[] scores : loaded.score(new String[] {CAT, UNSEEN})) {
      for (double score : scores) {
        assertTrue(Double.isFinite(score));
      }
    }
  }

  /**
   * Fine-tuning updates only the model's copy of provider arrays.
   *
   * @param mode The public training path.
   * @throws IOException If training fails.
   */
  @ParameterizedTest
  @EnumSource(Mode.class)
  void testFineTuningDoesNotChangeProviderArray(Mode mode) throws IOException {
    final float[] provided = {0.5f, -0.25f, 0.125f};
    final float[] expected = provided.clone();
    train(mode, settings(Parameter.PRETRAINED_TUNING, 0.1), word -> provided);
    assertArrayEquals(expected, provided);
  }

  /**
   * An optional lexicon can provide the table even when training words have no vector.
   *
   * @param mode A training path with an additional lexicon.
   * @throws IOException If training fails.
   */
  @ParameterizedTest
  @EnumSource(value = Mode.class, names = {"SINGLE_LEXICON", "MULTI_LEXICON"})
  void testLexiconOnlyVectors(Mode mode) throws IOException {
    final float[] vector = {0.5f, -0.25f};
    final BilstmPOSModel model = train(mode, settings(Parameter.DROPOUT, 0),
        word -> UNSEEN.contentEquals(word) ? vector : null);
    assertNull(model.pretrainedVector(CAT));
    assertArrayEquals(vector, model.pretrainedVector(UNSEEN));
    assertEquals(1, new BilstmPOSTagger(model).tag(new String[] {UNSEEN}).length);
  }

  /**
   * A provider must return at least one vector across the requested words.
   *
   * @param mode The training path.
   */
  @ParameterizedTest
  @EnumSource(Mode.class)
  void testNoVectors(Mode mode) {
    assertThrows(IllegalArgumentException.class,
        () -> train(mode, settings(Parameter.DROPOUT, 0), word -> null));
  }

  /**
   * The manual's map-backed provider stores vectors for additional words.
   *
   * @throws IOException If training fails.
   */
  @Test
  void testManualVectorProvider() throws IOException {
    final Map<String, float[]> vectors = Map.of(
        CAT, new float[] {0.5f, -0.25f},
        "dog", new float[] {0.25f, 0.5f},
        "kitten", new float[] {0.4f, -0.2f});
    final BilstmPOSModel model = BilstmPOSTrainer.train(new CollectionObjectStream<>(CORPUS),
        settings(Parameter.DROPOUT, 0), word -> vectors.get(word.toString()), List.of("kitten"));
    assertArrayEquals(vectors.get("kitten"), model.pretrainedVector("kitten"));
    assertNull(model.pretrainedVector(UNSEEN));
    assertEquals(1, new BilstmPOSTagger(model).tag(new String[] {"kitten"}).length);
  }

  /** {@return null positions in required and optional sample arrays} */
  private static Stream<Arguments> nullSampleElements() {
    return Stream.of(0, 1, 2, 3).flatMap(array -> Stream.of(0, 1)
        .map(index -> Arguments.of(array, index)));
  }

  /**
   * Sample construction rejects null elements without prohibiting absent auxiliary arrays.
   *
   * @param array The array containing null: tokens, UPOS, XPOS or features.
   * @param index The null element's position.
   */
  @ParameterizedTest
  @MethodSource("nullSampleElements")
  void testNullSampleElement(int array, int index) {
    final String[][] values = {{"the", CAT}, {"D", "N"}, {"DT", "NN"}, {"_", "Number=Sing"}};
    values[array][index] = null;
    assertThrows(IllegalArgumentException.class,
        () -> new BilstmPOSTrainer.MultiTaskSample(values[0], values[1], values[2], values[3]));
  }

  /** {@return positions of the unknown symbol in the training corpus} */
  private static Stream<Arguments> unknownSymbols() {
    return Stream.of(new String[] {UNKNOWN}, new String[] {UNKNOWN, CAT},
        new String[] {CAT, UNKNOWN}).flatMap(tokens -> Stream.of(false, true)
            .map(multi -> Arguments.of(tokens, multi)));
  }

  /**
   * A literal unknown symbol does not overwrite the reserved embedding index.
   *
   * @param tokens The training sentence.
   * @param multi Whether to use the multi-task entry point.
   * @throws IOException If training, saving or loading fails.
   */
  @ParameterizedTest
  @MethodSource("unknownSymbols")
  void testUnknownSymbol(String[] tokens, boolean multi) throws IOException {
    final String[] tags = new String[tokens.length];
    Arrays.fill(tags, "N");
    final BilstmPOSTrainer.Settings settings = settings(Parameter.DROPOUT, 0);
    final BilstmPOSModel model = multi
        ? BilstmPOSTrainer.trainMultiTask(new CollectionObjectStream<>(List.of(
            new BilstmPOSTrainer.MultiTaskSample(tokens, tags, null, null))), settings, null, null)
        : BilstmPOSTrainer.train(new CollectionObjectStream<>(List.of(new POSSample(tokens, tags))),
            settings);
    assertEquals(0, model.wordId(UNKNOWN));
    assertEquals(0, model.wordId(UNSEEN));
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    model.serialize(output);
    final BilstmPOSModel loaded = BilstmPOSModel.load(new ByteArrayInputStream(output.toByteArray()));
    assertArrayEquals(tags, new BilstmPOSTagger(loaded).tag(tokens));
    assertArrayEquals(new String[] {"N"}, new BilstmPOSTagger(loaded).tag(new String[] {UNSEEN}));
  }

  /**
   * Creates a one-epoch configuration with one replaced floating-point setting.
   *
   * @param parameter The setting to replace.
   * @param value Its new value.
   * @return The settings.
   * @throws IllegalArgumentException If the replacement is outside the setting's range.
   */
  private BilstmPOSTrainer.Settings settings(Parameter parameter, double value) {
    final double[] values = {0.01, 5.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0};
    values[parameter.ordinal()] = value;
    return new BilstmPOSTrainer.Settings(4, 2, 2, 4, 1, 2, values[0], values[1], values[2],
        1, 12, 17L, 1, values[3], 0, false, 1, values[4], values[5], values[6], values[7], false);
  }

  /**
   * Trains through a selected public overload with a shared small corpus.
   *
   * @param mode The training path.
   * @param settings The training configuration.
   * @param vectors The vector provider.
   * @return The trained model.
   * @throws IOException If reading training data fails.
   * @throws IllegalArgumentException If a training input is invalid.
   */
  private BilstmPOSModel train(Mode mode, BilstmPOSTrainer.Settings settings,
      Function<CharSequence, float[]> vectors) throws IOException {
    return switch (mode) {
      case SINGLE -> BilstmPOSTrainer.train(new CollectionObjectStream<>(CORPUS), settings, vectors);
      case SINGLE_LEXICON -> BilstmPOSTrainer.train(new CollectionObjectStream<>(CORPUS), settings,
          vectors, List.of(UNSEEN));
      case MULTI, MULTI_LEXICON -> {
        final List<BilstmPOSTrainer.MultiTaskSample> corpus = CORPUS.stream().map(sample ->
            new BilstmPOSTrainer.MultiTaskSample(sample.getSentence(), sample.getTags(),
                new String[] {"DT", "NN"}, new String[] {"_", "Number=Sing"})).toList();
        yield BilstmPOSTrainer.trainMultiTask(new CollectionObjectStream<>(corpus), settings,
            vectors, mode == Mode.MULTI_LEXICON ? List.of(UNSEEN) : null);
      }
    };
  }
}
