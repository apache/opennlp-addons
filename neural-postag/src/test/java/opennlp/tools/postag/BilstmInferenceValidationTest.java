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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import opennlp.tools.util.Sequence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks numerical failures and token validation through public inference methods. */
class BilstmInferenceValidationTest {

  private enum Operation {
    SCORE, CACHED_SCORE, TAG, CONTEXT_TAG, SEQUENCES, CONTEXT_SEQUENCES
  }

  private enum Failure {
    NONE, POSITIVE_OVERFLOW, NEGATIVE_OVERFLOW, ENCODER, ADAPTER
  }

  /** {@return model variants, numerical failures and inference operations} */
  private static Stream<Arguments> failures() {
    final List<Arguments> cases = new ArrayList<>();
    for (boolean crf : new boolean[] {false, true}) {
      for (boolean stacked : new boolean[] {false, true}) {
        for (Failure failure : new Failure[] {Failure.POSITIVE_OVERFLOW,
            Failure.NEGATIVE_OVERFLOW, Failure.ENCODER, Failure.ADAPTER}) {
          final int tags = failure == Failure.POSITIVE_OVERFLOW
              || failure == Failure.NEGATIVE_OVERFLOW ? 2 : 1;
          for (int tag = 0; tag < tags; tag++) {
            for (Operation operation : Operation.values()) {
              cases.add(Arguments.of(crf, stacked, failure, tag, operation));
            }
          }
        }
      }
    }
    return cases.stream();
  }

  /**
   * A valid finite-parameter file can fail numerically during inference.
   *
   * @param crf Whether to use CRF decoding.
   * @param stacked Whether to use stacked encoders.
   * @param failure The computation that fails.
   * @param tag The output affected by direct overflow.
   * @param operation The public inference method.
   * @throws IOException If the model cannot be saved or loaded.
   */
  @ParameterizedTest
  @MethodSource("failures")
  void testNumericalFailure(boolean crf, boolean stacked, Failure failure, int tag,
      Operation operation) throws IOException {
    final BilstmPOSModel model = model(crf, stacked, failure, tag, new double[2]);
    final IllegalStateException error = assertThrows(IllegalStateException.class,
        () -> invoke(operation, model, new String[] {"token", "other", "token"}));
    assertFalse(error.getMessage().contains("corrupt"));
  }

  /** {@return null-token positions for each decoder and inference method} */
  private static Stream<Arguments> nullTokens() {
    return Stream.of(false, true).flatMap(crf -> Stream.of(Operation.values())
        .flatMap(operation -> Stream.of(0, 1, 2)
            .map(position -> Arguments.of(crf, operation, position))));
  }

