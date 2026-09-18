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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Finite-difference gradient checks for {@link LstmLayer}: the analytic
 * backpropagation gradients must match central-difference numerical gradients of a
 * scalar loss on the layer outputs. This is the hard gate for any training use of the
 * layer; a recurrent net with silently wrong gradients still learns something, just
 * not what the loss says.
 */
class LstmLayerGradientTest {

  private static final int INPUT_SIZE = 3;
  private static final int HIDDEN_SIZE = 4;
  private static final int STEPS = 5;
  private static final double EPSILON = GradientChecks.EPSILON;
  private static final double TOLERANCE = 1e-6d;

  private final Random random = new Random(42L);

  @Test
  void testInputGradientsMatchFiniteDifferences() {
    final LstmLayer layer = new LstmLayer(INPUT_SIZE, HIDDEN_SIZE, new Random(7L));
    final double[][] xs = randomSequence();
    final double[][] projection = randomProjection();

    final double[][] dXs = analyticInputGradients(layer, xs, projection);

    for (int t = 0; t < STEPS; t++) {
      for (int k = 0; k < INPUT_SIZE; k++) {
        final double analytic = dXs[t][k];
        final double numerical = numericalInputGradient(layer, xs, projection, t, k);
        assertClose(analytic, numerical, "dXs[" + t + "][" + k + "]");
      }
    }
  }

  @Test
  void testParameterGradientsMatchFiniteDifferences() {
    final LstmLayer layer = new LstmLayer(INPUT_SIZE, HIDDEN_SIZE, new Random(7L));
    final double[][] xs = randomSequence();
    final double[][] projection = randomProjection();

    final LstmLayer.Gradients gradients = analyticParameterGradients(layer, xs, projection);

    for (int r = 0; r < 4 * HIDDEN_SIZE; r++) {
      for (int k = 0; k < INPUT_SIZE; k++) {
        final double numerical = numericalWeightGradient(layer, xs, projection,
            layer.w()[r], k);
        assertClose(gradients.dw()[r][k], numerical, "dw[" + r + "][" + k + "]");
      }
      for (int k = 0; k < HIDDEN_SIZE; k++) {
        final double numerical = numericalWeightGradient(layer, xs, projection,
            layer.u()[r], k);
        assertClose(gradients.du()[r][k], numerical, "du[" + r + "][" + k + "]");
      }
      final double numerical = numericalBiasGradient(layer, xs, projection, r);
      assertClose(gradients.db()[r], numerical, "db[" + r + "]");
    }
  }

  @Test
  void testGradientsThroughCarryoverOnly() {
    // loss touches only the final hidden state, so every gradient at earlier
    // timesteps flows purely through the cell-state and hidden carryover
    final LstmLayer layer = new LstmLayer(INPUT_SIZE, HIDDEN_SIZE, new Random(11L));
    final double[][] xs = randomSequence();
    final double[][] projection = new double[STEPS][HIDDEN_SIZE];
    for (int j = 0; j < HIDDEN_SIZE; j++) {
      projection[STEPS - 1][j] = random.nextGaussian() * 0.5d;
    }

    final double[][] dXs = analyticInputGradients(layer, xs, projection);

    for (int t = 0; t < STEPS; t++) {
      for (int k = 0; k < INPUT_SIZE; k++) {
        final double numerical = numericalInputGradient(layer, xs, projection, t, k);
        assertClose(dXs[t][k], numerical, "carryover dXs[" + t + "][" + k + "]");
      }
    }
  }

  @Test
  void testRejectsEmptySequence() {
    final LstmLayer layer = new LstmLayer(INPUT_SIZE, HIDDEN_SIZE, new Random(1L));
    assertThrows(IllegalArgumentException.class,
        () -> layer.run(new double[0][INPUT_SIZE], LstmLayer.ForwardCache.of(1, HIDDEN_SIZE)));
  }

