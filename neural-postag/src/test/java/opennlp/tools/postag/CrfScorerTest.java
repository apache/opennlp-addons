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

import java.util.Random;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Finite-difference gradient checks for {@link CrfScorer} forward-backward, plus a
 * brute-force check that Viterbi decoding finds the true best sequence.
 */
class CrfScorerTest {

  private static final int TAGS = 3;
  private static final int STEPS = 4;
  private static final double EPSILON = GradientChecks.EPSILON;
  private static final double TOLERANCE = 1e-5d;

  private CrfScorer scorer;
  private double[][] emissions;
  private double[][] transitions;
  private double[] start;
  private double[] end;
  private int[] gold;
  private double[][] emissionGrads;
  private double[][] transitionGrads;
  private double[] startGrads;
  private double[] endGrads;

  @BeforeEach
  void setUp() {
    scorer = new CrfScorer(TAGS);
    final Random random = new Random(13L);
    emissions = randomMatrix(random, STEPS, TAGS);
    transitions = randomMatrix(random, TAGS, TAGS);
    start = randomVector(random, TAGS);
    end = randomVector(random, TAGS);
    gold = new int[] {0, 2, 1, 1};
    emissionGrads = new double[STEPS][TAGS];
    transitionGrads = new double[TAGS][TAGS];
    startGrads = new double[TAGS];
    endGrads = new double[TAGS];
    scorer.lossAndGradients(emissions, gold, transitions, start, end, emissionGrads,
        transitionGrads, startGrads, endGrads);
  }

  @Test
  void testEmissionGradientsMatchFiniteDifferences() {
    for (int t = 0; t < STEPS; t++) {
      for (int k = 0; k < TAGS; k++) {
        assertClose(emissionGrads[t][k], numerical(emissions[t], k),
            "emissions[" + t + "][" + k + "]");
      }
    }
  }

  @Test
  void testTransitionGradientsMatchFiniteDifferences() {
    for (int j = 0; j < TAGS; j++) {
      for (int k = 0; k < TAGS; k++) {
        assertClose(transitionGrads[j][k], numerical(transitions[j], k),
            "transitions[" + j + "][" + k + "]");
      }
    }
  }

  @Test
  void testStartEndGradientsMatchFiniteDifferences() {
    for (int k = 0; k < TAGS; k++) {
      assertClose(startGrads[k], numerical(start, k), "start[" + k + "]");
      assertClose(endGrads[k], numerical(end, k), "end[" + k + "]");
    }
  }

  @Test
  void testViterbiMatchesBruteForce() {
    final int[] expected = bestSequenceBruteForce();
    assertArrayEquals(expected, scorer.viterbi(emissions, transitions, start, end));
  }

  @Test
  void testMarginalsSumToOne() {
    final double[][] marginals = scorer.marginals(emissions, transitions, start, end);
    for (int t = 0; t < STEPS; t++) {
      double sum = 0.0d;
      for (int k = 0; k < TAGS; k++) {
        sum += marginals[t][k];
      }
      assertEquals(1.0d, sum, 1e-9d);
    }
  }

  private double numerical(double[] row, int i) {
    final double original = row[i];
    row[i] = original + EPSILON;
    final double plus = loss();
    row[i] = original - EPSILON;
    final double minus = loss();
    row[i] = original;
    return (plus - minus) / (2.0d * EPSILON);
  }

  private double loss() {
    return scorer.lossAndGradients(emissions, gold, transitions, start, end,
        new double[STEPS][TAGS], new double[TAGS][TAGS], new double[TAGS],
        new double[TAGS]);
  }

  private int[] bestSequenceBruteForce() {
    int[] best = new int[STEPS];
    double bestScore = Double.NEGATIVE_INFINITY;
    final int total = (int) Math.pow(TAGS, STEPS);
    for (int code = 0; code < total; code++) {
      final int[] candidate = new int[STEPS];
      int remainder = code;
      for (int t = 0; t < STEPS; t++) {
        candidate[t] = remainder % TAGS;
        remainder /= TAGS;
      }
      double score = start[candidate[0]] + end[candidate[STEPS - 1]];
      for (int t = 0; t < STEPS; t++) {
        score += emissions[t][candidate[t]];
        if (t > 0) {
          score += transitions[candidate[t - 1]][candidate[t]];
        }
      }
      if (score > bestScore) {
        bestScore = score;
        best = candidate;
      }
    }
    return best;
  }

  private static double[][] randomMatrix(Random random, int rows, int cols) {
    final double[][] matrix = new double[rows][cols];
    for (int r = 0; r < rows; r++) {
      for (int c = 0; c < cols; c++) {
        matrix[r][c] = random.nextGaussian() * 0.5d;
      }
    }
    return matrix;
  }

  private static double[] randomVector(Random random, int length) {
    final double[] vector = new double[length];
    for (int i = 0; i < length; i++) {
      vector[i] = random.nextGaussian() * 0.5d;
    }
    return vector;
  }

  private static void assertClose(double analytic, double numerical, String what) {
    GradientChecks.assertClose(analytic, numerical, TOLERANCE, what);
  }
}
