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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Checks CRF calculations using exhaustive tag sequences with exact score sums. */
class CrfScorerPrecisionTest {

  /** {@return inputs with large common offsets and cancelling parameters} */
  private static Stream<Input> examples() {
    final List<Input> examples = new ArrayList<>();
    for (int tags : new int[] {1, 2, 3}) {
      for (int steps : new int[] {1, 3}) {
        for (int component = 0; component < 4; component++) {
          for (double offset : new double[] {0, 100, 1e10, 1e20, 1e300, -1e300,
              Double.MAX_VALUE}) {
            final Random random = new Random(17);
            final double[][] emissions = randomMatrix(random, steps, tags);
            final double[][] transitions = randomMatrix(random, tags, tags);
            final double[] start = randomMatrix(random, 1, tags)[0];
            final double[] end = randomMatrix(random, 1, tags)[0];
            final double[][] target = switch (component) {
              case 0 -> emissions;
              case 1 -> transitions;
              case 2 -> new double[][] {start};
              default -> new double[][] {end};
            };
            for (double[] values : target) {
              Arrays.fill(values, offset);
            }
            examples.add(new Input("offset-" + tags + "-" + steps + "-" + component
                + "-" + offset, emissions, transitions, start, end));
          }
        }
      }
      if (tags == 1) {
        continue;
      }
      for (double magnitude : new double[] {1e20, 1e300, Double.MAX_VALUE}) {
        final double[] start = {magnitude, -magnitude, 0};
        final double[][] emissions = new double[3][tags];
        final double[][] transitions = new double[tags][tags];
        for (int k = 0; k < tags; k++) {
          for (double[] values : emissions) {
            values[k] = -start[k];
          }
          for (double[] values : transitions) {
            values[k] = start[k];
          }
        }
        final double[] end = new double[tags];
        end[1] = 1;
        examples.add(new Input("cancellation-" + tags + "-" + magnitude, emissions,
            transitions, Arrays.copyOf(start, tags), end));
        examples.add(new Input("single-cancellation-" + tags + "-" + magnitude,
            new double[][] {emissions[0]}, transitions, Arrays.copyOf(start, tags), end));
      }
    }
    examples.add(new Input("finite-extreme-loss", new double[][] {{-1e300, 1e300}},
        new double[2][2], new double[2], new double[2]));
    examples.add(new Input("unrepresentable-loss",
        new double[][] {{-Double.MAX_VALUE, Double.MAX_VALUE}},
        new double[2][2], new double[2], new double[2]));
    for (int exponent : new int[] {-900, -50, 0, 50, 900}) {
      for (int seed = 0; seed < 16; seed++) {
        final Random random = new Random(seed);
        final double[][] emissions = randomMatrix(random, 4, 3);
        final double[][] transitions = randomMatrix(random, 3, 3);
        for (double[][] matrix : new double[][][] {emissions, transitions}) {
          for (double[] values : matrix) {
            for (int k = 0; k < values.length; k++) {
              values[k] = Math.scalb(values[k], exponent);
            }
          }
        }
        examples.add(new Input("scaled-" + exponent + "-" + seed, emissions, transitions,
            randomMatrix(random, 1, 3)[0], randomMatrix(random, 1, 3)[0]));
      }
    }
    return examples.stream();
  }

  /**
   * Compares loss and additive gradients with the exhaustive probabilities.
   *
   * @param input The CRF parameters.
   */
  @ParameterizedTest
  @MethodSource("examples")
  void testLossAndGradients(Input input) {
    final Expected expected = enumerate(input);
    final int steps = input.emissions().length;
    final int tags = input.start().length;
    final int[] gold = new int[steps];
    for (int t = 0; t < steps; t++) {
      gold[t] = t % tags;
    }
    final double[][] emissionGrads = new double[steps][tags];
    final double[][] transitionGrads = new double[tags][tags];
    final double[] startGrads = new double[tags];
    final double[] endGrads = new double[tags];
    Arrays.fill(startGrads, 0.25);
    Arrays.fill(endGrads, 0.25);
    for (double[] values : emissionGrads) {
      Arrays.fill(values, 0.25);
    }
    for (double[] values : transitionGrads) {
      Arrays.fill(values, 0.25);
    }
    final double loss = new CrfScorer(tags).lossAndGradients(input.emissions(), gold,
        input.transitions(), input.start(), input.end(), emissionGrads, transitionGrads,
        startGrads, endGrads);
    if (Double.isFinite(expected.loss())) {
      assertEquals(expected.loss(), loss, Math.max(1e-12, 4 * Math.ulp(expected.loss())));
    } else {
      assertEquals(expected.loss(), loss);
    }
    for (int t = 0; t < steps; t++) {
      for (int k = 0; k < tags; k++) {
        assertEquals(0.25 + expected.marginals()[t][k] - (gold[t] == k ? 1 : 0),
            emissionGrads[t][k], 1e-12, "emission gradient " + t + ", " + k);
      }
    }
    for (int k = 0; k < tags; k++) {
      assertEquals(0.25 + expected.marginals()[0][k] - (gold[0] == k ? 1 : 0),
          startGrads[k], 1e-12, "start gradient " + k);
      assertEquals(0.25 + expected.marginals()[steps - 1][k]
          - (gold[steps - 1] == k ? 1 : 0), endGrads[k], 1e-12, "end gradient " + k);
      for (int j = 0; j < tags; j++) {
        double value = 0.25 + expected.transitions()[j][k];
        for (int t = 1; t < steps; t++) {
          if (gold[t - 1] == j && gold[t] == k) {
            value -= 1;
          }
        }
        assertEquals(value, transitionGrads[j][k], 1e-12, "transition gradient " + j + ", " + k);
      }
    }
  }