  @Test
  void testZeroedGradientsAreReusable() {
    final LstmLayer layer = new LstmLayer(INPUT_SIZE, HIDDEN_SIZE, new Random(7L));
    final double[][] xs = randomSequence();
    final double[][] projection = randomProjection();
    final LstmLayer.Gradients gradients = analyticParameterGradients(layer, xs, projection);
    gradients.zero();
    for (int r = 0; r < 4 * HIDDEN_SIZE; r++) {
      for (int k = 0; k < INPUT_SIZE; k++) {
        assertEquals(0.0d, gradients.dw()[r][k], 0.0d);
      }
      for (int k = 0; k < HIDDEN_SIZE; k++) {
        assertEquals(0.0d, gradients.du()[r][k], 0.0d);
      }
      assertEquals(0.0d, gradients.db()[r], 0.0d);
    }
  }

  private double[][] randomSequence() {
    final double[][] xs = new double[STEPS][INPUT_SIZE];
    for (int t = 0; t < STEPS; t++) {
      for (int k = 0; k < INPUT_SIZE; k++) {
        xs[t][k] = random.nextGaussian() * 0.5d;
      }
    }
    return xs;
  }

  private double[][] randomProjection() {
    final double[][] projection = new double[STEPS][HIDDEN_SIZE];
    for (int t = 0; t < STEPS; t++) {
      for (int j = 0; j < HIDDEN_SIZE; j++) {
        projection[t][j] = random.nextGaussian() * 0.5d;
      }
    }
    return projection;
  }

  private static double loss(LstmLayer layer, double[][] xs, double[][] projection) {
    final LstmLayer.ForwardCache cache =
        LstmLayer.ForwardCache.of(xs.length, HIDDEN_SIZE);
    final double[][] h = layer.run(xs, cache);
    double total = 0.0d;
    for (int t = 0; t < xs.length; t++) {
      for (int j = 0; j < HIDDEN_SIZE; j++) {
        total += projection[t][j] * h[t][j];
      }
    }
    return total;
  }

  private static double[][] analyticInputGradients(LstmLayer layer, double[][] xs,
      double[][] projection) {
    final LstmLayer.ForwardCache cache =
        LstmLayer.ForwardCache.of(xs.length, HIDDEN_SIZE);
    layer.run(xs, cache);
    final double[][] dXs = new double[xs.length][INPUT_SIZE];
    layer.backward(xs, cache, projection, dXs, layer.newGradients());
    return dXs;
  }

  private static LstmLayer.Gradients analyticParameterGradients(LstmLayer layer,
      double[][] xs, double[][] projection) {
    final LstmLayer.ForwardCache cache =
        LstmLayer.ForwardCache.of(xs.length, HIDDEN_SIZE);
    layer.run(xs, cache);
    final LstmLayer.Gradients gradients = layer.newGradients();
    layer.backward(xs, cache, projection, new double[xs.length][INPUT_SIZE], gradients);
    return gradients;
  }

  private static double numericalInputGradient(LstmLayer layer, double[][] xs,
      double[][] projection, int t, int k) {
    final double original = xs[t][k];
    xs[t][k] = original + EPSILON;
    final double plus = loss(layer, xs, projection);
    xs[t][k] = original - EPSILON;
    final double minus = loss(layer, xs, projection);
    xs[t][k] = original;
    return (plus - minus) / (2.0d * EPSILON);
  }

  private static double numericalWeightGradient(LstmLayer layer, double[][] xs,
      double[][] projection, double[] row, int k) {
    final double original = row[k];
    row[k] = original + EPSILON;
    final double plus = loss(layer, xs, projection);
    row[k] = original - EPSILON;
    final double minus = loss(layer, xs, projection);
    row[k] = original;
    return (plus - minus) / (2.0d * EPSILON);
  }

  private static double numericalBiasGradient(LstmLayer layer, double[][] xs,
      double[][] projection, int r) {
    return numericalWeightGradient(layer, xs, projection, layer.b(), r);
  }

  private static void assertClose(double analytic, double numerical, String what) {
    GradientChecks.assertClose(analytic, numerical, TOLERANCE, what);
  }
}
