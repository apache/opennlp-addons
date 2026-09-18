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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import opennlp.tools.util.CollectionObjectStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks derived array dimensions without allocating large models in the test JVM. */
class BilstmDimensionTest {

  @TempDir
  private Path temporary;

  private enum Mode {
    SINGLE, SINGLE_LEXICON, MULTI, MULTI_LEXICON, LAYER_INITIALIZATION, LAYER_WEIGHTS
  }

  /** {@return invalid and representable settings boundaries} */
  private static Stream<Arguments> dimensions() {
    final List<Arguments> cases = new ArrayList<>();
    for (int field = 0; field < 4; field++) {
      for (int value : new int[] {0, -1, Integer.MIN_VALUE}) {
        final int[] sizes = {1, 1, 1, 1};
        sizes[field] = value;
        cases.add(Arguments.of(sizes[0], sizes[1], sizes[2], sizes[3], false));
      }
    }
    for (int value : new int[] {Integer.MAX_VALUE / 4 + 1, 1 << 30, Integer.MAX_VALUE}) {
      cases.add(Arguments.of(1, 1, value, 1, false));
      cases.add(Arguments.of(1, 1, 1, value, false));
    }
    cases.add(Arguments.of(Integer.MAX_VALUE, 1, 1, 1, false));
    cases.add(Arguments.of(Integer.MAX_VALUE - 1, 1, 1, 1, false));
    cases.add(Arguments.of(Integer.MAX_VALUE - 3, 1, 2, 1, false));
    cases.add(Arguments.of(Integer.MAX_VALUE - 100, 1, 51, 1, false));
    cases.add(Arguments.of(1, 1, 1, 1, true));
    cases.add(Arguments.of(Integer.MAX_VALUE - 2, 1, 1, 1, true));
    cases.add(Arguments.of(1, Integer.MAX_VALUE, 1, 1, true));
    cases.add(Arguments.of(1, 1, Integer.MAX_VALUE / 4, 1, true));
    cases.add(Arguments.of(1, 1, 1, Integer.MAX_VALUE / 4, true));
    cases.add(Arguments.of(Integer.MAX_VALUE / 2 + 1, 1,
        Integer.MAX_VALUE / 4, Integer.MAX_VALUE / 4, true));
    cases.add(Arguments.of(Integer.MAX_VALUE - 2 * (Integer.MAX_VALUE / 4), 1,
        Integer.MAX_VALUE / 4, 1, true));
    return cases.stream();
  }

  /**
   * Settings reject overflowing dimensions without imposing a machine-specific memory cap.
   *
   * @param word The word embedding width.
   * @param character The character embedding width.
   * @param charHidden The character encoder width.
   * @param hidden The sentence encoder width.
   * @param valid Whether all calculated widths fit positive integers.
   */
  @ParameterizedTest
  @MethodSource("dimensions")
  void testSettings(int word, int character, int charHidden, int hidden, boolean valid) {
    if (valid) {
      assertDoesNotThrow(() -> settings(word, character, charHidden, hidden));
    } else {
      assertThrows(IllegalArgumentException.class,
          () -> settings(word, character, charHidden, hidden));
    }
  }

  /** {@return public training paths and overflowing layer widths for bounded child checks} */
  private static Stream<Arguments> childCases() {
    final List<Arguments> cases = new ArrayList<>();
    for (Mode mode : Mode.values()) {
      if (mode == Mode.LAYER_INITIALIZATION || mode == Mode.LAYER_WEIGHTS) {
        for (int hidden : new int[] {Integer.MAX_VALUE / 4 + 1, 1 << 30, Integer.MAX_VALUE}) {
          cases.add(Arguments.of(mode, hidden));
        }
      } else {
        cases.add(Arguments.of(mode, 1));
      }
    }
    return cases.stream();
  }

  /** {@return non-positive input and hidden widths at both layer entry points} */
  private static Stream<Arguments> nonPositiveLayers() {
    return Stream.of(0, -1, Integer.MIN_VALUE).flatMap(value -> Stream.of(
        Arguments.of(value, 1, false), Arguments.of(1, value, false),
        Arguments.of(value, 1, true), Arguments.of(1, value, true)));
  }