  /**
   * Checks posterior probabilities independently of the training method.
   *
   * @param input The CRF parameters.
   */
  @ParameterizedTest
  @MethodSource("examples")
  void testMarginals(Input input) {
    final double[][] expected = enumerate(input).marginals();
    final double[][] actual = new CrfScorer(input.start().length).marginals(
        input.emissions(), input.transitions(), input.start(), input.end());
    for (int t = 0; t < actual.length; t++) {
      assertArrayEquals(expected[t], actual[t], 1e-12);
    }
  }

  /**
   * Checks decoding, including equal-score ordering.
   *
   * @param input The CRF parameters.
   */
  @ParameterizedTest
  @MethodSource("examples")
  void testViterbi(Input input) {
    assertArrayEquals(enumerate(input).best(), new CrfScorer(input.start().length).viterbi(
        input.emissions(), input.transitions(), input.start(), input.end()));
  }

  /** {@return non-finite scores in each parameter type for all scorer operations} */
  private static Stream<Arguments> invalidExamples() {
    final List<Arguments> cases = new ArrayList<>();
    for (double value : new double[] {Double.NaN, Double.NEGATIVE_INFINITY,
        Double.POSITIVE_INFINITY}) {
      for (int component = 0; component < 4; component++) {
        for (int operation = 0; operation < 3; operation++) {
          cases.add(Arguments.of(value, component, operation));
        }
      }
    }
    return cases.stream();
  }

  /**
   * Rejects non-finite computed scores without returning a tagging or probabilities.
   *
   * @param value The invalid score.
   * @param component The parameter type.
   * @param operation The scorer operation.
   */
  @ParameterizedTest
  @MethodSource("invalidExamples")
  void testNonFiniteScores(double value, int component, int operation) {
    final double[][] emissions = new double[2][2];
    final double[][] transitions = new double[2][2];
    final double[] start = new double[2];
    final double[] end = new double[2];
    switch (component) {
      case 0 -> emissions[0][1] = value;
      case 1 -> transitions[0][1] = value;
      case 2 -> start[1] = value;
      default -> end[1] = value;
    }
    final CrfScorer scorer = new CrfScorer(2);
    assertThrows(IllegalStateException.class, () -> {
      switch (operation) {
        case 0 -> scorer.viterbi(emissions, transitions, start, end);
        case 1 -> scorer.marginals(emissions, transitions, start, end);
        default -> scorer.lossAndGradients(emissions, new int[2], transitions, start, end,
            new double[2][2], new double[2][2], new double[2], new double[2]);
      }
    });
  }

  /**
   * Tests long sequences without accumulating common score offsets.
   *
   * @param steps The sequence length.
   */
  @ParameterizedTest
  @ValueSource(ints = {1, 100, 10000})
  void testLongSequence(int steps) {
    final double[][] emissions = new double[steps][2];
    for (double[] values : emissions) {
      Arrays.fill(values, 1e20);
    }
    final CrfScorer scorer = new CrfScorer(2);
    final double[][] gradients = new double[steps][2];
    final double[][] marginals = scorer.marginals(emissions, new double[2][2],
        new double[2], new double[] {0, 1});
    for (int t = 0; t < steps - 1; t++) {
      assertArrayEquals(new double[] {0.5, 0.5}, marginals[t], 1e-12);
    }
    assertArrayEquals(new double[] {1 / (1 + Math.exp(1)), 1 / (1 + Math.exp(-1))},
        marginals[steps - 1], 1e-12);
    assertEquals((steps - 1) * Math.log(2) + Math.log1p(Math.exp(1)),
        scorer.lossAndGradients(emissions, new int[steps], new double[2][2], new double[2],
            new double[] {0, 1}, gradients, new double[2][2], new double[2], new double[2]),
        1e-8);
  }