  /**
   * Identifies invalid elements before feature computation or cache access.
   *
   * @param crf Whether to use CRF decoding.
   * @param operation The public inference method.
   * @param position The null element's position.
   * @throws IOException If the model cannot be saved or loaded.
   */
  @ParameterizedTest
  @MethodSource("nullTokens")
  void testNullToken(boolean crf, Operation operation, int position) throws IOException {
    final BilstmPOSModel model = model(crf, false, Failure.NONE, 0, new double[2]);
    final String[] tokens = {"token", "other", "token"};
    tokens[position] = null;
    final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> invoke(operation, model, tokens));
    assertEquals("tokens[" + position + "] must not be null", error.getMessage());
  }

  /** A null value is rejected by the standalone normalization API. */
  @Test
  void testNullNormalization() {
    final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> BilstmPOSModel.normalize(null));
    assertEquals("token must not be null", error.getMessage());
  }

  /** {@return inference operations for each decoder} */
  private static Stream<Arguments> operations() {
    return Stream.of(false, true).flatMap(crf -> Stream.of(Operation.values())
        .map(operation -> Arguments.of(crf, operation)));
  }

  /**
   * Retains the public methods' null-array errors.
   *
   * @param crf Whether to use CRF decoding.
   * @param operation The public inference method.
   * @throws IOException If the model cannot be saved or loaded.
   */
  @ParameterizedTest
  @MethodSource("operations")
  void testNullArray(boolean crf, Operation operation) throws IOException {
    final BilstmPOSModel model = model(crf, false, Failure.NONE, 0, new double[2]);
    final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> invoke(operation, model, null));
    assertEquals(operation == Operation.SCORE || operation == Operation.CACHED_SCORE
        ? "tokens must not be null or empty" : "sentence must not be null", error.getMessage());
  }

  /**
   * Empty tagging results do not require scoring a sentence.
   *
   * @param crf Whether to use CRF decoding.
   * @param operation The public inference method.
   * @throws IOException If the model cannot be saved or loaded.
   */
  @ParameterizedTest
  @MethodSource("operations")
  void testEmptyArray(boolean crf, Operation operation) throws IOException {
    final BilstmPOSModel model = model(crf, false, Failure.POSITIVE_OVERFLOW, 0,
        new double[2]);
    if (operation == Operation.SCORE || operation == Operation.CACHED_SCORE) {
      final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
          () -> invoke(operation, model, new String[0]));
      assertEquals("tokens must not be null or empty", error.getMessage());
    } else {
      final Object result = invoke(operation, model, new String[0]);
      if (result instanceof String[] tags) {
        assertEquals(0, tags.length);
      } else {
        final Sequence[] sequences = (Sequence[]) result;
        assertEquals(1, sequences.length);
        assertTrue(sequences[0].getOutcomes().isEmpty());
        assertEquals(0, sequences[0].getProbs().length);
        assertEquals(0.0, sequences[0].getScore());
      }
    }
  }

  /** {@return tokens and their model vocabulary lookup forms} */
  private static Stream<Arguments> lookupForms() {
    return Stream.of(Arguments.of("", ""), Arguments.of(" ", " "),
        Arguments.of("Token", "token"), Arguments.of("*UNK*", "*UNK*"),
        Arguments.of("\u0130", "i"), Arguments.of("\uD801\uDC00", "\uD801\uDC28"),
        Arguments.of("\uD800", "\uD800"), Arguments.of("\uDC00", "\uDC00"));
  }

  /**
   * Validation does not change token content or vocabulary normalization.
   *
   * @param token The input token.
   * @param lookup The expected lookup form.
   * @throws IOException If the model cannot be saved or loaded.
   */
  @ParameterizedTest
  @MethodSource("lookupForms")
  void testTokenContent(String token, String lookup) throws IOException {
    assertEquals(lookup, BilstmPOSModel.normalize(token));
    final BilstmPOSModel model = model(false, false, Failure.NONE, 0, new double[2]);
    final String[] tokens = {token};
    assertArrayEquals(new double[2], model.score(tokens)[0]);
    assertArrayEquals(new String[] {"N"}, new BilstmPOSTagger(model).tag(tokens));
    assertArrayEquals(new String[] {token}, tokens);
  }

  /** {@return finite extreme scores for each decoder and encoder depth} */
  private static Stream<Arguments> finiteScores() {
    final List<Arguments> cases = new ArrayList<>();
    for (boolean crf : new boolean[] {false, true}) {
      for (boolean stacked : new boolean[] {false, true}) {
        for (double[] scores : new double[][] {{0, 0}, {Double.MAX_VALUE, Double.MAX_VALUE},
            {-Double.MAX_VALUE, -Double.MAX_VALUE}, {-Double.MAX_VALUE, Double.MAX_VALUE},
            {Double.MAX_VALUE, -Double.MAX_VALUE}, {Double.MIN_VALUE, 0}, {-1000, 0}}) {
          cases.add(Arguments.of(crf, stacked, scores[0], scores[1]));
        }
      }
    }
    return cases.stream();
  }

  /**
   * Finite extreme scores retain valid probabilities and selected tags.
   *
   * @param crf Whether to use CRF decoding.
   * @param stacked Whether to use stacked encoders.
   * @param first The first tag's score.
   * @param second The other tag's score.
   * @throws IOException If the model cannot be saved or loaded.
   */
  @ParameterizedTest
  @MethodSource("finiteScores")
  void testFiniteScores(boolean crf, boolean stacked, double first, double second)
      throws IOException {
    final BilstmPOSModel model = model(crf, stacked, Failure.NONE, 0,
        new double[] {first, second});
    final String[] tokens = {"token", "", "other"};
    final String expectedTag = second > first ? "V" : "N";
    final double expectedProbability = 1 / (1 + Math.exp(Math.min(first, second)
        - Math.max(first, second)));
    for (Operation operation : Operation.values()) {
      final Object result = invoke(operation, model, tokens);
      if (result instanceof double[][] scores) {
        for (double[] values : scores) {
          assertArrayEquals(new double[] {first, second}, values);
        }
      } else if (result instanceof String[] tags) {
        assertArrayEquals(new String[] {expectedTag, expectedTag, expectedTag}, tags);
      } else {
        final Sequence[] sequences = (Sequence[]) result;
        assertEquals(1, sequences.length);
        assertArrayEquals(new String[] {expectedTag, expectedTag, expectedTag},
            sequences[0].getOutcomes().toArray(String[]::new));
        for (double probability : sequences[0].getProbs()) {
          assertTrue(Double.isFinite(probability));
          assertEquals(expectedProbability, probability, 1e-15);
        }
      }
    }
  }

  /**
   * Calls an inference entry point without changing input arrays.
   *
   * @param operation The entry point.
   * @param model The model.
   * @param tokens The input.
   * @return The inference result.
   */
  private Object invoke(Operation operation, BilstmPOSModel model, String[] tokens) {
    if (operation == Operation.SCORE) {
      return model.score(tokens);
    }
    if (operation == Operation.CACHED_SCORE) {
      model.enableRepresentationCache();
      return model.score(tokens);
    }
    final BilstmPOSTagger tagger = new BilstmPOSTagger(model);
    return switch (operation) {
      case TAG -> tagger.tag(tokens);
      case CONTEXT_TAG -> tagger.tag(tokens, new Object[0]);
      case SEQUENCES -> tagger.topKSequences(tokens);
      case CONTEXT_SEQUENCES -> tagger.topKSequences(tokens, new Object[0]);
      default -> throw new AssertionError(operation);
    };
  }

  /**
   * Saves and loads finite parameters that isolate an output or encoder computation.
   *
   * @param crf Whether to include CRF weights.
   * @param stacked Whether to include another encoder.
   * @param failure The numerical failure to construct.
   * @param badTag The output index for direct overflow.
   * @param bias The output biases.
   * @return The loaded model.
   * @throws IOException If serialization or loading fails.
   */
  private BilstmPOSModel model(boolean crf, boolean stacked, Failure failure, int badTag,
      double[] bias) throws IOException {
    final LinkedHashMap<String, Integer> words = new LinkedHashMap<>();
    words.put(BilstmPOSModel.UNKNOWN, 0);
    final LinkedHashMap<String, Integer> chars = new LinkedHashMap<>(words);
    final double[][] wordEmbeddings = new double[1][2];
    if (failure == Failure.ENCODER) {
      Arrays.fill(wordEmbeddings[0], Double.MAX_VALUE);
    }
    final int inputSize = failure == Failure.ADAPTER ? 5 : 4;
    final double[][] inputWeights = new double[4][inputSize];
    if (failure == Failure.ENCODER) {
      inputWeights[0][0] = Double.MAX_VALUE;
      inputWeights[0][1] = -Double.MAX_VALUE;
    }
    final LstmLayer charLayer = LstmLayer.ofWeights(1, 1, new double[4][1],
        new double[4][1], new double[4]);
    final LstmLayer wordLayer = LstmLayer.ofWeights(inputSize, 1, inputWeights,
        new double[4][1], new double[] {8, 0, 8, 8});
    final LstmLayer second = stacked ? LstmLayer.ofWeights(2, 1, new double[4][2],
        new double[4][1], new double[] {8, 0, 8, 8}) : null;
    final double[][] outputWeights = new double[2][2];
    if (failure == Failure.POSITIVE_OVERFLOW || failure == Failure.NEGATIVE_OVERFLOW) {
      Arrays.fill(outputWeights[badTag], failure == Failure.POSITIVE_OVERFLOW
          ? Double.MAX_VALUE : -Double.MAX_VALUE);
    }
    final LinkedHashMap<String, Integer> pretrained = new LinkedHashMap<>();
    pretrained.put("token", 0);
    final boolean adapter = failure == Failure.ADAPTER;
    final BilstmPOSModel original = new BilstmPOSModel(words, chars,
        new String[] {"N", "V"}, wordEmbeddings, new double[1][1], charLayer, charLayer,
        wordLayer, wordLayer, second, second, outputWeights, bias, 12,
        adapter ? pretrained : null, adapter ? new float[][] {{Float.MAX_VALUE}} : null,
        crf ? new double[2][2] : null, crf ? new double[2] : null,
        crf ? new double[2] : null, adapter ? new double[][] {{Double.MAX_VALUE}} : null,
        adapter ? new double[1] : null);
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    original.serialize(output);
    return BilstmPOSModel.load(new ByteArrayInputStream(output.toByteArray()));
  }
}
