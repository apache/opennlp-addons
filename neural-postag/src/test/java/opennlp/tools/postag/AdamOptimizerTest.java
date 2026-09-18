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

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Checks gradient norms, clipping and finite Adam updates. */
class AdamOptimizerTest {

  /** {@return norms near overflow, underflow and ordinary values} */
  private static Stream<Arguments> norms() {
    return Stream.of(
        Arguments.of(new double[] {0.0, -0.0}, 0.0),
        Arguments.of(new double[] {3, 4}, 5.0),
        Arguments.of(new double[] {1e200, -2e200}, Math.hypot(1e200, 2e200)),
        Arguments.of(new double[] {1e-200, -2e-200}, Math.hypot(1e-200, 2e-200)),
        Arguments.of(new double[] {1e-160, -2e-160}, Math.hypot(1e-160, 2e-160)),
        Arguments.of(new double[] {Double.MIN_VALUE}, Double.MIN_VALUE),
        Arguments.of(new double[] {Double.MAX_VALUE}, Double.MAX_VALUE),
        Arguments.of(new double[] {1e308, 1e308}, Math.hypot(1e308, 1e308)),
        Arguments.of(new double[] {Double.MAX_VALUE, Double.MAX_VALUE}, Double.POSITIVE_INFINITY));
  }

  /**
   * Finite gradients have a useful norm even when their squares are outside double range.
   *
   * @param values The gradients.
   * @param expected The Euclidean norm.
   */
  @ParameterizedTest
  @MethodSource("norms")
  void testNorm(double[] values, double expected) {
    final double actual = optimizer(values).globalNorm();
    if (Double.isFinite(expected) && Math.abs(expected) >= Double.MIN_NORMAL) {
      assertEquals(expected, actual, 4 * Math.ulp(expected));
    } else {
      assertEquals(expected, actual);
    }
  }

  /** {@return clipping cases with analytically known results} */
  private static Stream<Arguments> clipping() {
    return Stream.of(
        Arguments.of(new double[] {3, 4}, 2.5, new double[] {1.5, 2}),
        Arguments.of(new double[] {3, 4}, 5.0, new double[] {3, 4}),
        Arguments.of(new double[] {3e200, -4e200}, 5.0, new double[] {3, -4}),
        Arguments.of(new double[] {3e-200, -4e-200}, 2.5e-200,
            new double[] {1.5e-200, -2e-200}),
        Arguments.of(new double[] {1e308, -1e308}, 1.0,
            new double[] {1 / Math.sqrt(2), -1 / Math.sqrt(2)}),
        Arguments.of(new double[] {Double.MAX_VALUE, -Double.MAX_VALUE}, 1.0,
            new double[] {1 / Math.sqrt(2), -1 / Math.sqrt(2)}),
        Arguments.of(new double[] {Double.MAX_VALUE}, Double.MIN_VALUE,
            new double[] {Double.MIN_VALUE}),
        Arguments.of(new double[] {Double.MAX_VALUE}, 1e-15, new double[] {1e-15}),
        Arguments.of(new double[] {Double.MAX_VALUE, Double.MAX_VALUE, 1e-100},
            Double.MAX_VALUE, new double[] {Double.MAX_VALUE / Math.sqrt(2),
                Double.MAX_VALUE / Math.sqrt(2), 1e-100 / Math.sqrt(2)}),
        Arguments.of(new double[] {0.0, -0.0}, Double.MIN_VALUE, new double[] {0.0, -0.0}));
  }

  /**
   * Clipping retains direction without zeroing a finite gradient after norm overflow.
   *
   * @param values The initial gradients.
   * @param limit The requested norm bound.
   * @param expected The clipped gradients.
   */
  @ParameterizedTest
  @MethodSource("clipping")
  void testClipping(double[] values, double limit, double[] expected) {
    final AdamOptimizer optimizer = optimizer(values);
    optimizer.clipGradients(limit);
    final double[] actual = optimizer.gradient(0)[0];
    for (int i = 0; i < actual.length; i++) {
      if (Math.abs(expected[i]) < Double.MIN_NORMAL) {
        assertEquals(expected[i], actual[i]);
      } else {
        assertEquals(expected[i], actual[i], 4 * Math.ulp(expected[i]));
      }
    }
  }

  /** {@return invalid gradient values for both norm and update paths} */
  private static Stream<Arguments> invalidGradients() {
    return Stream.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
        .flatMap(value -> Stream.of("norm", "clip", "step")
            .map(operation -> Arguments.of(value, operation)));
  }

  /**
   * A non-finite gradient stops optimization instead of corrupting weights.
   *
   * @param value The invalid gradient.
   * @param operation The optimizer operation.
   */
  @ParameterizedTest
  @MethodSource("invalidGradients")
  void testInvalidGradient(double value, String operation) {
    final AdamOptimizer optimizer = optimizer(new double[] {value});
    assertThrows(IllegalStateException.class, () -> {
      switch (operation) {
        case "norm" -> optimizer.globalNorm();
        case "clip" -> optimizer.clipGradients(1);
        case "step" -> optimizer.step(0.01, 1);
        default -> throw new AssertionError(operation);
      }
    });
  }