  /**
   * A shared scorer uses independent arithmetic buffers for concurrent requests.
   *
   * @param offset The common emission score.
   * @throws Exception If a concurrent calculation fails.
   */
  @ParameterizedTest
  @ValueSource(doubles = {0, 1e20, 1e300})
  void testSharedScorer(double offset) throws Exception {
    final Input input = new Input("shared", new double[][] {{offset, offset}, {offset, offset}},
        new double[2][2], new double[2], new double[] {0, 1});
    final Expected expected = enumerate(input);
    final CrfScorer scorer = new CrfScorer(2);
    final List<Callable<Void>> tasks = new ArrayList<>();
    for (int i = 0; i < 32; i++) {
      tasks.add(() -> {
        assertArrayEquals(expected.best(), scorer.viterbi(input.emissions(), input.transitions(),
            input.start(), input.end()));
        final double[][] actual = scorer.marginals(input.emissions(), input.transitions(),
            input.start(), input.end());
        for (int t = 0; t < actual.length; t++) {
          assertArrayEquals(expected.marginals()[t], actual[t], 1e-12);
        }
        return null;
      });
    }
    try (var executor = Executors.newFixedThreadPool(4)) {
      for (var result : executor.invokeAll(tasks)) {
        result.get();
      }
    }
  }

  /**
   * Creates small binary fractions, avoiding input rounding before evaluation.
   *
   * @param random The random source.
   * @param height The first dimension.
   * @param width The last dimension.
   * @return The values.
   */
  private static double[][] randomMatrix(Random random, int height, int width) {
    final double[][] values = new double[height][width];
    for (double[] vector : values) {
      for (int k = 0; k < width; k++) {
        vector[k] = (random.nextInt(7) - 3) * 0.125;
      }
    }
    return values;
  }

  /**
   * Enumerates tag sequences with exact parameter addition before exponentiation.
   *
   * @param input The CRF parameters.
   * @return The reference loss, probabilities, transition counts and best sequence.
   */
  private Expected enumerate(Input input) {
    final int steps = input.emissions().length;
    final int tags = input.start().length;
    final int count = (int) Math.pow(tags, steps);
    final int[][] paths = new int[count][steps];
    final BigDecimal[] scores = new BigDecimal[count];
    int best = 0;
    int gold = 0;
    for (int code = 0; code < count; code++) {
      int remaining = code;
      boolean isGold = true;
      BigDecimal score = BigDecimal.ZERO;
      for (int t = 0; t < steps; t++) {
        final int tag = remaining % tags;
        remaining /= tags;
        paths[code][t] = tag;
        isGold &= tag == t % tags;
        score = score.add(new BigDecimal(input.emissions()[t][tag]));
        if (t == 0) {
          score = score.add(new BigDecimal(input.start()[tag]));
        } else {
          score = score.add(new BigDecimal(input.transitions()[paths[code][t - 1]][tag]));
        }
      }
      scores[code] = score.add(new BigDecimal(input.end()[paths[code][steps - 1]]));
      if (scores[code].compareTo(scores[best]) > 0) {
        best = code;
      }
      if (isGold) {
        gold = code;
      }
    }
    final double[] probabilities = new double[count];
    double total = 0;
    for (int code = 0; code < count; code++) {
      probabilities[code] = Math.exp(scores[code].subtract(scores[best]).doubleValue());
      total += probabilities[code];
    }
    final double[][] marginals = new double[steps][tags];
    final double[][] transitions = new double[tags][tags];
    for (int code = 0; code < count; code++) {
      final double probability = probabilities[code] / total;
      for (int t = 0; t < steps; t++) {
        marginals[t][paths[code][t]] += probability;
        if (t > 0) {
          transitions[paths[code][t - 1]][paths[code][t]] += probability;
        }
      }
    }
    return new Expected(scores[best].subtract(scores[gold]).doubleValue() + Math.log(total),
        marginals, transitions, paths[best]);
  }

  /** The CRF parameters for one test. */
  private record Input(String name, double[][] emissions, double[][] transitions,
      double[] start, double[] end) {
    /** {@inheritDoc} */
    @Override
    public String toString() {
      return name;
    }
  }

  /** Exhaustive reference results. */
  private record Expected(double loss, double[][] marginals, double[][] transitions, int[] best) {
  }
}