  /**
   * Both layer entry points reject non-positive dimensions.
   *
   * @param input The input width.
   * @param hidden The recurrent width.
   * @param weights Whether to wrap weight arrays instead of initializing them.
   */
  @ParameterizedTest
  @MethodSource("nonPositiveLayers")
  void testNonPositiveLayer(int input, int hidden, boolean weights) {
    if (weights) {
      final int gates = hidden == 0 ? 0 : 4;
      assertThrows(IllegalArgumentException.class, () -> LstmLayer.ofWeights(input, hidden,
          new double[gates][1], new double[gates][1], new double[gates]));
    } else {
      assertThrows(IllegalArgumentException.class, () -> new LstmLayer(input, hidden,
          new Random(17)));
    }
  }

  /**
   * Invalid dimensions fail before large parameter allocations in a bounded process.
   *
   * @param mode The construction or training path.
   * @param hidden The layer width for layer construction.
   * @throws Exception If process execution or log reading fails.
   */
  @ParameterizedTest
  @MethodSource("childCases")
  void testBoundedRejection(Mode mode, int hidden) throws Exception {
    final Path log = temporary.resolve("dimensions.log");
    final Process child = new ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx32m", "-cp",
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
        DimensionProbe.class.getName(), mode.name(), Integer.toString(hidden))
        .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    try {
      assertTrue(child.waitFor(20, TimeUnit.SECONDS), "dimension check did not finish");
      assertEquals(0, child.exitValue(), Files.readString(log));
    } finally {
      child.destroyForcibly();
      assertTrue(child.waitFor(5, TimeUnit.SECONDS), "dimension check process did not exit");
    }
  }

  /**
   * Creates settings without allocating model parameters.
   *
   * @param word The word embedding width.
   * @param character The character embedding width.
   * @param charHidden The character encoder width.
   * @param hidden The sentence encoder width.
   * @return The settings.
   * @throws IllegalArgumentException If a dimension is invalid.
   */
  private static BilstmPOSTrainer.Settings settings(int word, int character, int charHidden,
      int hidden) {
    return new BilstmPOSTrainer.Settings(word, character, charHidden, hidden, 1, 1,
        0.01, 5.0, 0.0, 1, 8, 17L, 1, 0.0, 0, false, 1, 0.0, 0.0, 1.0, 0.0, false);
  }

  /** Runs allocation-sensitive checks outside the Maven test JVM. */
  public static final class DimensionProbe {

    /**
     * Exits successfully only when the selected path rejects its invalid dimensions.
     *
     * @param arguments The mode and hidden width.
     * @throws IOException If reading the sample stream fails.
     */
    public static void main(String[] arguments) throws IOException {
      final Mode mode = Mode.valueOf(arguments[0]);
      final int hidden = Integer.parseInt(arguments[1]);
      try {
        switch (mode) {
          case LAYER_INITIALIZATION -> new LstmLayer(1, hidden, new Random(17));
          case LAYER_WEIGHTS -> LstmLayer.ofWeights(1, hidden, new double[0][],
              new double[0][], new double[0]);
          default -> train(mode);
        }
      } catch (IllegalArgumentException expected) {
        if (mode != Mode.LAYER_INITIALIZATION && mode != Mode.LAYER_WEIGHTS
            && !expected.getMessage().contains("representation")) {
          throw new AssertionError("unexpected validation error", expected);
        }
        return;
      }
      throw new AssertionError("invalid dimensions were accepted");
    }

    /**
     * Adds pretrained width to a representable base width before parameter allocation.
     *
     * @param mode The public training overload.
     * @throws IOException If reading training data fails.
     * @throws IllegalArgumentException If training rejects the combined input width.
     */
    private static void train(Mode mode) throws IOException {
      final BilstmPOSTrainer.Settings settings = settings(Integer.MAX_VALUE - 2, 1, 1, 1);
      final List<POSSample> samples = List.of(
          new POSSample(new String[] {"word"}, new String[] {"N"}));
      final boolean lexicon = mode == Mode.SINGLE_LEXICON || mode == Mode.MULTI_LEXICON;
      final Function<CharSequence, float[]> vectors = word ->
          !lexicon || "extra".contentEquals(word) ? new float[3] : null;
      switch (mode) {
        case SINGLE -> BilstmPOSTrainer.train(new CollectionObjectStream<>(samples), settings,
            vectors);
        case SINGLE_LEXICON -> BilstmPOSTrainer.train(new CollectionObjectStream<>(samples),
            settings, vectors, List.of("extra"));
        case MULTI, MULTI_LEXICON -> BilstmPOSTrainer.trainMultiTask(new CollectionObjectStream<>(
            List.of(new BilstmPOSTrainer.MultiTaskSample(new String[] {"word"},
                new String[] {"N"}, null, null))), settings, vectors,
            lexicon ? List.of("extra") : null);
        default -> throw new AssertionError(mode);
      }
    }
  }
}