  /**
   * Rate multipliers must be finite and positive.
   *
   * @param value An invalid multiplier.
   */
  @ParameterizedTest
  @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1, 0})
  void testInvalidMultiplier(double value) {
    final AdamOptimizer optimizer = new AdamOptimizer();
    assertThrows(IllegalArgumentException.class,
        () -> optimizer.register(new double[][] {{1}}, value));
    assertEquals(0, optimizer.register(new double[] {1}));
  }

  /**
   * Learning rates cannot be negative or non-finite.
   *
   * @param value An invalid rate.
   */
  @ParameterizedTest
  @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1})
  void testInvalidRate(double value) {
    assertThrows(IllegalArgumentException.class, () -> optimizer(new double[] {1}).step(value, 1));
  }

  /** {@return finite inputs whose Adam arithmetic exceeds double range} */
  private static Stream<Arguments> overflowingSteps() {
    return Stream.of(
        Arguments.of(0.0, 1e200, 0.01, 1.0),
        Arguments.of(0.0, 1.0, Double.MAX_VALUE, 2.0),
        Arguments.of(0.0, 1e150, Double.MAX_VALUE, 1.0),
        Arguments.of(Double.MAX_VALUE, -1.0, Double.MAX_VALUE, 1.0));
  }

  /**
   * Non-finite moments, products and final weights abort an update.
   *
   * @param weight The initial weight.
   * @param gradient The gradient.
   * @param rate The base rate.
   * @param multiplier Its parameter-group multiplier.
   */
  @ParameterizedTest
  @MethodSource("overflowingSteps")
  void testStepOverflow(double weight, double gradient, double rate, double multiplier) {
    final AdamOptimizer optimizer = new AdamOptimizer();
    final int index = optimizer.register(new double[][] {{weight}}, multiplier);
    optimizer.gradient(index)[0][0] = gradient;
    assertThrows(IllegalStateException.class, () -> optimizer.step(rate, 1));
  }

  /**
   * Clipping allows Adam to use a large finite gradient whose unscaled square overflows.
   */
  @Test
  void testClippedStep() {
    final double[] weights = {1.0};
    final AdamOptimizer optimizer = new AdamOptimizer();
    optimizer.gradient(optimizer.register(weights))[0][0] = 1e200;
    optimizer.clipGradients(1);
    optimizer.step(0.1, 1);
    final double expected = 1 - 0.1 / (1 + 1e-8 / Math.sqrt(1 - 0.999));
    assertEquals(expected, weights[0], 4 * Math.ulp(expected));
  }

  /** A zero rate after learning-rate decay is accepted, and reset clears gradients. */
  @Test
  void testZeroRateAndReset() {
    final double[] weights = {1, -2};
    final AdamOptimizer optimizer = new AdamOptimizer();
    final int index = optimizer.register(weights);
    optimizer.gradient(index)[0][0] = 3;
    optimizer.gradient(index)[0][1] = 4;
    optimizer.step(0, 1);
    assertArrayEquals(new double[] {1, -2}, weights);
    optimizer.zero();
    assertEquals(0, optimizer.globalNorm());
    final List<double[][]> buffers = optimizer.newGradientBuffers();
    buffers.get(0)[0][0] = 3;
    optimizer.absorb(buffers);
    assertEquals(3, optimizer.globalNorm());
  }

  /** Norms include every registered matrix, including matrices with empty rows. */
  @Test
  void testMultipleMatrices() {
    final AdamOptimizer optimizer = new AdamOptimizer();
    final int first = optimizer.register(new double[][] {{0}, {}});
    final int second = optimizer.register(new double[][] {{0, 0}});
    optimizer.gradient(first)[0][0] = 3;
    optimizer.gradient(second)[0][1] = 4;
    assertEquals(5, optimizer.globalNorm());
    optimizer.clipGradients(2.5);
    assertEquals(1.5, optimizer.gradient(first)[0][0]);
    assertEquals(2, optimizer.gradient(second)[0][1]);
    assertEquals(2.5, optimizer.globalNorm());
  }

  /**
   * A three-four-five gradient keeps its direction across powers of two.
   *
   * @param exponent The gradient scale exponent.
   */
  @ParameterizedTest
  @ValueSource(ints = {-1073, -1000, -600, -530, -500, -1, 0, 1, 500, 510, 512, 600, 1000})
  void testScaledTriangle(int exponent) {
    final double scale = Math.scalb(1.0, exponent);
    final AdamOptimizer optimizer = optimizer(new double[] {3 * scale, -4 * scale});
    assertEquals(5 * scale, optimizer.globalNorm());
    optimizer.clipGradients(2.5 * scale);
    assertArrayEquals(new double[] {1.5 * scale, -2 * scale}, optimizer.gradient(0)[0]);
  }

  /**
   * Creates an optimizer with a single vector of gradients.
   *
   * @param values The gradient values.
   * @return The optimizer.
   */
  private AdamOptimizer optimizer(double[] values) {
    final AdamOptimizer optimizer = new AdamOptimizer();
    final int index = optimizer.register(new double[values.length]);
    System.arraycopy(values, 0, optimizer.gradient(index)[0], 0, values.length);
    return optimizer;
  }
}
